package AdminClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;

import ServerAdmin.beans.Drone;
import ServerAdmin.beans.Stat;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

/**
 * AdminHandler class that execute the various operations chosen by user in AdminClient application.
 * It asks to the user the eventual extra information to generate the requests and makes the http request to
 * the REST server.
 *
 * To unmarshal the obtained object it uses gson to create the objects from JSON server response.
 */
public class AdminHandler {

    // Gson class to unmarshall objects
    private final Gson gson;
    // HTTP Client to make the HTTP requests
    private final Client client;

    // The URI base to the server, in order to simplify the request code and make it more readable. It refers to
    // admin service path of REST server.
    private final String uriBase = "http://localhost:1337/admin/";

    // Simple constructor that initialize the attributes of the class
    public AdminHandler()
    {
        gson = new Gson();
        client = Client.create();
    }

    // Get the list of current drones in the smart city.
    public void getDroneList()
    {
        // Create the resource that points to the correct URI of the operation
        WebResource resource = client.resource(uriBase + "drones");
        // Get the server response, which must be a JSON string to be accepted. It perform a GET request
        ClientResponse response = resource.accept("application/json").get(ClientResponse.class);

        // Handle the different status codes the server may produces
        switch (response.getStatus())
        {
            // The request was correctly handled by server
            case 200:
                // Get the payload and unmarshall the JSON object
                String output = response.getEntity(String.class);
                List<Drone> droneList = gson.fromJson(output, new TypeToken<List<Drone>>(){}.getType());

                // Print to console the output
                for (Drone d : droneList)
                    System.out.println("\t\t" + d.toString());
                break;
            // No content code. There are not drones in the city at the moment
            case 204:
                System.out.println("\t\tNo drones currently in Smart City.");
                break;
            // Print an error message for any other status code
            default:
                System.out.println("\t\tError: server connection issues.");
                break;
        }

        System.out.println("\n");
    }

    // Get the coverage distance average of all the drones in the network in a given range of time
    public void getDistanceAvg(BufferedReader inFromUser)
    {
        boolean cycle = true;
        String t1 = null, t2 = null;
        // Cycle to repeat the input of the dates if they are not valid
        while(cycle)
        {
            System.out.println("\tInsert the range of time by the format 'yyyy-MM-dd'...");
            try
            {
                // Read the dates from user input
                System.out.print("\t\tFrom: ");
                t1 = inFromUser.readLine();
                System.out.print("\t\tTo: ");
                t2 = inFromUser.readLine();

                // Parser for date format
                DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

                // Create the timestamps. If the inputs have wrong dateformat an exception is thrown
                Timestamp time1 = new Timestamp(dateFormat.parse(t1).getTime());
                Timestamp time2 = new Timestamp(dateFormat.parse(t2).getTime());

                // Check if the dates are valid: if the first one is after the second, or the first date is after current time
                if (time1.after(time2) || time1.after(new Timestamp(System.currentTimeMillis())))
                {
                    System.out.println("\t\tInvalid data insert. Try again.");
                    continue;
                }
                // If the inputs are correct, exit from the cycle
                cycle = false;
            }
            // Catch input errors
            catch(IOException e)
            {
                System.out.println("\t\tError during input phase.");
                return;
            }
            // Error handling if the insert date are in a wrong date format
            catch (ParseException er)
            {
                System.out.println("\t\tInvalid data format insert. Try again.");
            }
        }

        // Create the resource for the request, with the functionality URI
        WebResource resource = client.resource(uriBase + "distance/" + t1 + "/" + t2);
        // Get the response from server, accepted only if a JSON string. It perform a GET request
        ClientResponse response = resource.accept("application/json").get(ClientResponse.class);

        // Check the response status
        switch (response.getStatus())
        {
            // Correct request and valid response
            case 200:
                // Get the average by parsing with gson, and print the output
                double avg = gson.fromJson(response.getEntity(String.class), Double.class);
                System.out.println("\t\tAverage of distance covered by drones in given time range: " + avg);
                break;
            // No data are currently stored in server
            case 204:
                System.out.println("\t\tNo stats stored yet.");
                break;
            // Wrong request, bad timestamps sent
            case 406:
                System.out.println("\t\tError: invalid data sent.");
                break;
            // Warn when there are server connection issues
            default:
                System.out.println("\t\tError: server connection issues.");
        }
        System.out.println("\n");
    }

    // Get the average of the number of deliveries completed by drones in a range of given time
    public void getDeliveryAvg(BufferedReader inFromUser)
    {
        boolean cycle = true;
        String t1 = null, t2 = null;
        // Cycle until the insert data are valid
        while(cycle)
        {
            System.out.println("\tInsert the range of time by the format 'yyyy-MM-dd'...");
            try
            {
                System.out.print("\t\tFrom: ");
                t1 = inFromUser.readLine();
                System.out.print("\t\tTo: ");
                t2 = inFromUser.readLine();

                // Data parser for the format
                DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

                // Create the timestamp objects. If the inputs have wrong dateformat an exception is thrown
                Timestamp time1 = new Timestamp(dateFormat.parse(t1).getTime());
                Timestamp time2 = new Timestamp(dateFormat.parse(t2).getTime());

                // Check if the insert date are valid
                if (time1.after(time2) || time1.after(new Timestamp(System.currentTimeMillis())))
                {
                    System.out.println("\t\tInvalid data insert. Try again.");
                    continue;
                }
                // If the given dates are valid, exit from cycle
                cycle = false;
            }
            // Handle input errors
            catch(IOException e)
            {
                System.out.println("\t\tError during input phase.");
                return;
            }
            // Exception thrown if a wrong date format was insert
            catch (ParseException er)
            {
                System.out.println("\t\tInvalid data format insert. Try again.");
            }
        }

        // Create the resource with the functionality URI
        WebResource resource = client.resource(uriBase + "delivery/" + t1 + "/" + t2);
        // Get the response from server. It accept only if the response is a json string. It perform a GET request
        ClientResponse response = resource.accept("application/json").get(ClientResponse.class);

        // Check the response status
        switch (response.getStatus())
        {
            // The request was correctly done without errors
            case 200:
                // Get the output by parsing from json string with gson, and print it
                double avg = gson.fromJson(response.getEntity(String.class), Double.class);
                System.out.println("\t\tAverage of delivery completed by drones in given time range: " + avg);
                break;
            // No data are currently stored in server
            case 204:
                System.out.println("\t\tNo stats stored yet.");
                break;
            // Wrong request, bad timestamps sent
            case 406:
                System.out.println("\t\tError: invalid data sent.");
                break;
            // Warn when there are server connection issues
            default:
                System.out.println("\t\tError: server connection issues.");
        }
        System.out.println("\n");
    }

    // Get the last n global stats of the drone network
    public void getStats(BufferedReader inFromUser)
    {
        boolean cycle = true;
        int n = 0;
        // Cycle until input is valid
        while(cycle) {
            System.out.print("\tInsert the number of stats to show: ");
            try {
                n = Integer.parseInt(inFromUser.readLine());
                // Negative value is not accepted
                if (n <= 0)
                    throw new IOException();
                // Else, exit from cycle
                cycle = false;
            } catch (IOException e) {
                System.out.println("\t\tMust be a non-negative digit.");
            }
        }

        // Create the resource with the functionality URI
        WebResource resource = client.resource(uriBase + "stats/" + n);
        // Get the response from server, that must be a json string. It perform a GET request
        ClientResponse response = resource.accept("application/json").get(ClientResponse.class);

        // Check the response status
        switch (response.getStatus())
        {
            // Correct response obtained, go to next instructions
            case 200:
                break;
            // No data are currently stored in server
            case 204:
                System.out.println("\tNo stats stored currently.");
                return;
            // Invalid data sent
            case 406:
                System.out.println("\t\tError: not valid data sent.");
                return;
            // Warn if there were connection issues
            default:
                System.out.println("\t\tError: server connection issues.");
                return;
        }

        // Get the stats list from response by parsing the json with gson
        List<Stat> stats = gson.fromJson(response.getEntity(String.class), new TypeToken<List<Stat>>(){}.getType());

        // Print the stats received
        for (Stat s : stats)
            System.out.println("\t\t" + s.toString());

        System.out.println("\n");
    }
}
