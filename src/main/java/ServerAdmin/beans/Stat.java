package ServerAdmin.beans;

import org.codehaus.jackson.annotate.JsonIgnore;

import java.sql.Time;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

/**
 * Stat class that models a global statistics received from the drone network.
 *
 * It implements Comparable in order to get sorted by timestamp.
 *
 * Class marked as serializable for JAXB.
 */
@XmlRootElement
public class Stat implements Comparable<Stat> {

    // Average of the delivery completed by the drones
    private double deliveryAvg;
    // Average of the distance covered by the drones
    private double distanceAvg;
    // Average of the pollution values acquired by drones
    private double pollutionAvg;
    // Average of the battery level of the drones
    private double batteryAvg;
    // Timestamp of the statistic, acquired when computed by drone master
    private Date timestamp;

    // Empty constructor for JAXB unmarshalling
    public Stat()
    {

    }

    // Constructor used by classes, to create a new Stat object
    public Stat(double deliveryAvg, double distanceAvg, double pollutionAvg, double batteryAvg, Date timestamp) {
        this.deliveryAvg = deliveryAvg;
        this.distanceAvg = distanceAvg;
        this.pollutionAvg = pollutionAvg;
        this.batteryAvg = batteryAvg;
        this.timestamp = timestamp;
    }

    // Getter and setter

    public double getDeliveryAvg() {
        return deliveryAvg;
    }

    public void setDeliveryAvg(double deliveryAvg) {
        this.deliveryAvg = deliveryAvg;
    }

    public double getDistanceAvg() {
        return distanceAvg;
    }

    public void setDistanceAvg(double distanceAvg) {
        this.distanceAvg = distanceAvg;
    }

    public double getPollutionAvg() {
        return pollutionAvg;
    }

    public void setPollutionAvg(double pollutionAvg) {
        this.pollutionAvg = pollutionAvg;
    }

    public double getBatteryAvg() {
        return batteryAvg;
    }

    public void setBatteryAvg(double batteryAvg) {
        this.batteryAvg = batteryAvg;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    // Comparable method 
    @Override
    public int compareTo(Stat o) {
        return this.timestamp.compareTo(o.getTimestamp());
    }

    @Override
    public String toString() {
        return "Stat{" +
                "deliveryAvg=" + deliveryAvg +
                ", distanceAvg=" + distanceAvg +
                ", pollutionAvg=" + pollutionAvg +
                ", batteryAvg=" + batteryAvg +
                ", timestamp=" + timestamp +
                '}';
    }
}
