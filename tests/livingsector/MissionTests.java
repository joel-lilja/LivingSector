package livingsector;

import java.util.Collections;
import livingsector.model.TrafficMission;
import livingsector.model.TrafficMission.State;
import livingsector.traffic.TrafficPlan;

/** Mission transitions are independent of frame cadence and native object identity. */
public final class MissionTests {
    private static int checks;
    public static void run() {
        TrafficPlan plan = new TrafficPlan("vip", "station", "planet", "Test",
                Collections.singletonList("mudskipper_Standard"), .5f, 180);
        TrafficMission mission = new TrafficMission("ls-1", plan, "a", 42, 0, true, true);
        check(mission.stops.size() == 3 && mission.stops.get(2).marketId.equals("station"), "Return itinerary retains home station");
        mission.observeLeg(1);
        check(mission.active(), "Intermediate arrival does not complete a round trip");
        check(mission.bindFleet("first"), "First physical generation binds");
        check(!mission.bindFleet("first") && mission.generation() == 1, "Duplicate spawn notification is idempotent");
        boolean rejected = false;
        try { mission.bindFleet("duplicate"); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected && mission.fleetId().equals("first"), "A second live fleet cannot replace the existing binding");
        check(!mission.releaseFleet("stale"), "An unrelated despawn cannot clear the binding");
        check(mission.releaseFleet("first") && mission.active(), "Distance despawn preserves mission");
        check(mission.bindFleet("second") && mission.generation() == 2, "Respawn changes fleet ID but retains mission identity");
        check(!mission.releaseFleet("first") && mission.fleetId().equals("second"), "Late old-generation despawn is ignored");
        mission.observeLeg(2);
        mission.observeLeg(0);
        check(mission.leg() == 2, "Stale progress cannot rewind itinerary");
        check(mission.finish(State.DESTROYED, "battle"), "A battle can terminate the mission");
        check(!mission.finish(State.COMPLETED, "late arrival") && mission.state() == State.DESTROYED,
                "A late arrival cannot overwrite destruction");
        check(!mission.bindFleet("resurrected"), "Terminal missions cannot spawn another generation");

        for (State result : new State[]{State.COMPLETED, State.DESTROYED, State.CANCELLED, State.FAILED}) {
            TrafficMission trip = new TrafficMission("sequence-" + result, plan, "a", 42, 0, false, true);
            for (int i = 0; i < 40; i++) {
                String id = "fleet-" + i;
                trip.bindFleet(id);
                trip.bindFleet(id);
                trip.releaseFleet("fleet-" + (i - 1));
                check(id.equals(trip.fleetId()), "Old callbacks cannot erase a new generation");
                trip.releaseFleet(id);
            }
            trip.finish(result, "terminal");
            for (State late : State.values()) if (late != State.ACTIVE) trip.finish(late, "late");
            check(trip.state() == result && trip.events().size() <= 12, "Terminal outcome is stable and history bounded");
        }
        System.out.println("PASS: " + checks + " mission lifecycle assertions");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
