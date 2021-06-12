package Drone.sensors;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * DroneBuffer class that models the buffer the sensor uses to store the data it produces.
 *
 * It implements the overlapping sliding windows technique: when the buffer is full (window), computes the average
 * of values currently stored. This grants to get a single value that represents the whole window, in order to
 * reduce the value of data the buffer must store and return to the drone. The overlapping windows approach
 * aims to make the averages computed more correlated between them, in order to get a better data acquisition history.
 * This is done by removing only a percentage of data inside the buffer when is cleaned to compute the average, and
 * maintain inside the buffer the remaining values. This brings to more averages computed, but more correlation in
 * data.
 */
public class DroneBuffer implements Buffer{

    // FIFO queue to store the data acquired by sensor
    private final Queue<Measurement> buffer;
    // Max size of the buffer
    private final int MAX_SIZE;
    // List of the windows average value
    private final List<Measurement> avgHistory;
    // Number of values to overlap in the next window
    private final int OVERLAP;

    // Default constructor. Set the max size of the buffer and the number of overlapped values
    public DroneBuffer(int maxSize, float overlapPerc)
    {
        this.buffer = new LinkedList<>();
        this.avgHistory = new LinkedList<>();
        this.MAX_SIZE = maxSize;

        this.OVERLAP = (int) (MAX_SIZE * overlapPerc);
    }

    // Add a new measurement to the buffer. If it is full, compute the average and clear the buffer (by removing
    // the values that doesn't belong to the next window, based on overlap number).
    // Synchronized method to avoid buffer cleaning while adding new measurement.
    @Override
    public synchronized void addMeasurement(Measurement m) {
        // Add the measurement to the buffer
        this.buffer.add(m);

        // If the buffer is full compute the average and remove #OVERLAP values
        if (this.buffer.size() >= MAX_SIZE)
        {
            // Removes the data from buffer to delete the ones that won't be in the next window, and sum their value
            double avg = 0;
            for (int i = 0; i < OVERLAP; i++)
            {
                m = this.buffer.poll();
                if (m != null)
                    avg += m.getValue();
            }

            // Sum the value of the remaining values without popping them from queue (they are overlapping values)
            for (Measurement meas : this.buffer)
                avg += meas.getValue();

            // Compute the average
            avg /= MAX_SIZE;

            // Assign the average to the last Measurement object popped from queue (to avoid creation of new object),
            // and add it in the list
            assert m != null;
            m.setValue(avg);
            this.avgHistory.add(m);
        }
    }

    // Read all the measurements stored in average history list and clear both list and queue. This method is called
    // after a delivery completion. Synchronized method to avoid retrieving average list while last adding,
    // and avoid structure cleaning while adding values to them.
    @Override
    public synchronized List<Measurement> readAllAndClean() {
        // Clear the buffer
        this.buffer.clear();
        // Copy the values to the average list
        List<Measurement> list = new LinkedList<>(this.avgHistory);
        // Clear the average history list
        this.avgHistory.clear();
        // Return the copy
        return list;
    }
}
