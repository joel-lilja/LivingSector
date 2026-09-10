package livingsector.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import livingsector.traffic.TrafficPlan;

/** Saved intent and lifecycle; native execution owns position, movement, and combat. */
public final class TrafficMission {
    public enum State { ACTIVE, COMPLETED, DESTROYED, CANCELLED, FAILED }
    public static final class Stop {
        public final String marketId;
        public final float dwellDays;
        public Stop(String marketId, float dwellDays) {
            if (marketId == null || marketId.isEmpty() || !Float.isFinite(dwellDays) || dwellDays <= 0) {
                throw new IllegalArgumentException("A stop needs a market and positive dwell time");
            }
            this.marketId = marketId;
            this.dwellDays = dwellDays;
        }
    }

    public final int schemaVersion = 1;
    public final String id, factionId;
    public final TrafficPlan plan;
    public final long seed;
    public final double createdAt;
    public final boolean test;
    public final List<Stop> stops;
    private State state = State.ACTIVE;
    private int leg, generation;
    private String fleetId, lastFleetId, outcome;
    private final List<String> events = new ArrayList<String>();
    public interface EventSink { void accept(TrafficMission mission, String kind, String detail); }
    private transient EventSink eventSink;
    public void setEventSink(EventSink sink) { eventSink = sink; }
    public boolean hasEventSink() { return eventSink != null; }

    public TrafficMission(String id, TrafficPlan plan, String factionId, long seed, double day,
                          boolean roundTrip, boolean test) {
        if (id == null || id.isEmpty() || plan == null || factionId == null || !Double.isFinite(day)) {
            throw new IllegalArgumentException("Invalid mission identity");
        }
        this.id = id;
        this.plan = plan;
        this.factionId = factionId;
        this.seed = seed;
        createdAt = day;
        this.test = test;
        List<Stop> itinerary = new ArrayList<Stop>();
        itinerary.add(new Stop(plan.originId, Math.max(.01f, plan.boardingDays)));
        itinerary.add(new Stop(plan.destinationId, 1f));
        if (roundTrip) itinerary.add(new Stop(plan.originId, 1f));
        stops = Collections.unmodifiableList(itinerary);
        note("admitted");
    }

    public boolean active() { return state == State.ACTIVE; }
    public State state() { return state; }
    public int leg() { return leg; }
    public int generation() { return generation; }
    public String fleetId() { return fleetId; }
    public String lastFleetId() { return lastFleetId; }
    public String outcome() { return outcome; }
    public List<String> events() { return Collections.unmodifiableList(events); }

    public void observeLeg(int next) {
        if (!active() || next <= leg) return;
        if (next >= stops.size()) throw new IllegalArgumentException("Leg outside itinerary");
        leg = next;
        note("LEG_CHANGED", "leg " + (leg + 1) + "/" + stops.size());
    }

    /** Duplicate spawn notifications are harmless; a second live representation is an error. */
    public boolean bindFleet(String id) {
        if (!active()) return false;
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Missing fleet ID");
        if (id.equals(fleetId)) return false;
        if (fleetId != null) throw new IllegalStateException("Mission already has fleet " + fleetId);
        fleetId = lastFleetId = id;
        generation++;
        note("MATERIALIZED", "physical generation " + generation + ": " + id);
        return true;
    }

    public boolean releaseFleet(String id) {
        if (id == null || !id.equals(fleetId)) return false;
        fleetId = null;
        if (active()) note("DEMATERIALIZED", "native route without physical fleet");
        return true;
    }

    public boolean finish(State result, String reason) {
        if (result == null || result == State.ACTIVE) throw new IllegalArgumentException("Terminal result required");
        if (!active()) return false;
        state = result;
        outcome = reason;
        fleetId = null;
        note(result.name(), result + ": " + reason);
        return true;
    }

    public void note(String event) {
        note("NOTE", event);
    }
    public void note(String kind, String event) {
        if (events.size() == 12) events.remove(0);
        events.add(event);
        if (eventSink != null) eventSink.accept(this, kind, event);
    }
}
