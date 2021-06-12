package Drone;

import ServerAdmin.beans.Drone;
import droneNetwork.proto.DroneNetworkServiceGrpc.*;
import droneNetwork.proto.DroneNetworkServiceOuterClass.*;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;

import java.util.LinkedList;
import java.util.List;

/**
 * Concrete class for the Drone Network gRPC service.
 *
 * It implements the methods for each request the drone may receive.
 *
 * The gRPC server interacts with drone methods by the attribute network, which is the reference for the
 * DroneClient object. This is mandatory to evaluate the received messages and forward them to the next
 * drone (due to ring-based drone network architecture).
 */
public class DroneNetworkServiceImpl extends DroneNetworkServiceImplBase {

    // Let the gRPC server workers to call DroneClient methods to handle the requests
    private final DroneClient network;

    // Default constructor; set the network attribute with the received DroneClient reference
    public DroneNetworkServiceImpl(DroneClient d)
    {
        this.network = d;
    }

    // Handle the Hello requests. Received only when a new drone enters the network, and the message has not
    // reached the master yet. The drones who receive a hello message must add to their local drone list the
    // information about the new drone, and then forward the message to next drone.
    // If the master receives an hello message, after signing the new drone information wrap the message into
    // a Master message which contains its ID too. This message will be consumed by the new drone to store the
    // current master ID.
    @Override
    public void hello(Hello request, StreamObserver<Response> responseObserver) {
        System.out.println("[HELLO] hello message received. sending ack...");

        // Call the onNext and onCompleted methods to warn the previous drone the message was correctly received
        // and ends the request procedure. No values are sent as response in onNext call because there is no data
        // exchange toward the previous drone.
        responseObserver.onNext(null);
        responseObserver.onCompleted();

        // Update the last message timestamp for next drone connection status check
        this.network.setLastMessage(System.currentTimeMillis());

        // Almost always true check: used to destroy the message when the master doesn't receive the hello message
        // from new drone (due to its disconnection) and the message returns to the sender. In that case even without
        // master all the other drones in the network knows the new drone information.
        if (request.getId() != this.network.getMyInfo().getId())
        {
            // Add the new drone to the local drone list
            System.out.println("[HELLO] new drone detected, adding to local list.");
            Drone d = new Drone(request.getId(), request.getIpAddress(), request.getListenPort(), request.getPosition());
            this.network.addDroneToList(d);
            // Check if the new drone must be the current drone's next, and in that case setup the new client
            this.network.updateConnection();

            // Due to RPC calls to the next drone in a RPC request handling call, the context should be forked and
            // changed before making the request
            Context newContext;
            Context origContext;

            // If the current drone is the master, wrap the Hello message into a Master message and adds its ID
            if (this.network.isMaster())
            {
                Master message = Master.newBuilder().setId(this.network.getMasterId()).setNewDrone(request).build();

                // Change the gRPC context to call the "make request" method
                newContext = Context.current().fork();
                origContext = newContext.attach();

                try {
                    // Send the new Master message to the next drone
                    this.network.masterMessage(message);
                } finally {
                    // Back to the original context to complete the call and end the thread
                    newContext.detach(origContext);
                }

                // If the master drone was shutting down but there are pending deliveries, it is waiting for new
                // drones to join the network in order to assign them
                if (!DroneMain.alive)
                {
                    synchronized (this.network)
                    {
                        this.network.notifyAll();
                    }
                }
            }
            // If the current drone is not the master, just forward the Hello message to the next drone
            else
            {
                newContext = Context.current().fork();
                origContext = newContext.attach();
                try {
                    this.network.helloMessage(request);
                } finally {
                    newContext.detach(origContext);
                }
            }
        }
        else
            // Console log that the message is back to the new drone without reaching the master
            System.out.println("[HELLO] Hello message returned to me. Master may be gone...");
    }

    // Handle the Master requests. When a Master message is received there can be only three cases: there is a
    // new drone in the network, a new master has been elected and asking for drones current position, or the
    // current drone is the master and is collecting the positions of all the drones to begin the master tasks.
    @Override
    public void master(Master request, StreamObserver<Response> responseObserver) {
        System.out.println("[MASTER] master message received. sending ack...");

        // Call the onNext and onCompleted methods to warn the previous drone the message was correctly received
        // and ends the request procedure. No values are sent as response in onNext call because there is no data
        // exchange toward the previous drone.
        responseObserver.onNext(null);
        responseObserver.onCompleted();

        // Update the last message timestamp for next drone connection status check
        this.network.setLastMessage(System.currentTimeMillis());

        // If the message has the elected flag set to true, it is about master election
        if (request.getElected())
        {
            // If the current drone is the sender, it is the new master
            if (request.getId() == this.network.getMyInfo().getId())
            {
                System.out.println("[MASTER] master message returned to me and retrieved all positions.");
                System.out.println("[MASTER] updating local drone positions...");

                // Get a copy of the local drone list, and remove itself from it in order to get only the other
                // drones in it to match the object in message position list.
                List<Drone> droneList = this.network.getDroneList();
                droneList.remove(this.network.getMyInfo());

                // Get the position list from the message
                List<Position> positionList = new LinkedList<>(request.getPositionListList());

                // Sort the two lists by the drones' IDs, so they match the lists indexes to the same drone.
                // This solution avoid to find the corresponding position for each drone which causes a O(n^2)
                // cost, and complete the drones' position update in O(n logn) (due to sort)
                positionList.sort((o1, o2) -> (int)(o1.getId() - o2.getId()));
                droneList.sort((o1, o2) -> (int)(o1.getId() - o2.getId()));

                // Parallel visit the two lists and update the drones position
                for (int i = 0, j = 0; i < droneList.size() && j < positionList.size(); i++, j++)
                {
                    Position p = positionList.get(i);
                    Drone d = droneList.get(i);

                    // Avoid lists inconsistency that may be happens due to drones that enter or quit during election
                    while (d.getId() != p.getId())
                    {
                        if (d.getId() > p.getId())
                        {
                            j++;
                            if (j < positionList.size())
                                p = positionList.get(j);
                            else
                                break;
                        }
                        else
                        {
                            i++;
                            if (i < droneList.size())
                                d = droneList.get(i);
                            else
                                break;
                        }
                    }

                    d.setPosition(p.getPosition());
                    d.setBattery(p.getBattery());
                }

                // After update the drones position, initialize all the connections and data structures of the master
                // and begin its tasks
                System.out.println("[MASTER] starting master init...");
                this.network.masterInit();
                return;
            }
            // Else if the current drone is not the master, store the new master ID and add its position to message list
            else
            {
                System.out.println("[MASTER] received election results. storing new master and adding my position.");

                // Update the master's ID
                this.network.setMasterId(request.getId());

                // Add the position and battery information to the list of the message before forwarding to next drone
                request = Master.newBuilder(request)
                        .addPositionList(Position.newBuilder()
                                .setId(this.network.getMyInfo().getId())
                                .setPosition(this.network.getMyInfo().getPosition())
                                .setBattery(this.network.getMyInfo().getBattery()))
                        .build();
            }
        }
        // Else if the elected flag in the message is false, this message is for a new drone that waits for master's
        // ID information
        else if (request.getNewDrone().getId() == this.network.getMyInfo().getId())
        {
            System.out.println("[MASTER] received master id, storing and destroying message.");

            // Store the current master ID and consume this message. If the master is already known (maybe due to
            // elected message, just destroy the message and avoid to store potential old master's ID)
            if (this.network.getMasterId() == -1)
                this.network.setMasterId(request.getId());
            return;
        }
        // Else if this message is not about election and the current drone already knows the master, this message
        // must contains the information about a new drone that joined the network
        else
        {
            System.out.println("[MASTER] master already known. a new drone must be joined.");

            // Get the Hello message wrapped in the Master message and add the new drone information in local drone list
            Hello message = request.getNewDrone();

            System.out.println("[MASTER] new drone detected, adding to local list.");
            Drone d = new Drone(message.getId(), message.getIpAddress(), message.getListenPort(), message.getPosition());
            this.network.addDroneToList(d);

            // Check if the new drone must be the current drone's next
            this.network.updateConnection();
        }

        // Due to RPC calls to the next drone in a RPC request handling call, the context should be forked and
        // switched before making the request
        Context newContext = Context.current().fork();
        Context origContext = newContext.attach();
        try {
            // Forward the message to next drone
            this.network.masterMessage(request);

        } finally {
            // Back to original context
            newContext.detach(origContext);
        }
    }

    // Handle the Election requests. Received when an election has been started due to current master's absence
    // detected by some drone. It is based on the single-election Chang and Roberts algorithm: any drones that
    // receives an Election message check if the candidate in the message has worst status than itself. If true,
    // change the candidate with its ID and its battery status. This continues until a drone receives again the
    // message with its ID, meaning it is the best candidate in the network and that it wins the election. Then
    // the election winner must send a Master message with the elected flag set to true in order to warn the
    // other drones about its election and to collect the current position of all the other drones, mandatory
    // to begin the master's tasks. Once elected drone receives all the positions he update its local drones list
    // and begin its master's tasks. The base version of Chang and Roberts algorithm can be implemented instead
    // of the complete algorithm because there is only one way to start an election: the drone before the master
    // detects the absence. Another critical situation may be when the current best candidate quits before its
    // election, but in this case the previous drone when handles the sending error sees that it was the
    // candidate, and then restart the election by creating a new election message with its information. No other
    // cases of election starting may happens, meaning every time in every situation only a single election
    // message exists in the ring network.
    @Override
    public void election(Election request, StreamObserver<Response> responseObserver) {
        System.out.println("[ELECTION] election message received. sending ack...");

        // Call the onNext and onCompleted methods to warn the previous drone the message was correctly received
        // and let it ends the request procedure. No values are sent as response in onNext call because there is
        // no data exchange toward the previous drone.
        responseObserver.onNext(null);
        responseObserver.onCompleted();

        // Update the last message timestamp for next drone connection status check
        this.network.setLastMessage(System.currentTimeMillis());

        // If the current drone is the master, an election is useless, so the message will be destroyed
        if (this.network.isMaster())
        {
            System.out.println("[ELECTION] i am master. no need for an election, destroying message.");
            return;
        }

        // If the election message returned to current drone without other changes, it wins the election and
        // became the new master. Before starting the master's tasks it must update the position of all the other
        // drones
        if (this.network.getMyInfo().getId() == request.getId())
        {
            System.out.println("[ELECTION] i won the election, sending elected message.");

            // Due to RPC calls to the next drone in a RPC request handling call, the context should be forked and
            // changed before making the request
            Context newContext = Context.current().fork();
            Context origContext = newContext.attach();
            try{
                // Send to the next drone a Master message with new master ID and elected flag set to true
                this.network.masterMessage(Master.newBuilder().setId(this.network.getMyInfo().getId()).setElected(true).build());
            } finally {
                // Back to original context
                newContext.detach(origContext);
            }
            return;
        }
        // Else check the message and evaluate if the candidate is still active or the current drone is a better
        // candidate
        else
        {
            // If the current drone receives an Election message for the first time, it have to remove the old
            // master from its local drones list and set as master ID the default value
            if (this.network.getMasterId() != -1)
            {
                // Remove the old master from the local drone list and delete its ID as the master one's
                for (Drone d : this.network.getDroneList())
                {
                    if (d.getId() == this.network.getMasterId())
                    {
                        this.network.removeDroneToList(d);
                        this.network.setMasterId(-1);
                        break;
                    }
                }
            }

            // Check if the current drone may be a better candidate for master role. The result is stored in a
            // variable in order to distinguish the two cases below and console log the correct context, without
            // recomputing the method
            boolean betterCandidate = checkCandidate(request);

            // If the current drone is a better candidate or the current candidate is no more connected to the
            // network, destroy the message and create a new one with current drone information
            if (betterCandidate || isCandidateDisconnected(request.getId()))
            {
                // Console log the context
                if (betterCandidate)
                    System.out.println("[ELECTION] i am a better candidate for master election!");
                else
                    System.out.println("[ELECTION] current candidate is no more connected. Must begin new election.");

                request = Election.newBuilder()
                        .setBattery(this.network.getMyInfo().getBattery())
                        .setId(this.network.getMyInfo().getId())
                        .build();
            }
        }

        // Due to RPC calls to the next drone in a RPC request handling call, the context should be forked and
        // changed before making the request
        Context newContext = Context.current().fork();
        Context origContext = newContext.attach();
        try {
            // In the end send the message to the next drone
            this.network.electionMessage(request);
        } finally {
            // Back to the original context
            newContext.detach(origContext);
        }
    }

    // Check if the current candidate is still connected to the network (not in local drone list, meaning the
    // drone received a Missing message about it)
    private boolean isCandidateDisconnected(final long candidate_id) {
        for (Drone d : this.network.getDroneList())
        {
            if (d.getId() == candidate_id)
                return false;
        }
        return true;
    }

    // Check if the current drone is a better candidate than the one in the given Election message
    private boolean checkCandidate(final Election request)
    {
        // Retrieve the current drone battery. If it is delivering considers the status it will be at the end,
        // else just use the current one
        int myBattery;
        if (this.network.getMyInfo().isDelivering())
            myBattery = this.network.getMyInfo().getBattery() - 10;
        else
            myBattery = this.network.getMyInfo().getBattery();

        // Check if the drone is still able to delivery
        if (myBattery > 15) {
            // Evaluate the election conditions. The best candidate must have:
            // - The highest battery
            // - In case of equality, the higher ID
            return myBattery > request.getBattery() || (myBattery == request.getBattery() && this.network.getMyInfo().getId() > request.getId());
        }
        // If the current drone have a low battery level, consider it as a worst candidate due to its imminent
        // disconnection
        return false;
    }

    // Handle the Statistic requests. This message is generated by a drone that completes a delivery, and should
    // send its local statistics to the drone master, which is the only drone in the network that can consumes
    // these messages. When the master is down and the message returns to the sender, store it in a local buffer
    // and retry sending on the next completed delivery. When the master consumes a
    @Override
    public void statistics(Statistic request, StreamObserver<Response> responseObserver) {
        System.out.println("[STATISTICS] statistic message received. sending ack...");

        // Call the onNext and onCompleted methods to warn the previous drone the message was correctly received
        // and let it ends the request procedure. No values are sent as response in onNext call because there is
        // no data exchange toward the previous drone.
        responseObserver.onNext(null);
        responseObserver.onCompleted();

        // Update the last message timestamp for next drone connection status check
        this.network.setLastMessage(System.currentTimeMillis());

        // If this drone is the master, consume the message
        if (this.network.isMaster())
        {
            System.out.println("[STATISTICS] i'm master. received statistic message from " + request.getSenderId());

            // Update the sender drone status, to set it as ready to deliver and update its position and battery
            for (Drone d : this.network.getDroneList())
            {
                if (d.getId() == request.getSenderId())
                {
                    d.setDelivering(false);

                    // Remove the delivery from the assignment list
                    this.network.getDeliveryAssignment().remove(d.getId());

                    d.setPosition(request.getPosition().getPosition());
                    d.setBattery(request.getPosition().getBattery());
                    break;
                }
            }

            // Add the received stats to the local list
            this.network.stats.addDroneStat(request);

            // If there are pending deliveries, retry to assign them now that there is a drone ready
            if (!this.network.getDeliveryQueue().isEmpty())
            {
                Context newContext = Context.current().fork();
                Context origContext = newContext.attach();
                try
                {
                    this.network.assignDelivery(this.network.getDeliveryQueue().poll());
                }
                finally {
                    // Back to the original context
                    newContext.detach(origContext);
                }
            }
            // The master drone is shutting down, and is waiting until the pending deliveries are not totally
            // assigned. So, if there are not more, notify the main thread to complete the shutdown procedure
            else if (!DroneMain.alive)
                this.network.notifyAll();
        }
        // If the message returns to the sender, the master is currently unavailable so store the message in the
        // local buffer until it surely can receive the message
        else if (this.network.getMyInfo().getId() == request.getSenderId() && this.network.getMasterId() == -1)
        {
            System.out.println("[STATISTICS] message returned to me. storing statistics until new master election...");
            this.network.addStatisticToBuffer(request);
        }
        // Else just forward the message to the next drone
        else
        {
            System.out.println("[STATISTICS] i'm not master. forwarding to next drone...");

            // Due to RPC calls to the next drone in a RPC request handling call, the context should be forked and
            // changed before making the request
            Context newContext = Context.current().fork();
            Context origContext = newContext.attach();
            try
            {
                this.network.statisticMessage(request);
            }
            finally {
                // Back to the original context
                newContext.detach(origContext);
            }
        }
    }

    // Handle the Delivery requests. This message is created by the master drone and consumed by the assigned
    // drone for this delivery. When the assigned drone quit from the network, the drone which realizes its
    // absence can destroys the delivery message because the master keeps trace of all the assignment, so once
    // the master receives the Missing message, it puts the orphan delivery to the pending list.
    @Override
    public void delivery(Delivery request, StreamObserver<Response> responseObserver) {
        System.out.println("[DELIVERY] delivery message received. sending ack...");

        // Call the onNext and onCompleted methods to warn the previous drone the message was correctly received
        // and let it ends the request procedure. No values are sent as response in onNext call because there is
        // no data exchange toward the previous drone.
        responseObserver.onNext(null);
        responseObserver.onCompleted();

        // Update the last message timestamp for next drone connection status check. If the current drone is the
        // master, skip this operation
        if (!this.network.isMaster())
            this.network.setLastMessage(System.currentTimeMillis());

        // Due to RPC calls to the next drone in a RPC request handling call, the context should be forked and
        // changed before making the request
        Context newContext = Context.current().fork();
        Context origContext = newContext.attach();
        try {
            // If the assigned drone is not the current one, just forward the message
            if (request.getId() != this.network.getMyInfo().getId())
            {
                System.out.println("[DELIVERY] delivery not for me. forwarding to next drone...");
                this.network.deliveryMessage(request);
            }
            // Else do the received delivery
            else
                this.network.doDelivery(request);
        }
        finally {
            // Back to the original context
            newContext.detach(origContext);
        }
    }

    // Handle the Missing requests. These messages warns the drones in the network about the absence of another
    // one. They are simply forwarded by all the drones, and when return to the sender, it destroys the message.
    // This let all the currently active drones to know about the missing drone, so they can update their local
    // drone list. When the master drone receives a Missing message, it also checks if the missing drone had some
    // delivery assigned and eventually reassign it to some other drone.
    @Override
    public void missing(Missing request, StreamObserver<Response> responseObserver) {
        System.out.println("[MISSING] missing message received. sending ack...");

        // Call the onNext and onCompleted methods to warn the previous drone the message was correctly received
        // and let it ends the request procedure. No values are sent as response in onNext call because there is
        // no data exchange toward the previous drone.
        responseObserver.onNext(null);
        responseObserver.onCompleted();

        // Update the last message timestamp for next drone connection status check
        this.network.setLastMessage(System.currentTimeMillis());

        // If the message returned to the sender, destroy it
        if (request.getSenderId() == this.network.getMyInfo().getId())
            System.out.println("[MISSING] missing message returned to me. destroying...");
        // Else delete the missing drone information and forward the message
        else
        {
            System.out.println("[MISSING] missing drone #" + request.getMissingId() + ". removing from local list.");

            for (Drone d : this.network.getDroneList())
            {
                if (d.getId() == request.getMissingId())
                {
                    // Remove the drone from the local drone list
                    this.network.removeDroneToList(d);

                    // If the current drone is the master, reassign the eventual delivery it had assigned
                    if (this.network.isMaster() && this.network.getDeliveryAssignment().containsKey(request.getMissingId()))
                        this.network.getDeliveryQueue().add(this.network.getDeliveryAssignment().remove(request.getMissingId()));
                    break;
                }
            }
            System.out.println("[DELIVERY] forwarding missing message.");

            // Due to RPC calls to the next drone in a RPC request handling call, the context should be forked and
            // changed before making the request
            Context newContext = Context.current().fork();
            Context origContext = newContext.attach();
            try
            {
                this.network.missingMessage(request);
            }
            finally {
                // Back to the original context
                newContext.detach(origContext);
            }
        }
    }
}
