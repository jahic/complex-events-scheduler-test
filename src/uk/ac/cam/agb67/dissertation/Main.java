package uk.ac.cam.agb67.dissertation;

import uk.ac.cam.agb67.dissertation.algorithm.one.Coordinator;

import java.util.List;
import java.util.Set;

public class Main {

    public static boolean DEBUG = true;

    // Algorithm Selection
    String Algorithm_Selection;

    // The Full Details of the Event to Schedule
    SchedulingProblem Our_Event;


    public static void main(String[] args) {
        System.out.println("Hello project.");

        // This method is currently just a driver for testing Algorithm One

        SchedulingAlgorithm algorithm_one = new Coordinator();

    }
}
