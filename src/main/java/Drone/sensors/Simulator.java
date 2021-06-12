package Drone.sensors;

import java.util.Calendar;
import java.util.Random;

public abstract class Simulator extends Thread {

    protected volatile boolean stopCondition = false;
    protected Random rnd = new Random();
    private Buffer buffer;
    private String id;
    private String type;

    public Simulator(String id, String type, Buffer buffer){
        this.id = id;
        this.type = type;
        this.buffer = buffer;
    }

    public void stopMeGently() {
        stopCondition = true;
    }

    protected void addMeasurement(double measurement){
        buffer.addMeasurement(new Measurement(id, type, measurement, currentTime()));
    }

    public Buffer getBuffer(){
        return buffer;
    }

    protected void sensorSleep(long milliseconds){
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public abstract void run();

    private long currentTime(){
        return System.currentTimeMillis();
    }

    public String getIdentifier(){
        return id;
    }

}

