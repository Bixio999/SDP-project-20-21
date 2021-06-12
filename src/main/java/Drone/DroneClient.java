package Drone;

import Drone.sensors.DroneBuffer;
import Drone.sensors.Measurement;
import Drone.sensors.PM10Simulator;
import ServerAdmin.beans.Drone;
import ServerAdmin.beans.Stat;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import droneNetwork.proto.DroneNetworkServiceGrpc;
import droneNetwork.proto.DroneNetworkServiceGrpc.DroneNetworkServiceStub;
import droneNetwork.proto.DroneNetworkServiceOuterClass;
import droneNetwork.proto.DroneNetworkServiceOuterClass.Election;
import droneNetwork.proto.DroneNetworkServiceOuterClass.Missing;
import droneNetwork.proto.DroneNetworkServiceOuterClass.Master;
import droneNetwork.proto.DroneNetworkServiceOuterClass.Hello;
import droneNetwork.proto.DroneNetworkServiceOuterClass.Position;
import droneNetwork.proto.DroneNetworkServiceOuterClass.Statistic;
import droneNetwork.proto.DroneNetworkServiceOuterClass.Delivery;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.eclipse.paho.client.mqttv3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.util.*;

/**
 * DroneClient class models the drone module which connects and manages all the functionalities and the
 * resources of the drone itself and the connection to the network.
 *
 * It implements the methods for network management (update connection to next drone, missing drone handler,
 * disconnection from network), local drone structure (add and remove drones from list), message communication
 * to next drone (RPC calls), delivery (delivery execution and statistics), drone master's tasks (delivery
 * assignment, global stats sending), and resource getters and setters for the various modules of the system.
 *
 * Because of the main role of this object, which holds most of the resources, some attributes are declared as
 * volatile in order to maintain their values updated among all the various threads that work at the same time.
 */
public class DroneClient extends Thread {

    /*   DRONE MASTER ATTRIBUTES   */

    // Used to check if the current drone is the drone master
    private volatile boolean master;
    // Queue for pending deliveries
    private Queue<Delivery> deliveryQueue;
    // Map used to recovery deliveries if the assigned drone crashes or quits
    private Map<Long, Delivery> deliveryAssignment;
    // Subscriber MQTT client for orders reception
    private MqttClient dronazonClient;
    // gRPC channel to send messages to itself. Used for drone master's deliveries in order to execute them
    // with the gRPC thread worker instead of MQTT single thread (async delivery execution)
    private ManagedChannel selfChannel;

    /*   SERVER ADMIN CONNECTION   */

    // Common prefix for drones dedicated URI
    private final String uriBase = "http://localhost:1337/drones/";
    // Client for the HTTP requests to Server Admin
    private final Client client;

    /*   DRONE NETWORK ATTRIBUTES   */

    // gRPC channel for the next drone. Stored as attribute to avoid its instantiation every RPC call
    private volatile ManagedChannel nextDroneChannel;
    // Listen gRPC server to receive requests from other drones
    private Server serverNetworkNode;
    // ID of the next drone
    private volatile long nextId;
    // Timestamp of the last message received from the network. Used to check connection
    private volatile long lastMessage;
    // ID of the drone master
    private volatile long masterId;
    // Buffer for unsent Statistic due to possible master absence
    private final List<Statistic> statsBuffer;

    /*   DRONE MODULES   */

    // Drone statistics module
    protected DroneStats stats;

    // Reference for the main class. Used to notify main thread and shut down the drone
    private final DroneMain main;

    // Information of the drone itself
    private Drone myInfo;

    // Local list of current drones in the network
    private List<Drone> droneList;

    // Pollution sensor module
    private final PM10Simulator sensor;

    // Max number of drones in the network
    public final static long maxDroneId = 8;

    // Count the number of concurrent delivery assignment. Used by main thread to verify if there are
    // delivery assignment before quit from drone network
    private volatile int assignmentCounter = 0;

    /*   LOCKS   */

    private static final class Lock { }

    private final Object droneListLock;
    private final Object lastMessageLock;
    private final Object masterIdLock;
    private final Object deliveryAssignmentLock;

    /*   CONSTRUCTOR   */

    // Default constructor to initialize the main attributes
    public DroneClient(DroneMain main) {
        master = false;
        masterId = -1;
        client = Client.create();
        this.main = main;
        this.lastMessage = -1;
        this.statsBuffer = new LinkedList<>();

        // Instantiate the locks
        this.droneListLock = new Lock();
        this.lastMessageLock = new Lock();
        this.masterIdLock = new Lock();
        this.deliveryAssignmentLock = new Lock();

        // Instantiate the sensor simulator by passing a DroneBuffer object as buffer for data
        this.sensor = new PM10Simulator(new DroneBuffer(8, 0.5f));
        this.sensor.setName("Sensor simulator");

        // Setup the local drone information
        init();

        // Update the drone status flag to set the drone as active
        DroneMain.alive = true;
    }

    /*  GETTER AND SETTER */

    public boolean isMaster() {
        return master;
    }

    public Drone getMyInfo() {
        return myInfo;
    }

    // Get the ID of the drone master. Synchronized on masterIdLock to avoid the reading while the connection is refreshing
    public long getMasterId() {
        synchronized (this.masterIdLock)
        {
            return masterId;
        }
    }

    // Update the drone master's ID. Synchronized on masterIdLock to avoid value reading during assignment
    public void setMasterId(long masterId)
    {
        synchronized (this.masterIdLock)
        {
            this.masterId = masterId;
        }
    }

    public Map<Long, Delivery> getDeliveryAssignment() {
        return deliveryAssignment;
    }

    public Queue<Delivery> getDeliveryQueue() {
        return deliveryQueue;
    }

    // Update the last message timestamp. Synchronized on lastMessageLock to avoid reading while value update
    public void setLastMessage(long lastMessage) {
        synchronized (this.lastMessageLock)
        {
            this.lastMessage = lastMessage;
        }
    }

    // Add an unsent statistic to the local buffer. Synchronized to avoid buffer cleaning while adding a new item.
    // The synchronization happens on statsBuffer's lock
    public void addStatisticToBuffer(Statistic s)
    {
        synchronized (this.statsBuffer)
        {
            this.statsBuffer.add(s);
        }
    }

    // Get the unsent statistics stored in buffer, and clean it. Synchronized on statsBuffer's lock to avoid
    // element's retrieving and cleaning during item insertion
    public List<Statistic> clearStatisticBuffer()
    {
        synchronized (this.statsBuffer)
        {
            List<Statistic> list = new LinkedList<>(this.statsBuffer);
            this.statsBuffer.clear();
            return list;
        }
    }

    /*  DRONE LIST METHODS   */

    // Get a copy of the local drone list. Synchronized on droneListLock to avoid list updates while copying
    public List<Drone> getDroneList() {
        synchronized (this.droneListLock)
        {
            return new LinkedList<>(droneList);
        }
    }

    // Add a new drone to the local drone list. Synchronized on droneListLock to avoid element insertion during
    // list retrieving or deletion
    public void addDroneToList(Drone d)
    {
        synchronized (this.droneListLock)
        {
            this.droneList.add(d);
        }
    }

    // Delete a drone from the local drone list. Synchronized on droneListLock to avoid list operations during
    // deletion
    public void removeDroneToList(Drone d)
    {
        synchronized (this.droneListLock)
        {
            this.droneList.remove(d);
        }
    }

    /*    INIT METHODS    */

    // Parallel initialization of the drone connection to network
    public void run() {
        // Set default value for nextId
        this.nextId = -1;
        // Connect to network
        networkInit();
    }

    // Initialize the drone by getting its information from user and signing in to Server Admin
    private void init() {

        // Create the console reader for user input
        BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("Initial setup new drone...");
        // Create the drone information object
        this.myInfo = new Drone();

        try {
            // Get the IP address
            System.out.print("\tInsert ip address: ");
            this.myInfo.setIpAddress(inFromUser.readLine());

            // Get the listen port of the drone
            System.out.print("\tInsert listen port: ");
            this.myInfo.setListenPort(Integer.parseInt(inFromUser.readLine()));
        } catch (IOException e) {
            e.printStackTrace();
        } catch (NumberFormatException e) { // Called if the user insert a non-numeric value for listen port
            System.out.println("Fatal Error: insert a numeric value for listen port.");
            return;
        }

        // Cycle until the insert ID for the drone is not valid and successfully signed in to Server Admin
        boolean cycle = true;
        while (cycle) {
            // Define the id with the error value
            int id = -1;

            // Read the ID value
            System.out.print("\tInsert drone ID (less than " + maxDroneId + "): ");
            try {
                id = Integer.parseInt(inFromUser.readLine());
            } catch (IOException e) {
                e.printStackTrace();
            } catch (NumberFormatException e) { // Called if the user insert a non-numeric value. Cycle to read again
                System.out.println("\t\tError: not valid data insert. Try again.");
                continue;
            }

            // If the read value is strictly positive, store it and make the HTTP request for sign in to network
            if (id < maxDroneId && id > 0) {

                // Save the ID
                this.myInfo.setId(id);

                // Create the resource with the correct URI
                WebResource resource = client.resource(uriBase + "add");
                // Get the response from server, that must be a JSON entity. The request is a POST, which asks for
                // drone information, that is the current myInfo object
                ClientResponse response = resource.accept("application/json").type("application/json").post(ClientResponse.class, this.myInfo);

                // Create the gson object to unmarshal the response
                Gson gson = new Gson();

                // Check the response status code
                switch (response.getStatus()) {
                    // The request was successful
                    case 200:
                        // Get the drone list by unmarshalling with Gson
                        this.droneList = gson.fromJson(response.getEntity(String.class), new TypeToken<List<Drone>>() {}.getType());

                        // Search in drone list the updated information (by Server Admin) of the current drone,
                        // which now contains the starting position of the drone in the smart city
                        for (Drone d : droneList) {
                            if (d.getId() == this.myInfo.getId())
                            {
                                this.myInfo = d;
                                break;
                            }
                        }
                        // Exit from cycle
                        cycle = false;
                        break;
                    // The given ID is already signed in the network. A different ID should be chosen.
                    case 406:
                        System.out.println("\t\tInsert ID is already used.");
                        break;
                    // Some error occurred and cannot able to connect to server. Print the status code received
                    default:
                        System.out.println("\t\tError: cannot connect to server. Code " + response.getStatus());
                }
            }
            // If an invalid ID was insert, warn the user and repeat
            else
                System.out.println("\t\tError: not valid data insert. Try again.");
        }
    }

    // Initialize the drone connection to network, and if successful greet the other drones with a Hello message
    private void networkInit() {

        // Create the listen server and set the service with a new instance of DroneNetworkServiceImpl
        this.serverNetworkNode = ServerBuilder.forPort(this.getMyInfo().getListenPort()).addService(new DroneNetworkServiceImpl(this)).build();
        try {
            // Start the listen server
            this.serverNetworkNode.start();
            System.out.println("[SYSTEM] Listen server started!");
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Instantiate the statistics module
        this.stats = new DroneStats(this);
        this.stats.setName("DroneStats");

        // Start the statistics module thread
        this.stats.start();

        // Start the sensor module thread
        this.sensor.start();

        // If there are not other drones in the network, the current one must be the master
        if (this.getDroneList().size() == 1) {
            // Setup and start the master tasks
            this.masterInit();
        }
        // Else find the next drone and greet to the network
        else {
            // Set the connection to the next drone
            updateConnection();

            // Create the Hello message
            Drone info = this.getMyInfo();
            Hello request = Hello.newBuilder()
                    .setId(info.getId())
                    .setIpAddress(info.getIpAddress())
                    .setListenPort(info.getListenPort())
                    .setPosition(info.getPosition())
                    .build();

            // Send the Hello message to next drone
            this.helloMessage(request);
        }
    }

    // Setup drone master data structures and connections. Synchronized on this class' lock to start master
    // tasks before doing any other network operation
    public synchronized void masterInit() {

        // Set master flag to true
        this.master = true;
        // Update the drone master ID with its ID
        this.masterId = this.myInfo.getId();
        // Instantiate the queue for pending deliveries
        this.deliveryQueue = new LinkedList<>();
        // Instantiate the map for delivery assignment
        this.deliveryAssignment = new HashMap<>();

        // Create the gRPC channel to assign deliveries to itself
        this.selfChannel = ManagedChannelBuilder.forTarget(this.myInfo.getIpAddress() + ":" + this.myInfo.getListenPort()).usePlaintext().build();

        // Setup the statistics module to network stats collection and global stats creation
        this.stats.initMaster();

        // Define the MQTT configuration
        String broker = "tcp://localhost:1883";
        String clientId = MqttClient.generateClientId();
        String topic = "dronazon/smartcity/orders";
        int qos = 1;

        // Connect to broker and define the callback for message handling
        try {
            // Create the MQTT client
            dronazonClient = new MqttClient(broker, clientId);
            MqttConnectOptions connectOptions = new MqttConnectOptions();
            connectOptions.setCleanSession(true);

            // Connect the client
            dronazonClient.connect(connectOptions);
            System.out.println("[SYSTEM] Connected to broker!");

            // Set the callback for incoming orders
            dronazonClient.setCallback(new MqttCallback() {

                // Print to console eventual connection issues
                @Override
                public void connectionLost(Throwable cause) {
                    System.out.println(clientId + "[SYSTEM - ERROR] Connection lost! cause:" + cause.getMessage());
                }

                // If a new order arrives, assign it
                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    Delivery order = Delivery.parseFrom(message.getPayload());
                    System.out.println("[SYSTEM] Delivery received. starting assignment of order #" + order.getDeliveryId() +"...");
                    DroneClient.this.assignDelivery(order);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // not used
                }
            });

            // Subscribe to Dronazon orders topic
            dronazonClient.subscribe(topic, qos);
            System.out.println("[SYSTEM] Subscribed to topics: " + topic);
        }
        // Print to console eventual MQTT errors
        catch (MqttException me) {
            System.out.println("reason " + me.getReasonCode());
            System.out.println("msg " + me.getMessage());
            System.out.println("loc " + me.getLocalizedMessage());
            System.out.println("cause " + me.getCause());
            System.out.println("excep " + me);
            me.printStackTrace();
        }
        // Print to console other eventual errors thrown
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*  DRONE MASTER'S METHODS  */

    // Assign the given delivery to a drone in the network
    public void assignDelivery(Delivery delivery) {

        // Synchronize on deliveryAssignmentLock and increase the assignment counter, in order to avoid drone
        // disconnection from network before the Delivery message sending finishes
        synchronized (this.deliveryAssignmentLock)
        {
            // If a new assignment started after drone quit from network, stop
            if ((this.nextId != -1 && this.nextDroneChannel == null) || (this.nextDroneChannel != null && this.nextDroneChannel.isShutdown()))
                return;

            // Else increase the delivery assignment counter
            this.assignmentCounter++;
        }

        Drone selected = null;
        // Set as first minimum distance to infinity, so at the first iteration the selected drone will be assigned
        double minDistance = Double.POSITIVE_INFINITY;

        // Find the best drone to complete the delivery
        for (Drone d : this.droneList) {

            // if the drone is delivering, skip to next drone
            if (d.isDelivering())
                continue;

            // If the drone master is shutting down and is assigning the pending deliveries, exclude itself
            if (!DroneMain.alive && d.getId() == this.myInfo.getId())
                continue;

            // Calculate the distance from the drone to the package retrieve position
            double distance = this.distance(this.myInfo.getPosition(), delivery.getFrom());

            // If the current minimum distance is less than the calculated one, skip to next drone
            if (minDistance < distance)
                continue;
            else if (minDistance == distance)
            {
                // If the distance is the same, check is the current selected drone has higher battery status and
                // if true skip to next drone
                if (selected.getBattery() > d.getBattery())
                    continue;
                else if (selected.getBattery() == d.getBattery()) {
                    // If the battery status is equal too, compare the ID to choose the higher
                    if (selected.getId() > d.getId())
                        continue;
                }
            }

            // Assign the drone as new selected and update the minimum distance
            selected = d;
            minDistance = distance;
        }

        System.out.println("[SYSTEM - DELIVERY] selected drone for delivery: " + (selected != null ? "#" + selected.getId() : "none"));

        // Check if a deliver drone was found
        if (selected != null) {

            // Set the drone as delivering
            selected.setDelivering(true);

            // Set the drone ID in Delivery message
            delivery = Delivery.newBuilder(delivery).setId(selected.getId()).build();

            // Check if the assigned drone is the master itself or not
            if (selected.getId() != this.myInfo.getId())
            {
                // If it is another drone, save the assignment and send the message to the next drone
                this.deliveryAssignment.put(selected.getId(), delivery);
                this.deliveryMessage(delivery);
                return;
            }
            // Else send the message to itself to begin the delivery
            else
                this.selfDeliveryMessage(delivery);
        }
        // If there are not drones ready to deliver, store the message in the pending delivery
        else {
            System.out.println("[SYSTEM - DELIVERY] No drones available, storing delivery in queue.");
            this.deliveryQueue.add(delivery);
        }

        // Update the assignment counter to report the delivery assignment completion
        synchronized (this.deliveryAssignmentLock)
        {
            this.assignmentCounter--;

            // If the drone is shutting down and there aren't any other assignment notify the main thread
            // to quit from network and complete the shutdown procedure
            if (!DroneMain.alive && this.assignmentCounter == 0)
            {
                synchronized (this)
                {
                    this.notifyAll();
                }
            }
        }
    }

    // Send a Delivery message to the drone master itself. By this way, the delivery execution will be done by a
    // gRPC worker thread, leaving the single MQTT thread ready to handle new incoming messages from Dronazon
    private void selfDeliveryMessage(final Delivery request) {

        // Create the stub from the selfChannel
        DroneNetworkServiceStub stub = DroneNetworkServiceGrpc.newStub(this.selfChannel);

        System.out.println("[SYSTEM - DELIVERY] sending delivery message to myself.");

        // Call the delivery procedure
        stub.delivery(request, new StreamObserver<DroneNetworkServiceOuterClass.Response>() {
            @Override
            public void onNext(DroneNetworkServiceOuterClass.Response value) {
                // not used
            }

            // When error occurs, store the delivery in the pending deliveries queue
            @Override
            public void onError(Throwable t) {
                System.out.println("Error during self message. queuing delivery...");
                DroneClient.this.deliveryQueue.add(request);
            }

            // Console log the correct message sending
            @Override
            public void onCompleted() {
                System.out.println("[SYSTEM - DELIVERY] self delivery message correctly sent.");
            }
        });
    }

    // Send the given global stats to Server Admin
    public void sendGlobalStats(Stat globalStat) {

        // Create the resource with the correct URI
        WebResource resource = client.resource(uriBase + "stats");
        // Receive the response from server, by sending with a POST request the global stats object
        ClientResponse response = resource.type("application/json").post(ClientResponse.class, globalStat);

        // Check if the request was successful or not, and console log the event
        if (response.getStatus() == 200)
            System.out.println("[SYSTEM - GLOBAL STATS] Global stats correctly sent to server.");
        else
            System.out.println("[SYSTEM - GLOBAL STATS] Error: can't send global stats to server due to communication issues.");
    }

    /*   DRONE NETWORK METHODS   */

    // Handle the missing next drone event by removing it from its local drones list, update the network connection,
    // and send the Missing message to the other drones (if there are any). Synchronized to this class' lock in
    // order to restore the network connection before any other operation
    private synchronized void missingNextDrone() {

        // Save the missing drone ID before setting the current next drone ID to default value
        long oldNextId = this.nextId;
        this.nextId = -1;

        // Find the missing drone in the local drones list
        for (Drone d : this.getDroneList())
        {
            if (d.getId() == oldNextId)
            {
                // Remove it from the drones list
                this.removeDroneToList(d);
                // Update the connection to restore the network
                this.updateConnection();

                // If the current drone is the master, and the missing drone was delivering when crashed or quit,
                // add its delivery to the pending deliveries queue
                if (this.master && this.deliveryAssignment.containsKey(d.getId()))
                    this.deliveryQueue.add(this.deliveryAssignment.remove(d.getId()));

                // Check if there are not any other drones in the network. If true, the drone master just end the
                // procedure, but a drone which realizes to be alone must became the new master
                if (this.nextId == -1)
                {
                    System.out.println("[SYSTEM - MISSING] no other drones in the network.");

                    // Stop the old next drone channel
                    this.nextDroneChannel.shutdown();
                    this.nextDroneChannel = null;

                    if (this.master)
                        return;
                    System.out.println("[SYSTEM - MISSING] i must be the new master.");
                    this.masterInit();
                }
                // Else if there are other drones in the network, check if the missing drone was the master. In that
                // case an election must begin.
                else if (oldNextId == this.masterId)
                {
                    System.out.println("[SYSTEM - MISSING] drone master missing. must begin new elections...");

                    // Send in the network the election message with itself as initial current candidate
                    this.electionMessage(Election.newBuilder().setId(this.myInfo.getId()).setBattery(this.myInfo.getBattery()).build());
                }
                // Else send a Missing message to warn the other drones by the absence of that drone
                else
                {
                    Missing message = Missing.newBuilder()
                            .setMissingId(oldNextId)
                            .setSenderId(this.myInfo.getId())
                            .build();
                    this.missingMessage(message);
                }
                break;
            }
        }
    }

    // Set or update the connection to the next drone, if present
    public void updateConnection() {

        // Set the temporary next variable to null as default value
        Drone next = null;
        // Set as the initial max distance between drones IDs the max value the IDs can have, which is impossible
        // to obtain as distance because the higher distance is maxDroneId/2 computed in the case with full network
        // and between two IDs in symmetrical position (e.g. maxDrone = 8, drone IDs = 0 and 4, distance = 4)
        long minDistance = DroneClient.maxDroneId;

        // Find the nearest drone to the current one. The distance between two drones is computed by the module
        // to maxDroneId of the difference of their ID. This grants to solve the min search in linear time (based
        // on the current size of the drones list)
        for (Drone d : this.getDroneList()) {
            if (d != this.getMyInfo()) {
                long distance = Math.floorMod(d.getId() - this.getMyInfo().getId(), DroneClient.maxDroneId);
                if (distance < minDistance) {
                    next = d;
                    minDistance = distance;
                }
            }
        }
        // Console log the eventual new next drone
        System.out.println("[SYSTEM] next drone: " + (next!= null? "#" + next.getId() : "none"));

        // If no next drone was found, set to default value nextId and shutdown and destroy the old channel
        if (next == null)
        {
            // If the old next was already null, end the procedure
            if (this.nextId == -1)
                return;

            // Else update the attributes. Synchronized on this class' lock to avoid stub creation while
            // changing values
            synchronized (this)
            {
                this.nextId = -1;
                if (this.nextDroneChannel != null){
                    this.nextDroneChannel.shutdown();
                    this.nextDroneChannel = null;
                }
            }
        }
        // Else there is a next drone found
        else
        {
            // If the next drone found is still the same, end the procedure because nothing has to be changed
            if (this.nextId == next.getId())
                return;

            // Else a new nearest drone was found, then update the connection. Synchronized on this object's
            // lock to avoid stub creation while changing the channel
            synchronized (this)
            {
                // Set the new next ID
                this.nextId = next.getId();

                // If there the previous next drone wasn't null, shutdown its channel before the change
                if (this.nextDroneChannel != null)
                    this.nextDroneChannel.shutdown();
                // Set the new next drone channel by creating a new gRPC client channel toward the new next
                // drone IP address and listen port
                this.nextDroneChannel = ManagedChannelBuilder.forTarget(next.getIpAddress() + ":" + next.getListenPort()).usePlaintext().keepAliveWithoutCalls(true).build();
            }
        }
    }

    // Check if more than 10 seconds passed since the last message received from another drone, and if true test
    // if the connection is still active. Else execute the missingNextDrone method to warn the network for the
    // drone absence.
    public void checkConnectionTimeout() {

        // If no messages were received or the current nextDroneChannel is not set, there is no need to check
        // because the drone is still connecting or is alone in the network
        if (this.lastMessage == -1 || this.nextDroneChannel == null)
            return;

        // Get the current timestamp
        long currTime = System.currentTimeMillis(), lastMessage;

        // Synchronize on lastMessageLock to avoid reading from lastMessage attribute while it is getting updated
        synchronized (this.lastMessageLock)
        {
            lastMessage = this.lastMessage;
        }

        // Check if 10 seconds passed
        if ((currTime - lastMessage) >= 10000)
        {
            System.out.println("[SYSTEM] no recent messages received. checking connection...");

            // Test the connection state of the next drone by pinging it. The getState method of ManagedChannel
            // class let you to test the status of the connection. If the passed parameter is false the check
            // is only done to the local channel without ping, else if true try a connection test and check if
            // the server answers or not. When the server shuts down, the pinging fails and the method returns
            // a TRANSIENT_FAILURE status, meaning the TCP connection to server failed
            ConnectivityState connectionState = this.nextDroneChannel.getState(true);

            // Check the connection state obtained by the test
            switch (connectionState)
            {
                // If the channel is in IDLE, READY or CONNECTING status, means that the connection is still
                // alive
                case IDLE:
                case READY:
                case CONNECTING:
                    System.out.println("[SYSTEM] Connection to next drone still active.");
                    break;
                // The current client channel is shutting down due to next drone update or drone shutdown
                // procedure
                case SHUTDOWN:
                    System.out.println("[SYSTEM] current channel shutting down.");
                    break;
                // A TPC error was found and the connection to server failed, meaning the next drone quit. So
                // the missingNNextDrone method must be called
                case TRANSIENT_FAILURE:
                    System.out.println("[SYSTEM] next drone missing! its id was: #" + DroneClient.this.nextId);

                    // If the current drone has not changed the connection in the meantime, call the
                    // missingNextDrone method. Synchronize to this class' lock due to nextId reading
                    synchronized (this)
                    {
                        if (this.nextId != -1)
                            this.missingNextDrone();
                    }
                    break;
                // For any other state, console log that an unknown status was acquired
                default:
                    System.out.println("[SYSTEM - ERROR] An unknown error on network channel was found! Server unavailable");
            }
        }
    }

    // Disconnect the current drone from network, shutdown all modules and then terminate its execution. Synchronized
    // on this class' lock to terminate all the drone resources and modules without any other concurrent operations
    public synchronized void disconnect() {

        // If the drone is currently delivering, wait until it ends
        while (this.myInfo.isDelivering())
        {
            System.out.println("\tDrone is delivering, waiting...");
            try {
                this.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // Stop the sensor module
        this.sensor.stopMeGently();

        // Disconnect from MQTT broker
        if (this.dronazonClient != null)
        {
            try {
                this.dronazonClient.disconnect();
            } catch (MqttException e) {
                e.printStackTrace();
            }
            System.out.println("\tDisconnected from MQTT broker.");
        }

        // Check if there are delivery assignment currently running, and if true wait until they aren't completed
        if (this.isMaster())
        {
            // Create a local variable to store the number of current delivery assignment, in order to release
            // the lock after the reading
            int assignmentCounter;

            // Synchronize on deliveryAssignmentLock to avoid network shutdown while the last delivery assignment
            // is running
            synchronized (this.deliveryAssignmentLock)
            {
                // Read the attribute value
                assignmentCounter = this.assignmentCounter;
            }

            // Repeat until there there are assignment running
            while (assignmentCounter > 0)
            {
                // if some assignment is running, wait until they ends
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // Update the assignment counter's value to check if there are more assignment
                synchronized (this.deliveryAssignmentLock)
                {
                    // Read the attribute value
                    assignmentCounter = this.assignmentCounter;
                }
            }
        }

        // If the drone is the master, it must assign the remaining pending deliveries before it quits from the
        // network
        if (this.isMaster())
        {
            System.out.println("\tAssigning pending deliveries...");

            // Repeat until the pending deliveries queue is not empty
            while (!this.deliveryQueue.isEmpty())
            {
                // Check if there is any other drone in the network
                if (this.droneList.size() > 1)
                {
                    // If none of the other drones is currently delivering, assign the first pending delivery and
                    // then wait until they end (the notify is called in the Statistic request handler)
                    if (this.deliveryAssignment.isEmpty())
                        this.assignDelivery(this.deliveryQueue.poll());

                    // Else, just wait and they will be automatically assigned when the drones send their statistics
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                // There is no other drones in the network. The master waits for 30 seconds that new drones join the
                // network so it can assign the pending deliveries
                else
                {
                    System.out.println("\t\tNo drones in the network. Waiting some time for new drone that may deliver...");
                    try {
                        this.wait(30000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    // If it is still alone, quit anyways
                    if (this.droneList.size() <= 1)
                    {
                        System.out.println("\t\tStill no drones in the network. Pending deliveries will be lost.");
                        break;
                    }
                }
            }
        }

        System.out.println("\tDisconnecting from Network...");

        // Shutdown the gRPC client channel
        if (this.nextDroneChannel != null)
            this.nextDroneChannel.shutdown();

        // If the drone is the master, it has to shutdown the self client channel too
        if (this.isMaster())
            this.selfChannel.shutdown();

        // Shutdown the listen server
        this.serverNetworkNode.shutdown();

        System.out.println("\t\tDrone correctly disconnected from Network.");

        // If the drone is the master, it has to send the the remaining global stats to Server Admin
        if (this.isMaster())
        {
            // Stop the statistics module
            this.stats.stopMeGently();

            System.out.println("\tWaiting until last global stats are not sent...");

            // If there are not global stats, continue with shutdown procedure
            if (this.stats.isGlobalStatsEmpty())
                System.out.println("\t\tNo global stats to send.");
            else
            {
                // Wait for the global stats sending
                while (!this.stats.isGlobalStatsEmpty())
                {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        // If the drone is not the master, it can hard stop the statistics module by interrupting its thread
        else
            this.stats.interrupt();

        // Create the resource for Server Admin's signing out from network procedure
        WebResource resource = client.resource(uriBase + "remove/" + this.myInfo.getId());
        // Get the response of the operation
        ClientResponse response = resource.get(ClientResponse.class);

        // Check the response status code
        switch (response.getStatus()) {
            // Sign out successful
            case 200:
                System.out.println("\tDrone signed out.");
                break;
            // The Server Admin doesn't have any drone signed to the given ID
            case 406:
                System.out.println("\tError: Drone ID not found by Server Admin.");
                break;
            // Connection failed. Console log the error
            default:
                System.out.println("\t\tError: cannot connect to server. Code " + response.getStatus());
        }
    }

    /*   RPC CALLS METHODS   */

    // Send the given Delivery message to next drone. Synchronized on this class' lock to avoid channel changes while
    // creating the stub and calling the remote procedure
    public synchronized void deliveryMessage(final Delivery request) {

        // Create the stub to the current next drone channel
        DroneNetworkServiceStub stub = DroneNetworkServiceGrpc.newStub(this.nextDroneChannel);

        // Call the remote procedure and define the callback
        stub.delivery(request, new StreamObserver<DroneNetworkServiceOuterClass.Response>() {
            @Override
            public void onNext(DroneNetworkServiceOuterClass.Response value) {
                // not used
            }

            // Called when message sending to next drone fails. Handles the quit of next drone by testing the
            // connection to it
            @Override
            public void onError(Throwable t) {

                // Test the connection to the next drone. If it quits, the method returns a TRANSIENT_FAILURE status
                if (DroneClient.this.nextDroneChannel.getState(true).equals(ConnectivityState.TRANSIENT_FAILURE)) {
                    // Save the missing drone ID before connection update
                    long missing_id = DroneClient.this.nextId;

                    System.out.println("[DELIVERY - ERROR] next drone missing! its id was: #" + missing_id);

                    // Handle its quit and warn the network about the drone absence
                    DroneClient.this.missingNextDrone();

                    // Check if the current drone is the master
                    if (DroneClient.this.isMaster())
                    {
                        // Check if the missing drone is the one selected for the delivery and there are more
                        // drones in the network
                        if (missing_id != request.getId() && DroneClient.this.nextId != -1)
                            // If the missing drone is not the selected one, retry the sending of the message
                            DroneClient.this.deliveryMessage(request);
                        else
                            // Reassign the delivery to another drone
                            DroneClient.this.assignDelivery(request);
                    }
                }
                // Else if the next drone is still connected, console log the error caught
                else
                    System.out.println("[DELIVERY - ERROR] Error found! " + t.getMessage());
            }

            // Console log the successful delivery of the message
            @Override
            public void onCompleted() {
                System.out.println("[DELIVERY] Message delivered correctly.");

                // If the current drone is the master, update the assignment counter to warn the operation
                // completion
                if (DroneClient.this.isMaster())
                {
                    // Update the assignment counter to report the delivery assignment completion
                    synchronized (DroneClient.this.deliveryAssignmentLock)
                    {
                        DroneClient.this.assignmentCounter--;

                        // If the drone is shutting down and there aren't any other assignment notify the main thread
                        // to quit from network and complete the shutdown procedure
                        if (!DroneMain.alive && DroneClient.this.assignmentCounter == 0)
                        {
                            synchronized (DroneClient.this)
                            {
                                DroneClient.this.notifyAll();
                            }
                        }
                    }
                }
            }
        });
    }

    // Send the given Election message to next drone. Synchronized on this class' lock to avoid channel changes while
    // creating the stub and calling the remote procedure
    public synchronized void electionMessage(final Election request) {

        // Create the stub to the current next drone channel
        DroneNetworkServiceStub stub = DroneNetworkServiceGrpc.newStub(this.nextDroneChannel);

        // Call the remote procedure and define the callback
        stub.election(request, new StreamObserver<DroneNetworkServiceOuterClass.Response>() {
            @Override
            public void onNext(DroneNetworkServiceOuterClass.Response value) {
                //not used
            }

            // Called when message sending to next drone fails. Handles the quit of next drone by testing the
            // connection to it
            @Override
            public void onError(Throwable t) {

                // Test the connection to the next drone. If it quits, the method returns a TRANSIENT_FAILURE status
                if (DroneClient.this.nextDroneChannel.getState(true).equals(ConnectivityState.TRANSIENT_FAILURE))
                {
                    // Save the missing drone ID before connection update
                    long missing_id = DroneClient.this.nextId;

                    System.out.println("[ELECTION - ERROR] next drone missing! its id was: " + missing_id);

                    // Handle its quit and warn the network about the drone absence
                    DroneClient.this.missingNextDrone();

                    // If there are no more drones in the network, the current drone became the master and the
                    // election is now useless
                    if (DroneClient.this.nextId == -1)
                        return;

                    // If there are other drones, check if the missing drone was the election candidate of the message
                    if (missing_id == request.getId())
                    {
                        // If true, console log the event and begin a new election
                        System.out.println("[ELECTION] current candidate is missing. Must begin new election.");
                        DroneClient.this.electionMessage(Election.newBuilder()
                                                            .setId(DroneClient.this.myInfo.getId())
                                                            .setBattery(DroneClient.this.myInfo.getBattery())
                                                            .build());
                    }
                    // Else just retry the message sending
                    else
                        DroneClient.this.electionMessage(request);
                }
                // Else if the next drone is still connected, console log the error caught
                else
                    System.out.println("Error found! " + t.getMessage());
            }

            // Console log the successful delivery of the message
            @Override
            public void onCompleted() {
                System.out.println("[ELECTION] message delivered correctly.");
            }
        });
    }

    // Send the given Missing message to next drone. Synchronized on this class' lock to avoid channel changes while
    // creating the stub and calling the remote procedure
    public synchronized void missingMessage(final Missing request) {

        // Create the stub to the current next drone channel
        DroneNetworkServiceStub stub = DroneNetworkServiceGrpc.newStub(this.nextDroneChannel);

        // Call the remote procedure and define the callback
        stub.missing(request, new StreamObserver<DroneNetworkServiceOuterClass.Response>() {
            @Override
            public void onNext(DroneNetworkServiceOuterClass.Response value) {
                // not used
            }

            // Called when message sending to next drone fails. Handles the quit of next drone by testing the
            // connection to it
            @Override
            public void onError(Throwable t) {

                // Test the connection to the next drone. If it quits, the method returns a TRANSIENT_FAILURE status
                if (DroneClient.this.nextDroneChannel.getState(true).equals(ConnectivityState.TRANSIENT_FAILURE))
                {
                    System.out.println("[MISSING - ERROR] next drone missing! its id was: " + DroneClient.this.nextId);

                    // Handle its quit and warn the network about the drone absence
                    DroneClient.this.missingNextDrone();

                    // Retry the sending of the message if there are other drones in the network
                    if (DroneClient.this.nextId != -1)
                        DroneClient.this.missingMessage(request);
                }
                // Else if the next drone is still connected, console log the error caught
                else
                    System.out.println("[MISSING - ERROR] Error found! " + t.getMessage());
            }

            // Console log the successful delivery of the message
            @Override
            public void onCompleted() {
                System.out.println("[MISSING] Message delivered correctly.");
            }
        });
    }

    // Send the given Statistic message to next drone. Synchronized on this class' lock to avoid channel changes while
    // creating the stub and calling the remote procedure
    public synchronized void statisticMessage(final Statistic request) {

        // Create the stub to the current next drone channel
        DroneNetworkServiceStub stub = DroneNetworkServiceGrpc.newStub(this.nextDroneChannel);

        // Call the remote procedure and define the callback
        stub.statistics(request, new StreamObserver<DroneNetworkServiceOuterClass.Response>() {
            @Override
            public void onNext(DroneNetworkServiceOuterClass.Response value) {
                // not used
            }

            // Called when message sending to next drone fails. Handles the quit of next drone by testing the
            // connection to it
            @Override
            public void onError(Throwable t) {

                // Test the connection to the next drone. If it quits, the method returns a TRANSIENT_FAILURE status
                if (DroneClient.this.nextDroneChannel.getState(true).equals(ConnectivityState.TRANSIENT_FAILURE))
                {
                    System.out.println("[STATISTIC - ERROR] next drone missing! its id was: " + DroneClient.this.nextId);

                    // Handle its quit and warn the network about the drone absence
                    DroneClient.this.missingNextDrone();

                    // Retry the sending of the message if there are other drones in the network
                    if (DroneClient.this.nextId != -1)
                        DroneClient.this.statisticMessage(request);
                    else if (request.getSenderId() == DroneClient.this.getMyInfo().getId())
                        DroneClient.this.myInfo.setDelivering(false);
                }
                // Else if the next drone is still connected, console log the error caught
                else
                    System.out.println("[STATISTIC - ERROR] Error found! " + t.getMessage());
            }

            // If the drone is sending the delivery completion statistics, update the delivering flag and
            // check if the drone is shutting down. In that case, notify the main thread which is waiting
            // for delivery completion, and continue the shutdown procedure
            @Override
            public void onCompleted() {

                // Check if the Statistic message sent was created by itself due to delivery completion
                if (request.getSenderId() == DroneClient.this.getMyInfo().getId()) {

                    // Set back the delivering flag to false
                    DroneClient.this.myInfo.setDelivering(false);

                    // If the drone is shutting down, the main thread is waiting for delivery completion
                    if (!DroneMain.alive)
                    {
                        synchronized (DroneClient.this)
                        {
                            DroneClient.this.notifyAll();
                        }
                    }
                }
                System.out.println("[STATISTICS] Message delivered correctly.");
            }
        });
    }

    // Send the given Hello message to next drone. Synchronized on this class' lock to avoid channel changes while
    // creating the stub and calling the remote procedure
    public synchronized void helloMessage(final Hello request) {

        // Create the stub to the current next drone channel
        DroneNetworkServiceStub stub = DroneNetworkServiceGrpc.newStub(this.nextDroneChannel);

        // Call the remote procedure and define the callback
        stub.hello(request, new StreamObserver<DroneNetworkServiceOuterClass.Response>() {
            @Override
            public void onNext(DroneNetworkServiceOuterClass.Response value) {
                // not used
            }

            // Called when message sending to next drone fails. Handles the quit of next drone by testing the
            // connection to it
            @Override
            public void onError(Throwable t) {

                // Test the connection to the next drone. If it quits, the method returns a TRANSIENT_FAILURE status
                ConnectivityState connectionState = DroneClient.this.nextDroneChannel.getState(true);
                if (connectionState.equals(ConnectivityState.TRANSIENT_FAILURE))
                {
                    System.out.println("[HELLO - ERROR] next drone missing! its id was: " + DroneClient.this.nextId);

                    // Handle its quit and warn the network about the drone absence
                    DroneClient.this.missingNextDrone();

                    // Retry the sending of the message if there are other drones in the network
                    if (DroneClient.this.nextId != -1)
                        DroneClient.this.helloMessage(request);
                }
                // Else if the next drone is still connected, console log the error caught
                else
                    System.out.println("[HELLO - ERROR] Error found! " + t.getMessage());
            }

            // Console log the successful delivery of the message
            @Override
            public void onCompleted() {
                System.out.println("[HELLO] Message delivered correctly.");
            }
        });
    }

    // Send the given Master message to next drone. Synchronized on this class' lock to avoid channel changes while
    // creating the stub and calling the remote procedure
    public synchronized void masterMessage(final Master request) {

        // Create the stub to the current next drone channel
        DroneNetworkServiceStub stub = DroneNetworkServiceGrpc.newStub(this.nextDroneChannel);

        // Call the remote procedure and define the callback
        stub.master(request, new StreamObserver<DroneNetworkServiceOuterClass.Response>() {
            @Override
            public void onNext(DroneNetworkServiceOuterClass.Response value) {
                // not used
            }

            // Called when message sending to next drone fails. Handles the quit of next drone by testing the
            // connection to it
            @Override
            public void onError(Throwable t) {
                ConnectivityState connectionState = DroneClient.this.nextDroneChannel.getState(true);
                if (connectionState.equals(ConnectivityState.TRANSIENT_FAILURE))
                {
                    System.out.println("[MASTER - ERROR] next drone missing! its id was: " + DroneClient.this.nextId);

                    // Handle its quit and warn the network about the drone absence
                    DroneClient.this.missingNextDrone();

                    // Retry the sending of the message if there are other drones in the network
                    if (DroneClient.this.nextId != -1)
                        DroneClient.this.masterMessage(request);
                }
                // Else if the next drone is still connected, console log the error caught
                else
                    System.out.println("[MASTER - ERROR] Error found! " + t.getMessage());
            }

            // Console log the successful delivery of the message
            @Override
            public void onCompleted() {
                System.out.println("[MASTER] Message delivered correctly.");
            }
        });
    }

    /*   DELIVERY METHODS   */

    // Execute the given delivery. After the order is successfully deliverer, update the local statistics and send
    // the information to drone master.
    public void doDelivery(Delivery request) {

        // Update the deliver status flag
        this.myInfo.setDelivering(true);

        System.out.println("[SYSTEM - DELIVERY] Delivering order no: " + request.getDeliveryId());

        // Execute the delivery
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Save the timestamp at the completion of the delivery
        long timestamp = System.currentTimeMillis();

        // Calculate the total distance crossed during this delivery (from its old position to the package retrieve,
        // to the destination)
        double distance = this.distance(this.getMyInfo().getPosition(), request.getFrom()) + this.distance(request.getFrom(), request.getTo());

        // Update the drone position
        this.getMyInfo().setPosition(request.getTo());
        // Update the drone battery status
        this.getMyInfo().setBattery(this.getMyInfo().getBattery() - 10);

        // Update the local statistics
        this.stats.addDistanceCoverage(distance);
        this.stats.increaseDeliveryCount();

        // Create the delivery statistics for the drone master
        this.generateStatistics(timestamp, distance);

        // If the current drone is the master, set now the delivering flag to false due to no statistics message
        // sending. Also, check if the drone is shutting down, and in that case notify the main thread to complete
        // the shutdown procedure
        if (this.isMaster())
        {
            // Set back the deliver status flag
            this.myInfo.setDelivering(false);

            // If the drone is shutting down, the main thread is waiting for delivery completion
            if (!DroneMain.alive)
            {
                synchronized (this)
                {
                    this.notifyAll();
                }
                return;
            }
        }

        // Else if the drone is still active, check if the current battery status is low
        if (this.getMyInfo().getBattery() < 15)
        {
            System.out.println("[SYSTEM] Low battery, shutting down...");

            // Drone must shut down: synchronize to DroneMain object's lock, update the alive flag and notify
            // the main thread to begin the shutdown procedure
            synchronized (this.main) {
                DroneMain.alive = false;
                this.main.notifyAll();
            }
        }
        // Else if the current drone is the drone master, assign a pending delivery if present
        else if (this.master && !this.deliveryQueue.isEmpty())
            this.assignDelivery(this.deliveryQueue.poll());
    }

    // Generate the statistics for drone master.
    private void generateStatistics(long timestamp, double distance) {

        // Get the pollution data acquired by the sensor during the last delivery
        List<Double> pollution = new ArrayList<>();
        // Get all the measurement from the sensor and clean its buffer
        for (Measurement m : this.sensor.getBuffer().readAllAndClean())
            pollution.add(m.getValue());

        // Create the Statistic message, with all the information needed
        Statistic stats = Statistic.newBuilder()
                .setTimestamp(new Timestamp(timestamp).toString())
                .addAllAvgPollution(pollution)
                .setDistance(distance)
                .setPosition(Position.newBuilder()
                        .setPosition(this.getMyInfo().getPosition())
                        .setBattery(this.getMyInfo().getBattery()))
                .setSenderId(this.getMyInfo().getId())
                .setDeliveriesCompleted(this.stats.getDeliveriesCompleted())
                .build();

        // If the current drone is the drone master, just add the Statistic object to its local stats list
        if (this.isMaster())
            this.stats.addDroneStat(stats);
        // Else the drone must send it to the master
        else
        {
            // Send the Statistic message to drone master
            this.statisticMessage(stats);

            // If there are any other stats stored in the buffer (their previous sending failed due to master's
            // absence) retry their sending
            if (!this.statsBuffer.isEmpty())
            {
                for (Statistic s : this.clearStatisticBuffer())
                    this.statisticMessage(s);
            }
        }
    }

    /*   UTILITY   */

    // Calculate the distance between two given position strings
    private double distance(String from, String to) {
        // Convert the 'from' position to the two coordinates by splitting the string on the separator character ':'
        String[] tok = from.split(":");
        // Store the two coords
        int fromX = Integer.parseInt(tok[0]), fromY = Integer.parseInt(tok[1]);

        // Convert the 'to' position to the two coords
        tok = to.split(":");
        int toX = Integer.parseInt(tok[0]), toY = Integer.parseInt(tok[1]);

        // Return the distance calculated with the Euclidean distance formula
        return Math.sqrt(Math.pow(toX - fromX, 2) + Math.pow(toY - fromY, 2));
    }
}

