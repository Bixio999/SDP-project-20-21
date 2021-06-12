package ServerAdmin;

import java.io.IOException;

import com.sun.jersey.api.container.httpserver.HttpServerFactory;
import com.sun.net.httpserver.*;

/**
 * ServerAdmin class models the main class of the Server Administrator. It creates and start the REST
 * server to the specified IP address (which in this case is "http://localhost:1337"). Once the server
 * started, wait until read an user input to shut down the server.
 */
public class ServerAdmin {

    // IP address of the REST server
    private static final String host = "localhost";
    // The port the server should listen to
    private static final int port = 1337;

    public static void main(String[] args) throws IllegalArgumentException, IOException {
        // Create the server to the specified IP address and port
        HttpServer server = HttpServerFactory.create("http://" + host + ":" + port + "/");
        // Then start it
        server.start();

        // Console log the successful server start
        System.out.println("Server running!");
        System.out.println("Server started on: http://"+host+":"+port);

        // Wait an user input to shut down the server
        System.out.println("Hit return to stop...");
        System.in.read();

        // If an input was read, then shut down the server and log to the console
        System.out.println("Stopping server");
        server.stop(0);
        System.out.println("Server stopped");
    }
}
