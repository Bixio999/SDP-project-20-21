package Drone.sensors;

public class Measurement implements Comparable<Measurement> {

    private String id;
    private String type;
    private double value;
    private long timestamp;

    public Measurement(String id, String type, double value, long timestamp) {
        this.value = value;
        this.timestamp = timestamp;
        this.id=id;
        this.type=type;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public void setId(String type) {
        this.id = type;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public int compareTo(Measurement m) {
        Long thisTimestamp = timestamp;
        Long otherTimestamp = m.getTimestamp();
        return thisTimestamp.compareTo(otherTimestamp);
    }

    public String toString(){
        return value + " " + timestamp;
    }
}
