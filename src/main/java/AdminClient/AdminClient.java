package AdminClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * AdminClient class that models the client application for Dronazon admins. It shows a simple console menu
 * that offers four different operations that gives informations and statistic from the drone network in the
 * Smart City.
 *
 * This class just models the interface of the application, the backend operations are performed by the
 * AdminHandler class, which effects the actual request to the server.
 *
 * This class is composed just by main method and printMenu method. The second one print to console the menu interface.
 */
public class AdminClient {

    public static void main(String[] args) {
        // Create the reader object for user input.
        BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("Welcome to Admin client for Server Admin queries.\n");

        // Create the handler object that execute the operations.
        AdminHandler handler = new AdminHandler();

        // Store the user input
        String input = null;

        // Infinite loop that continuously shows the menu until exit option is chosen.
        while(true)
        {
            // Print the menu to console
            printMenu();

            System.out.print("\n\tSelect operation: ");
            try {
                // Read the input of the user
                input = inFromUser.readLine();
            } catch (IOException e) {
                System.out.println("Error during input read...");
                e.printStackTrace();
            }

            // Check if no input is obtained from console to avoid null pointer exception
            if (input == null)
                continue;

            // Detect the user choice
            switch (input) {
                // Get drones network status option
                case "1":
                    handler.getDroneList();
                    break;
                // Get last n global stats option
                case "2":
                    handler.getStats(inFromUser);
                    break;
                // Get completed deliveries average option
                case "3":
                    handler.getDeliveryAvg(inFromUser);
                    break;
                // Get distance covered average option
                case "4":
                    handler.getDistanceAvg(inFromUser);
                    break;
                // Quit from application
                case "5":
                    return;
                default:
            }
        }
    }

    // Print the menu in the console
    public static void printMenu()
    {
        System.out.println("\t1) Get current Drones network status.");
        System.out.println("\t2) Get the last n global stats about Smart City.");
        System.out.println("\t3) Get the average of completed deliveries in a given range of time.");
        System.out.println("\t4) Get the average of distance covered by drones in a given range of time.");
        System.out.println("\t5) Exit.");
    }
}
