package ServerAdmin.services;

import java.util.Random;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import ServerAdmin.beans.Drone;
import ServerAdmin.beans.DroneList;
import ServerAdmin.beans.GlobalStats;
import ServerAdmin.beans.Stat;
import com.google.gson.Gson;

/**
 * DroneService class implements the REST Server service for drones functionalities.
 *
 * It sets the URI path to "/drones/".
 */
@Path("drones")
public class DroneService {

    // Sign in the network the received drone. If its ID is valid, add it to the local drone list and send its
    // position in the smart city. Otherwise answer with a NON ACCEPTABLE response. Being a POST request, the
    // drone information are received as JSON entity (unmarshalled by JAXB).
    @POST
    @Path("add")
    @Consumes({"application/json", "application/xml"})
    public Response addDrone(Drone d)
    {
        // Check if the received drone ID already belongs to a drone in the network
        if (DroneList.getInstance().checkId(d.getId()))
        {
            // If the ID is valid, generate the drone's position and response with the updated Drone object
            Gson gson = new Gson();
            Random rand = new Random();
            d.setPosition(rand.nextInt(10) + ":" + rand.nextInt(10));
            DroneList.getInstance().addDrone(d);
            return Response.ok(gson.toJson(DroneList.getInstance().getDroneList())).build();
        }
        // Else return a NON ACCEPTABLE response
        else
            return Response.status(Response.Status.NOT_ACCEPTABLE).build();
    }

    // Sign out a drone from the network. The ID of the exiting drone is obtained as path parameter.
    // If the received ID exists in the local list, remove it and response with an OK status.
    // Otherwise response wit a NOT ACCEPTABLE status.
    @GET
    @Path("remove/{id}")
    public Response removeDrone(@PathParam("id") long id)
    {
        Drone deletedDrone = DroneList.getInstance().deleteDroneById(id);
        if (deletedDrone != null)
            return Response.ok().build();
        return Response.status(Response.Status.NOT_ACCEPTABLE).build();
    }

    // Store the received global statistics from the current drone master. The stats are obtained by
    // unmarshalling of the received JSON entity (by POST request).
    @POST
    @Path("stats")
    @Consumes({"application/json", "application/xml"})
    public Response sendStats(Stat s)
    {
        GlobalStats.getInstance().add(s);
        return Response.ok().build();
    }

}
