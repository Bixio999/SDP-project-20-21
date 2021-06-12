package Drone.sensors;

public class PM10Simulator extends Simulator {


    private final double A = 15;
    private final double W = 0.05;
    private static int ID = 1;

    public PM10Simulator(String id, Buffer buffer){
        super(id, "PM10", buffer);
    }

    //use this constructor to initialize the pm10's simulator in your project
    public PM10Simulator(Buffer buffer){
        this("pm10-"+(ID++), buffer);
    }

    @Override
    public void run() {

        double i = rnd.nextInt();
        long waitingTime;

        while(!stopCondition){

            double pm10 = getPM10Value(i);
            addMeasurement(pm10);

            waitingTime = 200 + (int)(Math.random()*200);
            sensorSleep(waitingTime);

            i+=0.2;

        }

    }

    private double getPM10Value(double t){
        return Math.abs(A * Math.sin(W*t) + rnd.nextGaussian()*0.1)+15;

    }
}
