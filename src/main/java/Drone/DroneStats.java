package Drone;

import ServerAdmin.beans.Drone;
import ServerAdmin.beans.Stat;
import droneNetwork.proto.DroneNetworkServiceOuterClass.Statistic;

import java.util.List;
import java.util.Map;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.Date;

/**
 * DroneStats class that models the drone local statistics module.
 *
 * It is an independent thread which console log the current stats every 10 seconds. If the drone is
 * the master, generate the global stats of the network from the local list of statistics received by other
 * drones, and send it to the server admin.
 *
 * Call the drone method to verify if drone connection to the next one is still active. Otherwise handle the
 * missing drone event.
 *
 * The attributes deliveryCompleted, distanceCoverage and active are defined as volatile because their values
 * is assigned by another thread and this one reads them. So they need to be synchronized.
 */
public class DroneStats extends Thread{

    // List of Statistic objects the master must store to compute the global stats for server admin
    private List<Statistic> globalStats;

    // Map used by drone master to store the number of deliveries completed by the drones in the network,
    // included itself, in order to compute the average for the global stats
    private Map<Long, Long> networkDeliveriesCompleted;

    // Map used by drone master to store the distance coverage for each drones in the network, included itself,
    // in order to compute the average for the global stats
    private Map<Long, Double> networkDistanceCoverage;

    // Reference to drone object
    private final DroneClient drone;

    // Counter for deliveries completed by the drone
    private volatile long deliveriesCompleted;
    // Total distance coverage by drone
    private volatile double distanceCoverage;

    // Flag to stop the thread if the drone shuts down
    private volatile boolean active;

    // Default constructor
    public DroneStats(DroneClient drone)
    {
        this.active = true;
        this.deliveriesCompleted = 0;
        this.distanceCoverage = 0;
        this.drone = drone;

        if (drone.isMaster())
        {
            this.globalStats = new LinkedList<>();
            this.networkDeliveriesCompleted = new HashMap<>();
            this.networkDistanceCoverage = new HashMap<>();
        }
    }

    // Add a new Statistic to the list. Synchronized method to avoid adding data meanwhile global stats generation
    public synchronized void addDroneStat(Statistic stat)
    {
        this.globalStats.add(stat);
    }

    // Initialize the global stats list if the drone is elected as new master
    public void initMaster()
    {
        // Check if the structure wasn't already created
        if (drone.isMaster() && this.globalStats == null)
        {
            this.globalStats = new LinkedList<>();
            this.networkDeliveriesCompleted = new HashMap<>();
            this.networkDistanceCoverage = new HashMap<>();
        }
    }

    // Increase the counter for completed deliveries
    public synchronized void increaseDeliveryCount()
    {
        this.deliveriesCompleted++;
    }

    // Get the number of completed deliveries
    public synchronized long getDeliveriesCompleted() {
        return deliveriesCompleted;
    }

    // Add the given distance value to the local total distance coverage.
    public synchronized void addDistanceCoverage(double distance)
    {
        this.distanceCoverage += distance;
    }

    // Check if global stats list is empty. Used by master when disconnecting to wait or not for the last cycle
    public synchronized boolean isGlobalStatsEmpty()
    {
        return this.globalStats.isEmpty();
    }

    // Thread run method. Cycle until active flag is set to true.
    public void run()
    {
        while(active)
        {
            // Sleep for 10 seconds
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                // If the drone is shutting down, the sleep is interrupted and the exception thrown
                System.out.println("\tInterrupt received, stopping stats service.");
                return;
            }

            // Print to console the drone local statistics
            System.out.println("- SHOWING LOCAL DRONE STATS -");
            System.out.println("\tDeliveries completed: " + this.deliveriesCompleted);
            System.out.println("\tCurrent distance coverage: " + this.distanceCoverage);
            System.out.println("\tBattery status: " + this.drone.getMyInfo().getBattery());

            // Check if the next drone connection is still active
            this.drone.checkConnectionTimeout();

            // If the drone is the master, generate the global stats
            if (this.drone.isMaster())
            {
                // If the stats structure is null, meaning drone was just elected, create the structure and skip
                // to next cycle
                if (this.globalStats == null)
                {
                    this.globalStats = new LinkedList<>();
                    this.networkDeliveriesCompleted = new HashMap<>();
                    this.networkDistanceCoverage = new HashMap<>();
                    continue;
                }

                // Check if the list is empty, and skip if true
                synchronized (this)
                {
                    if (this.globalStats.isEmpty())
                        continue;
                }

                // Copy the current stats list and clean the structure
                List<Statistic> statsCollected;
                synchronized (this)
                {
                    statsCollected = new LinkedList<>(this.globalStats);
                    this.globalStats.clear();
                }

                long deliveries = 0;
                double distances = 0;
                double pollution = 0;
                int pollutionCount = 0;
                int batteries = 0;

                // Calculate the information sum
                for (Statistic s : statsCollected)
                {
                    // Update the distance coverage of the drones
                    this.networkDistanceCoverage.put(s.getSenderId(), s.getDistance());

                    // Update the deliveries completed by the drones
                    this.networkDeliveriesCompleted.put(s.getSenderId(), s.getDeliveriesCompleted());

                    for (double value : s.getAvgPollutionList())
                        pollution += value;
                    pollutionCount += s.getAvgPollutionList().size();
                }

                // Get a copy of the drones list, in order to calculate the sum of their battery
                // level
                List<Drone> droneList = this.drone.getDroneList();
                for (Drone d : droneList)
                    batteries += d.getBattery();

                // Calculate the sum for the deliveries completed by the drones
                for (Long del : this.networkDeliveriesCompleted.values())
                    deliveries += del;

                // Calculate the sum for drones distance coverage
                for (Double dist : this.networkDistanceCoverage.values())
                    distances += dist;

                // Calculate the averages
                deliveries /= droneList.size();
                distances /= droneList.size();
                pollution /= pollutionCount;
                batteries /= droneList.size();

                // Instantiate the global stat object
                Stat globalStat = new Stat(deliveries, distances, pollution, batteries, new Date(System.currentTimeMillis()));

                // Send global stats to server admin
                this.drone.sendGlobalStats(globalStat);
            }
        }
        // If the service was stopped, master drone is shutting down and main thread is waiting
        if (this.drone.isMaster())
        {
            synchronized (this.drone)
            {
                this.drone.notifyAll();
            }
        }
    }

    // Set the active flag to false in order to stop the thread
    public void stopMeGently()
    {
        this.active = false;
    }
}
