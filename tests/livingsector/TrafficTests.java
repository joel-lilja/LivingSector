package livingsector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import livingsector.model.SectorSnapshot;
import livingsector.model.SectorSnapshot.Port;
import livingsector.traffic.*;

/** Deterministic behavioral tests: no game installation or third-party test library needed. */
public final class TrafficTests {
    private static int checks;

    public static void main(String[] args) {
        check(Math.abs(TrafficBudget.intervalChance(.35, 5) - (1 - Math.pow(.65, 5))) < 1e-12,
                "Planning probability accounts for the configured interval");
        check(TrafficBudget.intervalChance(0, 5) == 0 && TrafficBudget.intervalChance(1, 5) == 1,
                "Zero and certain probabilities remain exact");
        check(Math.abs(TrafficBudget.intervalChance(.35, 1) - .35) < 1e-12,
                "One-day interval preserves the previous chance");
        diplomacyAndEligibility();
        cooldownAndDuplicates();
        budgetsAndScheduling();
        changingSector();
        populationSimulation();
        extensionPolicy();
        System.out.println("PASS: " + checks + " traffic assertions");
    }

    private static void diplomacyAndEligibility() {
        VipTrafficPolicy vip = new VipTrafficPolicy(new VipTrafficPolicy.Config());
        SectorSnapshot hostile = sector(Arrays.asList(port("a", "a"), port("b", "b")), "a", "b");
        check(vip.plan(context(hostile), new Random(1)) == null, "Hostile endpoints must not generate trips");
        check(!hostile.peaceful("b", "a"), "One-way hostility must also block the reverse trip");
        check(vip.plan(context(sector(Collections.singletonList(port("a", "a")))), new Random(1)) == null,
                "A single market cannot send a trip to itself");
        SectorSnapshot same = sector(Arrays.asList(port("a", "a"), port("b", "a")));
        check(vip.plan(context(same), new Random(1)) != null, "Same-faction traffic is allowed");
        SectorSnapshot station = sector(Arrays.asList(port("a", "a"),
                new Port("station", "Station", "a", "system", 6, 5, 1, 1, false, false)));
        check(vip.plan(context(station), new Random(1)) != null, "Stations are eligible by default");
        VipTrafficPolicy.Config config = new VipTrafficPolicy.Config();
        config.includeStations = false;
        check(new VipTrafficPolicy(config).plan(context(station), new Random(1)) == null, "Station opt-out works");
        List<Port> ports = Arrays.asList(port("lonely", "enemy"), port("a", "a"), port("b", "a"));
        SectorSnapshot mixed = sector(ports, "enemy", "a");
        for (int seed = 0; seed < 100; seed++) {
            TrafficPlan plan = vip.plan(context(mixed), new Random(seed));
            check(plan != null && !plan.originId.equals("lonely") && !plan.destinationId.equals("lonely"),
                    "Dead-end origins must not prevent valid traffic elsewhere");
        }
        config.enabled = false;
        check(new VipTrafficPolicy(config).budget(same).target == 0, "Disabled policy has zero target");
    }

    private static void cooldownAndDuplicates() {
        SectorSnapshot sector = sector(Arrays.asList(port("a", "a"), port("b", "a")));
        VipTrafficPolicy vip = new VipTrafficPolicy(new VipTrafficPolicy.Config());
        Map<String, Double> last = new HashMap<String, Double>();
        last.put("a", 5.0);
        last.put("b", 5.0);
        TrafficContext cooldown = new TrafficContext(sector, routes(), 14, 10, last);
        check(vip.plan(cooldown, new Random(1)) == null, "Origins on cooldown cannot depart");
        check(vip.plan(new TrafficContext(sector, routes(), 15, 10, last), new Random(1)) != null,
                "Cooldown expires at the configured boundary");
        List<TrafficContext.Route> active = Arrays.asList(new TrafficContext.Route("vip", "a", "b"),
                new TrafficContext.Route("vip", "b", "a"));
        check(vip.plan(new TrafficContext(sector, active, 20, 10, last), new Random(1)) == null,
                "Duplicate routes do not spawn");
    }

    private static void budgetsAndScheduling() {
        VipTrafficPolicy.Config config = new VipTrafficPolicy.Config();
        config.dailySpawnChance = 1;
        config.targetVariation = 0;
        VipTrafficPolicy vip = new VipTrafficPolicy(config);
        SectorSnapshot sector = sector(Arrays.asList(port("a", "a"), port("b", "a")));
        TrafficScheduler scheduler = new TrafficScheduler();
        TrafficPlan first = scheduler.evaluate(vip, sector, routes(), 1, new Random(1));
        check(first != null, "Empty population can depart");
        check(scheduler.evaluate(vip, sector, routes(), 1, new Random(1)) != null,
                "A proposed but failed spawn must not consume cooldown");
        scheduler.recordDeparture(first, 1);
        TrafficPlan second = scheduler.evaluate(vip, sector, routes(), 2, new Random(1));
        check(second != null && !first.originId.equals(second.originId), "Successful spawn consumes origin cooldown");
        scheduler.recordDeparture(second, 2);
        check(scheduler.evaluate(vip, sector, routes(), 3, new Random(1)) == null, "Both origins on cooldown");
        check(TrafficBudget.spawnChance(5, 5, 1) == .5, "Soft target halves departure probability");
        check(TrafficBudget.spawnChance(10, 5, 1) < .1, "Crowding strongly reduces departures");
        check(TrafficBudget.spawnChance(0, 0, 1) == 0, "Zero budget never spawns");
        List<TrafficContext.Route> full = new ArrayList<TrafficContext.Route>();
        for (int i = 0; i < config.hardLimit; i++) full.add(new TrafficContext.Route("vip", "x" + i, "y" + i));
        check(scheduler.evaluate(vip, sector, full, 100, new Random(1)) == null, "Hard limit is absolute");
        config.targetVariation = .25;
        TrafficScheduler fluctuating = new TrafficScheduler();
        fluctuating.evaluate(vip, sector, full, 1, new Random(20));
        double initial = fluctuating.target("vip");
        fluctuating.evaluate(vip, sector, full, 2, new Random(999));
        check(initial == fluctuating.target("vip"), "Target remains stable between rerolls");
        fluctuating.evaluate(vip, sector, full, 26, new Random(200));
        check(initial != fluctuating.target("vip"), "Target rerolls after 25 campaign days");
        double base = vip.budget(sector).target;
        check(fluctuating.target("vip") >= base * .75 && fluctuating.target("vip") <= base * 1.25,
                "Fluctuation stays in its configured range");
    }

    private static void changingSector() {
        VipTrafficPolicy vip = new VipTrafficPolicy(new VipTrafficPolicy.Config());
        List<Port> ports = new ArrayList<Port>();
        for (int i = 0; i < 120; i++) ports.add(port("p" + i, "a"));
        SectorSnapshot large = sector(ports);
        check(vip.budget(large).target == 12, "Target scales with inhabited colony count");
        TrafficScheduler scheduler = new TrafficScheduler();
        scheduler.evaluate(vip, large, routes(), 1, new Random(1));
        double before = scheduler.target("vip");
        scheduler.evaluate(vip, sector(ports.subList(0, 2)), routes(), 2, new Random(1));
        check(scheduler.target("vip") < before, "Population loss updates budget without waiting for reroll");
        SectorSnapshot peaceful = sector(Arrays.asList(port("a", "a"), port("b", "b")));
        check(vip.plan(context(peaceful), new Random(1)) != null, "Peace allows departures");
        SectorSnapshot war = sector(peaceful.ports, "b", "a");
        check(vip.plan(context(war), new Random(1)) == null, "A new war stops departures");
        check(vip.plan(context(peaceful), new Random(1)) != null, "Peace restoration resumes departures");
    }

    private static void populationSimulation() {
        VipTrafficPolicy.Config config = new VipTrafficPolicy.Config();
        config.hardLimit = 12;
        config.dailySpawnChance = .9;
        VipTrafficPolicy vip = new VipTrafficPolicy(config);
        List<Port> ports = new ArrayList<Port>();
        for (int i = 0; i < 100; i++) ports.add(port("p" + i, "f" + i % 4));
        SectorSnapshot sector = sector(ports, "f0", "f1", "f2", "f3");
        TrafficScheduler scheduler = new TrafficScheduler();
        List<TrafficContext.Route> active = new ArrayList<TrafficContext.Route>();
        List<Integer> expiry = new ArrayList<Integer>();
        Random random = new Random(98231);
        int spawns = 0, peak = 0;
        for (int day = 1; day <= 4000; day++) {
            for (int i = expiry.size() - 1; i >= 0; i--) {
                if (day >= expiry.get(i)) { expiry.remove(i); active.remove(i); }
            }
            TrafficPlan plan = scheduler.evaluate(vip, sector, active, day, random);
            if (plan != null) {
                check(!plan.originId.equals(plan.destinationId), "Simulation never self-routes");
                check(sector.peaceful(sector.port(plan.originId).factionId, sector.port(plan.destinationId).factionId),
                        "Simulation respects diplomacy");
                scheduler.recordDeparture(plan, day);
                active.add(new TrafficContext.Route(plan.typeId, plan.originId, plan.destinationId));
                expiry.add(day + 20 + random.nextInt(40));
                spawns++;
            }
            peak = Math.max(peak, active.size());
            check(active.size() <= config.hardLimit, "Long campaign stays bounded");
        }
        check(spawns > 100 && peak > 5, "Simulation sustains traffic over time");
    }

    private static void extensionPolicy() {
        final SectorSnapshot sector = sector(Arrays.asList(port("a", "a"), port("b", "a")));
        TrafficPolicy extension = new TrafficPolicy() {
            public String getId() { return "test_relief"; }
            public TrafficBudget budget(SectorSnapshot snapshot) {
                return new TrafficBudget(snapshot.ports.size(), 0, 10, 1, 5, 0);
            }
            public TrafficPlan plan(TrafficContext context, Random random) {
                return new TrafficPlan(getId(), "a", "b", "Test Relief", Collections.singletonList("test"), 0, 30);
            }
        };
        TrafficRegistry.register(extension);
        check(TrafficRegistry.policies().contains(extension), "Extensions register without changing the scheduler");
        TrafficScheduler scheduler = new TrafficScheduler();
        check(scheduler.evaluate(extension, sector, routes(), 1, new Random(1)) != null, "Extension uses shared scheduling");
        boolean duplicateRejected = false;
        try { TrafficRegistry.register(extension); } catch (IllegalArgumentException expected) { duplicateRejected = true; }
        check(duplicateRejected, "Policy IDs must be unique");
    }

    private static Port port(String id, String faction) {
        return new Port(id, id, faction, "system_" + id, 5, 5, 0, 0, true, false);
    }

    private static SectorSnapshot sector(List<Port> ports, String... enemies) {
        Map<String, Set<String>> hostility = new HashMap<String, Set<String>>();
        for (int i = 0; i < enemies.length; i += 2) {
            Set<String> set = hostility.get(enemies[i]);
            if (set == null) { set = new HashSet<String>(); hostility.put(enemies[i], set); }
            set.add(enemies[i + 1]);
        }
        return new SectorSnapshot(ports, hostility);
    }

    private static List<TrafficContext.Route> routes() { return Collections.emptyList(); }
    private static TrafficContext context(SectorSnapshot sector) {
        return new TrafficContext(sector, routes(), 1, 10, Collections.<String, Double>emptyMap());
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
