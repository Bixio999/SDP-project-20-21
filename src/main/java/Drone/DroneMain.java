package Drone;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * DroneMain class models the starting class for the drone.
 *
 * It has two tasks: start the drone and run the
 * terminal thread. The second one is a thread started from this object, that executes the infinite loop
 * for user input quit command.
 *
 * The class main just instantiate a new DroneMain object and calls the startDrone method.
 *
 * The synchronization between main thread and terminal thread happens on the object lock. The main thread
 * after starting the drone execute the wait before the disconnect method: when the terminal thread receives the
 * quit command or a delivery thread sees the battery is low, call the notify to wake up main thread and begin
 * the shutdown procedure.
 *
 * The general drone status is monitored by the alive flag, which is a volatile static attribute so it can be
 * checked by each thread without a direct reference to DroneMain object. This choice was made because no other
 * DroneMain objects will be created for each JVM process, so even with multiple drone execution the alive variable
 * will have distinct values.
 */
public class DroneMain extends Thread{

    // Flag to monitor the drone status
    protected volatile static boolean alive;

    // Default constructor
    public DroneMain()
    {
        alive = false;
    }

    // Main method for drone execution. It just creates a DroneMain object and calls the startDrone method.
    public static void main(String[] args) throws InterruptedException {
        DroneMain drone = new DroneMain();
        drone.startDrone();
    }

    // Thread run method which handles the drone terminal for the quit command, to manually shutdown the drone.
    public void run()
    {
        // Create the reader from console
        BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));

        // Wait for an input
        String input = null;
        do {
            System.out.print("\tWrite 'quit' to terminate: ");
            try {
                input = inFromUser.readLine();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // If the input reads null or a string that is not the quit command (while drone is alive), cycle
        while ((input == null || !input.equalsIgnoreCase("quit")) && alive);

        // If the quit command is read, set the alive flag to false and notify the main thread
        synchronized (this)
        {
            alive = false;
            this.notifyAll();
        }
    }

    // Start the drone modules and then wait for shutdown signal
    public void startDrone() throws InterruptedException {

        // Create the DroneClient object and pass the self reference to let the drone network threads to notify the
        // main thread when battery is low
        DroneClient me = new DroneClient(this);
        me.setName("DroneClient - network init");
        // Start the DroneClient thread to finish the setup (connection to network)
        me.start();

        // Start the terminal thread
        this.setName("Terminal");
        this.start();

        // Wait until drone must shutdown
        synchronized (this)
        {
            while(alive)
            {
                this.wait();
            }
        }

        // If the main thread was notified, begin the shutdown procedure
         System.out.println("--------NOTIFY RECEIVED. STOPPING DRONE-----------");
        // Stop the terminal thread
        this.interrupt();
        // Run the shutdown method
        me.disconnect();

        // Terminate process execution
        System.exit(0);
    }
}
