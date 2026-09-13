package livingsector;

import java.util.*;
import livingsector.model.SectorSnapshot;
import livingsector.model.SectorSnapshot.Port;
import livingsector.model.TrafficMission;
import livingsector.traffic.*;

final class CivilianTrafficTests {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static Port port(String id, String system, boolean planet) {
        return new Port(id, id, "modded", system, 5, 5, 0, 0, planet, false);
    }
    private static TrafficContext context(SectorSnapshot sector) {
        return new TrafficContext(sector, Collections.emptyList(), 100, 10, Collections.emptyMap());
    }
    static void run() {
        SectorSnapshot sector = new SectorSnapshot(Arrays.asList(port("a", "s1", true), port("b", "s1", false),
                port("c", "s2", true), port("d", "s2", false)), Collections.emptyMap());
        String[] types = {CivilianTrafficPolicy.LOCAL, CivilianTrafficPolicy.LINER, CivilianTrafficPolicy.CHARTER};
        double[] chances = {.35, .5, .25};
        for (int type = 0; type < types.length; type++) {
            CivilianTrafficPolicy.Config config = new CivilianTrafficPolicy.Config();
            config.localEnabled = type == 0; config.linerEnabled = type == 1; config.charterEnabled = type == 2;
            CivilianTrafficPolicy policy = new CivilianTrafficPolicy(config);
            Random random = new Random(712);
            int returning = 0;
            for (int sample = 0; sample < 3000; sample++) {
                TrafficPlan plan = policy.plan(context(sector), random);
                check(plan != null && plan.typeId.equals(types[type]) && plan.nativeRoute && plan.variants.isEmpty()
                        && plan.fleetRequest != null, "Each type proposes native intent before hull selection");
                boolean same = sector.port(plan.originId).systemId.equals(sector.port(plan.destinationId).systemId);
                check(type == 2 || same == (type == 0), "Local shuttles stay local; liners cross systems");
                TrafficMission mission = new TrafficMission("test", plan, "modded", 1, 0, plan.roundTrip, false);
                check(mission.stops.size() == (plan.roundTrip ? 3 : 2) && mission.stops.get(1).dwellDays == plan.arrivalDays,
                        "All trips unload; only sampled return trips have a third stop");
                if (plan.roundTrip) returning++;
            }
            check(Math.abs(returning / 3000.0 - chances[type]) < .04, "Return proportions match configured chance");
            for (int chance = 0; chance <= 1; chance++) {
                config.localReturnChance = config.linerReturnChance = config.charterReturnChance = chance;
                for (int i = 0; i < 20; i++) check(policy.plan(context(sector), random).roundTrip == (chance == 1),
                        "Return probabilities zero and one are exact");
            }
        }
        CivilianTrafficPolicy.Config config = new CivilianTrafficPolicy.Config();
        config.dailySpawnChance = 1; config.targetVariation = 0; config.hardLimit = 2;
        CivilianTrafficPolicy policy = new CivilianTrafficPolicy(config);
        TrafficScheduler scheduler = new TrafficScheduler();
        List<TrafficContext.Route> full = Arrays.asList(new TrafficContext.Route(types[0], "a", "b"),
                new TrafficContext.Route("vip", "c", "d"));
        check(scheduler.evaluate(policy, sector, full, 100, new Random(1)) == null, "Legacy VIPs and new types share hard limit");
        double target = policy.budget(sector).target;
        config.localEnabled = config.linerEnabled = false;
        check(policy.budget(sector).target == target, "More types do not multiply population target");
        TrafficPlan first = scheduler.evaluate(policy, sector, Collections.emptyList(), 100, new Random(1));
        check(first != null, "Empty budget can depart"); scheduler.recordDeparture(first, 100);
        config.charterEnabled = false; config.localEnabled = true;
        TrafficPlan next = scheduler.evaluate(policy, sector, Collections.emptyList(), 101, new Random(1));
        check(next != null && !next.originId.equals(first.originId), "Origin cooldown is shared across civilian types");
        config.dailySpawnChance = 0;
        check(scheduler.evaluate(policy, sector, Collections.emptyList(), 200, new Random(1)) == null, "No forced minimum traffic");
        SectorSnapshot stations = new SectorSnapshot(Arrays.asList(port("a", "s", false), port("b", "s", false)), Collections.emptyMap());
        check(policy.plan(context(stations), new Random(1)) != null, "Station-only routes are eligible");
        config.includeStations = false;
        check(policy.plan(context(stations), new Random(1)) == null, "Station opt-out works");
        System.out.println("PASS: Phase A routing, shared budget/cooldown, and 9000 sampled return itineraries");
    }
}
