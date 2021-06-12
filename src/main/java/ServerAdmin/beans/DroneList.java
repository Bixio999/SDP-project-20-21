package ServerAdmin.beans;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * DroneList class models the handler for drone list structure in server admin.
 *
 * It is implemented as a singleton in order to get structure and methods without instantiate a DroneList
 * object but just with the static method getInstance.
 */
@XmlRootElement
@XmlAccessorType
public class DroneList {

    // The drone list attribute
    private final List<Drone> droneList;

    // Singleton attribute
    private static DroneList instance;

    // Default constructor that initialize the droneList
    private DroneList()
    {
        droneList = new ArrayList<>();
    }

    // Static method to obtain the singleton instance
    public synchronized static DroneList getInstance()
    {
        if (instance == null)
            instance = new DroneList();
        return instance;
    }

    // Get the current drone list. Synchronized method because of concurrency. Structure inconsistency
    // avoided thanks to synchronization and list copy object returned.
    public synchronized List<Drone> getDroneList()
    {
        return new ArrayList<>(droneList);
    }

    // Add a drone to the structure. Synchronized to avoid inconsistency between list accesses from
    // different threads of REST server (every request received is handled by a new thread, in order
    // to maintain the server active to new requests)
    public synchronized void addDrone(Drone d)
    {
        this.droneList.add(d);
    }

    // Delete a drone in structure. Called whenever a drone quits the network. Synchronized to avoid
    // inconsistency.
    public synchronized Drone deleteDroneById(long id)
    {
        // Find the drone in the list and remove it
        for (Drone drone : droneList) {
            if (drone.getId() == id)
            {
                droneList.remove(drone);
                // Return the deleted drone to offer the possibility to save its information.
                return drone;
            }
        }
        // If the drone wasn't found, it return null value
        return null;
    }

    // Check if a drone with the given ID already exists in drone structure. Used during drone sign-in
    // in the network to check if its ID is valid or not.
    public boolean checkId(long id)
    {
        List<Drone> list = this.getDroneList();
        
        for (Drone drone : list) {
            if (drone.getId() == id)
                return false;
        }
        return true;
    }
}
