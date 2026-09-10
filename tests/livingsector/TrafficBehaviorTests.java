package livingsector;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import livingsector.model.SectorSnapshot;
import livingsector.model.SectorSnapshot.Port;
import livingsector.traffic.TrafficBudget;
import livingsector.traffic.TrafficContext;
import livingsector.traffic.TrafficPlan;
import livingsector.traffic.TrafficPolicy;
import livingsector.traffic.TrafficScheduler;
import livingsector.traffic.VipTrafficPolicy;

/** Public behavior regressions: distribution tests use fixed seeds and broad acceptance bands. */
public final class TrafficBehaviorTests {
    private static final int SAMPLES = 12000;
    private static int checks;

    public static void run() {
        checks = 0;
        smallerOriginsRemainCompetitive();
        distantDestinationsRemainCompetitive();
        stationsCompeteEqually();
        stationOnlySector();
        policyStateIsIndependent();
        zeroChanceDoesNotFillTarget();
        System.out.println("PASS: " + checks + " behavior assertions across 6 traffic scenarios");
    }

    private static void smallerOriginsRemainCompetitive() {
        SectorSnapshot sector = sector(port("small", 3, 0, true), port("large", 6, 0, true));
        TrafficContext context = context(sector, Collections.<String, Double>emptyMap());
        VipTrafficPolicy vip = vip();
        Random random = new Random(61231);
        int large = 0;
        // Independent departures with equal eligibility; count actual public policy results.
        for (int i = 0; i < SAMPLES; i++) {
            TrafficPlan plan = vip.plan(context, random);
            if (plan == null) throw new AssertionError("Compatible ports should yield a route");
            if (plan.originId.equals("large")) large++;
        }
        // Roughly two thirds for size 6, rather than four fifths with the old size weighting.
        shareBetween(large, .63, .70, "Larger origins retain a mild advantage without dominating small ports");
    }

    private static void distantDestinationsRemainCompetitive() {
        SectorSnapshot sector = sector(port("home", 5, 0, true),
                port("near", 5, 0, true), port("far", 5, 30, true));
        TrafficContext context = onlyHomeCanDepart(sector);
        VipTrafficPolicy vip = vip();
        Random random = new Random(50332);
        int far = 0;
        for (int i = 0; i < SAMPLES; i++) {
            TrafficPlan plan = vip.plan(context, random);
            if (plan == null || !plan.originId.equals("home")) throw new AssertionError("Origin cooldown ignored");
            if (plan.destinationId.equals("far")) far++;
        }
        // The distant port should receive about a third of trips, not the old one fifth.
        shareBetween(far, .30, .37, "Longer routes remain competitive while shorter routes stay preferred");
    }

    private static void stationsCompeteEqually() {
        VipTrafficPolicy vip = vip();
        SectorSnapshot origins = sector(port("planet", 5, 0, true), port("station", 5, 0, false));
        SectorSnapshot destinations = sector(port("home", 5, 0, true),
                port("planet", 5, 10, true), port("station", 5, 10, false));
        TrafficContext originContext = context(origins, Collections.<String, Double>emptyMap());
        TrafficContext destinationContext = onlyHomeCanDepart(destinations);
        Random random = new Random(45519);
        int stationOrigins = 0, stationDestinations = 0;
        for (int i = 0; i < SAMPLES; i++) {
            TrafficPlan outbound = vip.plan(originContext, random);
            TrafficPlan inbound = vip.plan(destinationContext, random);
            if (outbound == null || inbound == null) throw new AssertionError("Station route missing");
            if (outbound.originId.equals("station")) stationOrigins++;
            if (inbound.destinationId.equals("station")) stationDestinations++;
        }
        shareBetween(stationOrigins, .47, .53, "Equal planets and stations have equal origin opportunities");
        shareBetween(stationDestinations, .47, .53, "Equal planets and stations have equal destination opportunities");
    }

    private static void stationOnlySector() {
        SectorSnapshot stations = sector(port("a", 5, 0, false), port("b", 5, 10, false));
        SectorSnapshot planets = sector(port("a", 5, 0, true), port("b", 5, 10, true));
        VipTrafficPolicy vip = vip();
        check(vip.budget(stations).target == vip.budget(planets).target && vip.budget(stations).target > 0,
                "Station-only sector contributes the same population target as equivalent planets");
        check(vip.plan(context(stations, Collections.<String, Double>emptyMap()), new Random(1)) != null,
                "Station-to-station travel does not require a planetary endpoint");
        VipTrafficPolicy.Config config = new VipTrafficPolicy.Config();
        config.includeStations = false;
        check(new VipTrafficPolicy(config).budget(stations).target == 0,
                "Opting out of stations removes them from the population target too");
    }

    private static void policyStateIsIndependent() {
        SectorSnapshot sector = sector(port("a", 5, 0, true), port("b", 5, 0, true));
        FixedPolicy alpha = new FixedPolicy("alpha", 1);
        FixedPolicy beta = new FixedPolicy("beta", 1);
        TrafficScheduler scheduler = new TrafficScheduler();
        Random random = new Random(5891);
        TrafficPlan first = scheduler.evaluate(alpha, sector, noRoutes(), 1, 5, random);
        check(first != null, "First policy can depart");
        scheduler.recordDeparture(first, 1);
        List<TrafficContext.Route> fullAlpha = Collections.singletonList(new TrafficContext.Route("alpha", "a", "b"));
        check(scheduler.evaluate(alpha, sector, fullAlpha, 6, 5, random) == null,
                "A policy at its hard limit cannot depart");
        TrafficPlan second = scheduler.evaluate(beta, sector, fullAlpha, 6, 5, random);
        check(second != null && second.originId.equals(first.originId),
                "Another type's cap, route, and origin cooldown do not block a departure");
        scheduler.recordDeparture(second, 6);
        check(scheduler.evaluate(alpha, sector, noRoutes(), 10, 5, random) == null,
                "First policy still honors its own origin cooldown");
        check(scheduler.evaluate(alpha, sector, noRoutes(), 11, 5, random) != null,
                "First policy's cooldown ends independently");
        check(scheduler.evaluate(beta, sector, noRoutes(), 11, 5, random) == null,
                "Second policy keeps its later cooldown");
        check(scheduler.evaluate(beta, sector, noRoutes(), 16, 5, random) != null,
                "Second policy resumes when its own cooldown ends");
    }

    private static void zeroChanceDoesNotFillTarget() {
        FixedPolicy disabledDepartures = new FixedPolicy("zero_chance", 0);
        TrafficScheduler scheduler = new TrafficScheduler();
        SectorSnapshot sector = sector(port("a", 5, 0, true), port("b", 5, 0, true));
        Random random = new Random(594);
        boolean proposed = false;
        for (int day = 5; day <= 500; day += 5) {
            proposed |= scheduler.evaluate(disabledDepartures, sector, noRoutes(), day, 5, random) != null;
        }
        check(!proposed && disabledDepartures.planCalls == 0 && scheduler.target("zero_chance") > 0,
                "An empty population and positive target never force a departure with zero spawn chance");
    }

    /** Two extension types share a route and have independent state in the real scheduler. */
    private static final class FixedPolicy implements TrafficPolicy {
        final String id;
        final double dailyChance;
        int planCalls;
        FixedPolicy(String id, double dailyChance) { this.id = id; this.dailyChance = dailyChance; }
        public String getId() { return id; }
        public TrafficBudget budget(SectorSnapshot sector) { return new TrafficBudget(1, 0, 25, dailyChance, 1, 10); }
        public TrafficPlan plan(TrafficContext context, Random random) {
            planCalls++;
            if (!context.canDepart("a") || context.routeActive(id, "a", "b")) return null;
            return new TrafficPlan(id, "a", "b", "Test transport", Collections.singletonList("test"), 0, 30);
        }
    }

    private static VipTrafficPolicy vip() { return new VipTrafficPolicy(new VipTrafficPolicy.Config()); }
    private static Port port(String id, int size, float x, boolean planet) {
        return new Port(id, id, "friendly", "system_" + x, size, 5, x, 0, planet, false);
    }
    private static SectorSnapshot sector(Port... ports) {
        return new SectorSnapshot(Arrays.asList(ports), Collections.<String, Set<String>>emptyMap());
    }
    private static List<TrafficContext.Route> noRoutes() { return Collections.emptyList(); }
    private static TrafficContext context(SectorSnapshot sector, Map<String, Double> last) {
        return new TrafficContext(sector, noRoutes(), 1, 10, last);
    }
    private static TrafficContext onlyHomeCanDepart(SectorSnapshot sector) {
        Map<String, Double> last = new HashMap<String, Double>();
        for (Port port : sector.ports) if (!port.id.equals("home")) last.put(port.id, 1.0);
        return context(sector, last);
    }
    private static void shareBetween(int count, double low, double high, String message) {
        double share = (double) count / SAMPLES;
        check(share >= low && share <= high, message + ": observed share=" + share);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
