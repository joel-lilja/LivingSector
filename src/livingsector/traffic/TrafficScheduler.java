package livingsector.traffic;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import livingsector.model.SectorSnapshot;

/** Saved scheduling state, independent of Starsector. Called once per planning pass. */
public final class TrafficScheduler {
    private static final class TypeState {
        double target, nextReroll, lastBaseTarget = -1;
        final Map<String, Double> lastDepartures = new HashMap<String, Double>();
    }

    private final Map<String, TypeState> states = new LinkedHashMap<String, TypeState>();

    public TrafficPlan evaluate(TrafficPolicy policy, SectorSnapshot sector,
                                List<TrafficContext.Route> active, double day, Random random) {
        return evaluate(policy, sector, active, day, 1, random);
    }

    public TrafficPlan evaluate(TrafficPolicy policy, SectorSnapshot sector,
                                List<TrafficContext.Route> active, double day, double intervalDays, Random random) {
        TrafficBudget budget = policy.budget(sector);
        TypeState state = state(policy.getId());
        // Recalculate a changed target at the next planning pass.
        if (day >= state.nextReroll || state.lastBaseTarget != budget.target) {
            state.target = Math.min(budget.hardLimit, Math.max(0,
                    budget.target * (1 + (random.nextDouble() * 2 - 1) * budget.variation)));
            state.nextReroll = day + budget.rerollDays;
            state.lastBaseTarget = budget.target;
        }
        // Avoid keeping IDs for decivilized/removed markets forever.
        state.lastDepartures.keySet().retainAll(portIds(sector));
        int count = 0;
        for (TrafficContext.Route route : active) if (policy.getId().equals(route.typeId)) count++;
        if (count >= budget.hardLimit || random.nextDouble()
                >= TrafficBudget.intervalChance(
                        TrafficBudget.spawnChance(count, state.target, budget.dailyChance), intervalDays)) return null;
        TrafficContext context = new TrafficContext(sector, active, day,
                budget.originCooldownDays, state.lastDepartures);
        TrafficPlan plan = policy.plan(context, random);
        if (plan != null && (!policy.getId().equals(plan.typeId) || !context.canDepart(plan.originId)
                || context.routeActive(plan.typeId, plan.originId, plan.destinationId))) {
            throw new IllegalArgumentException("Policy returned an invalid or duplicate departure: " + policy.getId());
        }
        return plan;
    }

    /** Only successful game-world spawns consume the origin cooldown. */
    public void recordDeparture(TrafficPlan plan, double day) {
        state(plan.typeId).lastDepartures.put(plan.originId, day);
    }

    public double target(String typeId) { return state(typeId).target; }

    private TypeState state(String id) {
        TypeState state = states.get(id);
        if (state == null) { state = new TypeState(); states.put(id, state); }
        return state;
    }

    private java.util.Set<String> portIds(SectorSnapshot sector) {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (SectorSnapshot.Port port : sector.ports) ids.add(port.id);
        return ids;
    }
}
