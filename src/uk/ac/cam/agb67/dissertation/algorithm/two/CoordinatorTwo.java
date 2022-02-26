package uk.ac.cam.agb67.dissertation.algorithm.two;

import uk.ac.cam.agb67.dissertation.*;

import org.chocosolver.solver.*;
import org.chocosolver.solver.variables.*;
import org.chocosolver.solver.search.strategy.Search;
import org.chocosolver.solver.search.strategy.assignments.DecisionOperator;
import org.chocosolver.solver.search.strategy.selectors.variables.*;
import org.chocosolver.solver.search.strategy.selectors.values.*;

import javax.swing.text.DefaultEditorKit;
import java.util.ArrayList;
import java.util.List;

public class CoordinatorTwo implements SchedulingAlgorithm {
// In the following comments, timeslot refers to an assigned day/time/room combination

    private boolean optimise_for_prefs = false;

    public CoordinatorTwo() {}
    public CoordinatorTwo(boolean opt) {
        optimise_for_prefs = opt;
    }

    @Override
    public Timetable generate(SchedulingProblem details) {
        String s = "";
        if (optimise_for_prefs) s="(Maximising preference values).";
        System.out.println("\nAttempting to generate a schedule with algorithm two. "+s+"\n");

        // Input validation step
        if (!details.check_validity()) {
            System.err.println("The given scheduling problem details were invalid.");
            return null;
        }

        // Create the variable arrays which Choco-Solver will use in it's model
        IntVar[] day_assignments = new IntVar[details.Session_Details.size()];
        IntVar[] start_time_assignments = new IntVar[details.Session_Details.size()];
        IntVar[] room_assignments = new IntVar[details.Session_Details.size()];

        // Use Choco-solver to model this scheduling problem,
        Model event_model = represent(details, day_assignments, start_time_assignments, room_assignments);
        Timetable schedule;

        if (optimise_for_prefs) {
            // Use Choco-solver to solve the model, finding the solution which maximises a metric for preference satisfaction
            Solution sol = optimise_and_solve(event_model, details, day_assignments, start_time_assignments, room_assignments);
            if (sol == null) {
                System.err.println("The optimising variant failed to solve the model. Deffering to the standard variant.");
                this.optimise_for_prefs = false;
                return this.generate(details);
            }

            // Finally decode the solution into a schedule
            schedule = decode_solution(sol, details, day_assignments, start_time_assignments, room_assignments);

        } else {
            // Use Choco-solver to solve the model, taking the first acceptable solution
            boolean solved = solve(event_model, details, day_assignments, start_time_assignments, room_assignments);
            if (!solved) {
                System.err.println("The model was not solved."); return null;
            }

            // Finally decode the solved model into a schedule
            schedule = decode_model_vars(details, day_assignments, start_time_assignments, room_assignments);
        }

        // Inform the user if this algorithm has failed
        if (schedule == null) {
            System.err.println("Algorithm two failed to generate a schedule.");
            return null;
        }

        // Return schedule
        if (Main.DEBUG) System.out.println("Algorithm two has generated a schedule.");
        if (Main.DEBUG) System.out.println(schedule.toString());
        return schedule;
    }

    private Model represent(SchedulingProblem details, IntVar[] day_assignments, IntVar[] start_time_assignments, IntVar[] room_assignments) {

        Model event = new Model();
        int num_sessions = details.Session_Details.size();

        // Variables initialisation
        //day_assignments = new IntVar[num_sessions];
        //start_time_assignments = new IntVar[num_sessions];
        //room_assignments = new IntVar[num_sessions];

        // Give the variables their appropriate domains, or for predetermined sessions set them to their given values
        for (int s = 0; s < num_sessions; s++) {
            if (details.Session_Details.get(s).getClass() != PredeterminedSession.class) {
                //if (Main.DEBUG) System.out.println("The number of days is "+details.Maximum_Days+" .");
                day_assignments[s] = event.intVar("Day Assignment for session #" + s, 0, details.Maximum_Days - 1, false);
                start_time_assignments[s] = event.intVar("Start Time Assignment for session #" + s, 0, details.Hours_Per_Day - 1, false);
                room_assignments[s] = event.intVar("Room Assignment for session #" + s, 0, details.Maximum_Rooms - 1, false);
            } else {
                // The session is predetermined, so lock the IntVars to the correct values
                //if (Main.DEBUG) System.out.println("Adding predetermined session #"+s+" .");
                PredeterminedSession session = (PredeterminedSession) details.Session_Details.get(s);
                day_assignments[s] = event.intVar("Day Assignment for session #" + s, session.PDS_Day);
                start_time_assignments[s] = event.intVar("Start Time Assignment for session #" + s, session.PDS_Start_Time);
                room_assignments[s] = event.intVar("Room Assignment for session #" + s, session.PDS_Room);
            }
        }


        // The following code creates the constraint which enforces each session having a unique combination of day, room, and set of hours.
        List<IntVar> timeslot_hash = new ArrayList<>();
        for (int s = 0; s < num_sessions; s++) {

            // We define a unique hash for every timeslot which is included in the length of the session
            // room + (time+offset)(MaxRooms) + day(MaxHours)(MaxRooms)
            for (int offset = 0; offset < details.Session_Details.get(s).Session_Length; offset++) {
                IntVar temp = room_assignments[s].add((start_time_assignments[s].add(offset)).mul(details.Maximum_Rooms),
                        day_assignments[s].mul(details.Hours_Per_Day).mul(details.Maximum_Rooms)).intVar();
                timeslot_hash.add(temp);
            }
            // The timeslot_hash list is not perfect however as offsets could spill over into other hashcodes if time+offset > MaxHours

            // So we include an additional constraint to ensure no session starts too close to the end of the day
            // if (Main.DEBUG) System.out.println("Creating close-to-end-of-day limit for session "+s+". This limit is "+start_time_assignments[s]+" + "+details
            // .Session_Details.get(s).Session_Length+" <= "+details.Hours_Per_Day+" .");
            event.arithm(event.intScaleView(start_time_assignments[s], details.Session_Details.get(s).Session_Length), "<=", details.Hours_Per_Day).post();
            // Condition: [start + length <= MaxHours] for session s
        }

        // We then require that time-slot hashcodes are all unique in the model
        IntVar[] timeslot_hash_array = intvar_list_to_array(timeslot_hash);
        event.allDifferent(timeslot_hash_array).post();


        // We will use the integer constraint factory to impose the requirement that assigned rooms have enough capacity
        int[] room_occupancy_limits = int_list_to_array(details.Room_Occupancy_Limits);
        int greatest_limit = 0;
        for (int lim : details.Room_Occupancy_Limits) {
            greatest_limit = Math.max(greatest_limit, lim);
        }

        for (int s = 0; s < num_sessions; s++) {
            // For each session create an IntVar which tracks how large the room assigned to the session is
            IntVar room_limit = event.intVar(("Room Capacity for session #" + s), 0, greatest_limit);
            event.element(room_limit, room_occupancy_limits, room_assignments[s]).post();

            // Ensure that the room size available to this session is greater than the number of people involved
            event.arithm(room_limit, ">=", details.Session_Details.get(s).Session_KeyInds.size()).post();
        }


        // Ensure that key individuals are only assigned to one session at a time
        for (int keyID = 0; keyID < details.KeyInd_Details.size(); keyID++) {

            // Prepare a list of all timeslot-hour hashcodes for this individual
            List<IntVar> relevant_timeslot_hash = new ArrayList<>();

            // Iterate through all sessions and Check that is individual is included in this session
            for (Session sesh : details.Session_Details) {
                if (sesh.Session_KeyInds.contains(keyID)) {

                    // We take a hash of the day and time (but NOT the room) of each session which includes this individual
                    // So if they are in two parallel sessions with the same room, they will have the same hash
                    for (int offset = 0; offset < sesh.Session_Length; offset++) {

                        // IntVar temp = start_time_assignments[sesh.Session_ID].add(offset).add(day_assignments[sesh.Session_ID].mul(details.Hours_Per_Day)).intVar();
                        // relevant_timeslot_hash.add(temp);

                        // The above implementation is the correct one, as afar as I can tell
                        // But Choco-Solver tells me that it cannot find any solutions when I add those variables to the model, before even applying the constraint
                        // Fix: So here is a modification which includes a constant addition, and this version works
                        IntVar extra_constant = event.intVar("constant for session #" + sesh.Session_ID, 1);

                        IntVar temp = extra_constant.add((start_time_assignments[sesh.Session_ID].add(offset)), day_assignments[sesh.Session_ID].mul(details.Hours_Per_Day)).intVar();
                        relevant_timeslot_hash.add(temp);
                    }
                }
            }

            // Then force all the timeslot hours that this individual is present in to be unique
            IntVar[] relevant_timeslot_hash_array = intvar_list_to_array(relevant_timeslot_hash);
            event.allDifferent(relevant_timeslot_hash_array).post();
        }

        return event;
    }

    private boolean solve(Model event, SchedulingProblem details, IntVar[] day_assignments, IntVar[] start_time_assignments, IntVar[] room_assignments) {
        // Use the solver to find the first acceptable solution to the problem
        Solver solver = event.getSolver();

        //if (Main.DEBUG) System.out.println("Printing full Event Model:\n");
        //if (Main.DEBUG) System.out.println(event.toString());

        long seed = (long) (Math.random() * Long.MAX_VALUE);
        // Search Strategy
        // Choose an uninstantiated variable with the smallest domain, and select values from the beginning of its domain
        solver.setSearch(Search.intVarSearch(
                new FirstFail(event),
                new IntDomainMin(),
                event.retrieveIntVars(true)
        ));

        if(solver.solve()){
            // Turn the instantiated values into a timetable to return
            return true;

        }else if(solver.isStopCriterionMet()){
            System.err.println("The Choco-Solver could not determine whether or not a solution existed.");
            if (Main.DEBUG) System.err.println("Search status: " + solver.getSearchState());
            return false;
        }else {
            System.err.println("No solution exists which satisfies the constraints.");
            if (Main.DEBUG) System.err.println("Search status: " + solver.getSearchState());
            return false;
        }
    }

    private Solution optimise_and_solve(Model event, SchedulingProblem details, IntVar[] day_assignments, IntVar[] start_time_assignments, IntVar[] room_assignments) {
        // Use the solver to find an acceptable solution to the problem, and iterate to improve the satisfaction score
        Solver solver = event.getSolver();
        Solution best_solution = null;
        int max_score = 0;

        TimetableVerifier ttv = new TimetableVerifier();
        TimetableSatisfactionMeasurer ttsm = new TimetableSatisfactionMeasurer();

        for (int i=0; i<10; i++) {

            // Search Strategy:
            // Choose an uninstantiated variable with a random domain, and select random values from within that domain to try
            long seedA = (long) (Math.random() * Long.MAX_VALUE);
            long seedB = (long) (Math.random() * Long.MAX_VALUE);
            if (Main.DEBUG) System.out.println("Iteration of optimising search. Seed A: " + seedA + " \n Seed B: "+ seedB);

            solver.setSearch(Search.intVarSearch(
                    new Random<>(seedA),  //new FirstFail(event),
                    new IntDomainRandom(seedB),
                    event.retrieveIntVars(true)
            ));

            // Find and decode a solution based on this search strategy
            Solution sol = solver.findSolution();
            Timetable prospect = decode_solution(sol, details, day_assignments, start_time_assignments, room_assignments);
            boolean prospect_valid; int prospect_score;

            // Check that is it is valid and record it's score
            try {
                prospect_valid = ttv.timetable_is_valid(prospect, details);
                prospect_score = ttsm.timetable_preference_satisfaction(prospect, details);
                if (Main.DEBUG) System.out.println("Found a valid timetable with score "+prospect_score+".");
            } catch (Exception e) {
                if (Main.DEBUG) System.out.println("The decoded schedule was not valid, and testing it threw an exception.");
                break;
            }

            // If it creates a valid timetable and gives a better score, then replace the solution we will return
            if (prospect_valid && prospect_score > max_score) {
                if (Main.DEBUG) System.out.println("Updating best solution due to new score of "+prospect_score+".");
                max_score = prospect_score;
                best_solution = sol;
            }

        }

        if (best_solution != null) {

            //if (Main.DEBUG) System.out.println("Solution: " + sol.toString());
            //if (Main.DEBUG) System.out.println("Day Assigmnent 0: " + sol.getIntVal(day_assignments[0]));
            return best_solution;

        }else {
            System.err.println("Optimising version of algorithm 2 failed to find a result.");
            if (Main.DEBUG) System.err.println("Search status: " + solver.getSearchState());
            return null;
        }
    }

    private Timetable decode_model_vars(SchedulingProblem details, IntVar[] day_assignments, IntVar[] start_time_assignments, IntVar[] room_assignments) {
        // Create a new schedule, then iterate through the sessions in the event
        // For every session retrieve the instantiated value of the IntVars for the day, time and room
        Timetable schedule = new Timetable(details.Maximum_Days, details.Hours_Per_Day, details.Maximum_Rooms);
        for (int s=0; s<details.Session_Details.size(); s++) {
            schedule.set(day_assignments[s].getValue(), start_time_assignments[s].getValue(), room_assignments[s].getValue(), details.Session_Details.get(s));
        }
        return schedule;
    }

    private Timetable decode_solution(Solution sol, SchedulingProblem details, IntVar[] day_assignments, IntVar[] start_time_assignments, IntVar[] room_assignments) {
        // Create a new schedule, then iterate through the sessions in the event
        // For every session retrieve the instantiated value of the IntVars for the day, time and room
        Timetable schedule = new Timetable(details.Maximum_Days, details.Hours_Per_Day, details.Maximum_Rooms);
        for (int s=0; s<details.Session_Details.size(); s++) {
            schedule.set(sol.getIntVal(day_assignments[s]), sol.getIntVal(start_time_assignments[s]), sol.getIntVal(room_assignments[s]), details.Session_Details.get(s));
        }

        return schedule;
    }

    private int[] int_list_to_array(List<Integer> list) {
        int[] array = new int[list.size()];

        for (int i=0; i<list.size(); i++) {
            array[i] = list.get(i);
        }

        return array;
    }

    private IntVar[] intvar_list_to_array(List<IntVar> list) {
        IntVar[] array = new IntVar[list.size()];

        for (int i=0; i<list.size(); i++) {
            array[i] = list.get(i);
        }

        return array;
    }

}