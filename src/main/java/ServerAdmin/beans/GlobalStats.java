package ServerAdmin.beans;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * GlobalStats handles the data structure to store the global statistics received from the drone network
 * master.
 *
 * It is implemented as a singleton instance, in order to maintain a single object, which is usable thanks to
 * static method getInstance.
 *
 * The data structure is a priority queue, in order to keep the stats received sorted by timestamp. Thanks to
 * this choice, the admin interrogations to obtain global stats information by temporal range are easier to
 * perform because of time sorted structure.
 */
@XmlRootElement
@XmlAccessorType
public class GlobalStats {

    // Global statistic queue attribute
    private Queue<Stat> stats;

    // Singleton attribute
    private static GlobalStats instance;

    // Default constructor that initialize the data structure
    private GlobalStats()
    {
        stats = new PriorityQueue<>();
    }

    // Singleton static method to obtain the instance of the object if exists. Otherwise if is
    // called the first time, it initialize the object.
    public static synchronized GlobalStats getInstance()
    {
        if (instance == null)
            instance = new GlobalStats();
        return instance;
    }

    // Get all the stats currently stored. Synchronized method because of concurrency avoidance by copying the
    // structure in a new list.
    public synchronized List<Stat> getStats()
    {
        return new ArrayList<>(stats);
    }

    // Add a new statistic to the structure. Synchronized method to maintain consistency between getStats calls.
    public synchronized void add(Stat s)
    {
        stats.add(s);
    }

    // Get the last n stats stored. If n is bigger than current structure size, the whole list is returned.
    public List<Stat> getStats(int n)
    {
        int size = stats.size();
        if (n < size)
            return getStats().subList(size - n, size);
        return getStats();
    }

    // Get the stored stats which were created between given t1 and t2.
    public List<Stat> getStatsFromTo(Timestamp t1, Timestamp t2)
    {
        // Find before the lower index
        int low = -1;
        // Get the current list
        List<Stat> list = getStats();

        for (int i = 0; i < list.size(); i++)
        {
            // If t1 is before or equal than the current stat, then the lower index is found
            if (t1.compareTo(list.get(i).getTimestamp()) <= 0)
            {
                low = i;
                break;
            }
        }
        // If the lower index is found (still -1) then the given t1 is after the last stat stored,
        // meaning no stats in the given range.
        if (low == -1)
            return null;

        // If a lower index was found then search for the higher one from the lower
        int high = -1;
        for (int i = low; i < list.size(); i++)
        {
            // If t2 is lower than the current stat, the one in the previous index is the last stat considered in range
            if (t2.compareTo(list.get(i).getTimestamp()) < 0 )
            {
                high = i;
                break;
            }
        }
        // If the higher index was not found, return the stats from the lower index to the last one
        if (high == -1)
            return list.subList(low, list.size());

        // Else if the higher index was found return the sublist [low, high)
        return list.subList(low, high);
    }
}
