package ServerAdmin.services;

import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.ResourceBundle;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import ServerAdmin.beans.Drone;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;

import ServerAdmin.beans.DroneList;
import ServerAdmin.beans.GlobalStats;
import ServerAdmin.beans.Stat;

/**
 * StatsService class implements the REST Server service for admin client functionalities.
 *
 * It sets the URI path to "/admin/".
 *
 * The uses of Gson to serialize items happens because of JAXB issues on lists marshalling. To simplify
 * the operations, it was preferred to use Gson serialization and send the items as strings.
 */
@Path("admin")
public class StatsService {

    // Gson object used for serializing objects into JSON strings as request's response.
    private final Gson gson = new Gson();

    // Get the list of the drones currently signed in in the smart city. If it is empty, answer with
    // a NO CONTENT status, else serialize the list with Gson and create the response object.
    @GET
    @Path("drones")
    public Response getDroneList()
    {
        List<Drone> droneList = DroneList.getInstance().getDroneList();
        if (droneList.size() > 0)
            return Response.ok(gson.toJson(DroneList.getInstance().getDroneList())).build();
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    // Get the last n master drone stats stored. Uses the getStats method from GlobalStats class.
    // If there are not stats, answer with a NO CONTENT status. If the received value (as a path parameter)
    // is invalid, answer with a NOT ACCEPTABLE status. Else return the list as JSON entity.
    @GET
    @Path("stats/{n}")
    public Response getStats(@PathParam("n") int n)
    {
        if (n > 0){
            List<Stat> res = GlobalStats.getInstance().getStats(n);
            if (res.size() > 0)
                return Response.ok(gson.toJson(GlobalStats.getInstance().getStats(n))).build();
            return Response.status(Response.Status.NO_CONTENT).build();
        }
        return Response.status(Response.Status.NOT_ACCEPTABLE).build();
    }

    // Get the average of deliveries completed by drones in a given range of time. The two timestamps
    // used for time range are obtained as path parameters, meaning this is a GET request. If the given
    // timestamps are invalid answer with a BAD REQUEST status. If there are no stats in the given time
    // range, answer with a NO CONTENT status. Else, return the average value as JSON string. To obtain
    // the list, uses the getStatsFromTo method from GlobalStats class, and then calculate the average
    // based on the stats in the received list.
    @GET
    @Path("delivery/{t1}/{t2}")
    public Response getDeliveryStats(@PathParam("t1") String t1, @PathParam("t2") String t2)
    {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        Timestamp time1, time2;
        try {
            time1 = new Timestamp(dateFormat.parse(t1).getTime());
            time2 = new Timestamp(dateFormat.parse(t2).getTime());
        } catch (ParseException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        List<Stat> list = GlobalStats.getInstance().getStatsFromTo(time1, time2);
        if (list == null || list.size() == 0)
            return Response.status(Response.Status.NO_CONTENT).build();

        double avg = 0;
        for (Stat stat : list) {
            avg += stat.getDeliveryAvg();
        }
        avg = avg / list.size();
        return Response.ok(gson.toJson(avg)).build();
    }

    // Get the average of the distance covered by the drones during deliveries, in a given range of time.
    // The two timestamps are obtained as path parameter, meaning this is a GET request. If the received
    // timestamps are not valid, answer with a BAD REQUEST status. The stats created in the considered
    // time range are obtained with the getStatsFromTo method from GlobalStats class. If the list is empty
    // there are no stats with a timestamp in the given range, then answer with a NO CONTENT status. Else
    // calculate the average and send it as JSON string.
    @GET
    @Path("distance/{t1}/{t2}")
    public Response getDistanceStats(@PathParam("t1") String t1, @PathParam("t2") String t2)
    {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        Timestamp time1, time2;
        try {
            time1 = new Timestamp(dateFormat.parse(t1).getTime());
            time2 = new Timestamp(dateFormat.parse(t2).getTime());
        } catch (ParseException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        List<Stat> list = GlobalStats.getInstance().getStatsFromTo(time1, time2);
        if (list == null || list.size() == 0)
            return Response.status(Response.Status.NO_CONTENT).build();

        double avg = 0;
        for (Stat stat : list) {
            avg += stat.getDistanceAvg();
        }
        avg = avg / list.size();
        return Response.ok(gson.toJson(avg)).build();
    }
}
