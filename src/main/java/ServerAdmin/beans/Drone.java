package ServerAdmin.beans;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Drone class that models the drone information.
 *
 * It also is a class marked for JAXB marshalling/unmarshalling.
 *
 * The delivering flag, the battery status and the position are defined as volatile because read and write
 * by various threads, and their values may change the software behaviour, so they need to be synchronized among them.
 */
@XmlRootElement
public class Drone {

    // Unique ID
    private long id;

    // Used  IP address
    private String ipAddress;

    // Port of the RPC server
    private int listenPort;

    // Battery level, by default set to 100%
    private volatile int battery = 100;

    // Flag for delivering status. Set true whenever the drone is delivering an order
    private volatile boolean delivering = false;

    // Position of the drone in the smart city. Received from Server Admin
    private volatile String position;

    // Empty constructor for JAXB unmarshalling
    public Drone()
    {
    }

    // Constructor used by classes to instantiate a new drone object
    public Drone(long id, String ipAddress, int listenPort, String position)
    {
        this.id = id;
        this.ipAddress = ipAddress;
        this.listenPort = listenPort;
        this.position = position;
    }

    /*   GETTER AND SETTER   */

    // The getter and setter for the attributes that get read and write by multiple threads are defined as
    // synchronized, in order to avoid inconsistency on their values

    public synchronized int getBattery() {
        return battery;
    }

    public synchronized void setBattery(int battery) {
        this.battery = battery;
    }

    public long getId() {
        return id;
    }

    public synchronized String getPosition() {
        return position;
    }

    public synchronized void setPosition(String position) {
        this.position = position;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    // Method to print drone information
    @Override
    public String toString() {
        return "Drone{" +
                "id=" + id +
                ", ipAddress='" + ipAddress + '\'' +
                ", listenPort=" + listenPort +
                ", delivering=" + delivering +
                ", position='" + position + '\'' +
                '}';
    }

    // Getter and setter of delivering flag. Synchronized methods because of possible concurrency, caused by multiple
    // threads that may access to this information
    public synchronized boolean isDelivering() {
        return delivering;
    }

    public synchronized void setDelivering(boolean delivering) {
        this.delivering = delivering;
    }
}
