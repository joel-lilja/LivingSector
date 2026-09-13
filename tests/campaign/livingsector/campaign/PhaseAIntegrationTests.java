package livingsector.campaign;

import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.thoughtworks.xstream.XStream;
import java.util.*;
import livingsector.LivingSectorPlugin;
import livingsector.model.TrafficMission;
import livingsector.traffic.*;
import static livingsector.campaign.CampaignFixture.*;
import static livingsector.campaign.IntegrationSuite.check;

final class PhaseAIntegrationTests {
    static void register(IntegrationSuite suite) {
        suite.add("civilian.factionPoolsAndFallbacks", PhaseAIntegrationTests::factionPoolsAndFallbacks);
        suite.add("civilian.rejectUnsuitableHulls", PhaseAIntegrationTests::rejectUnsuitableHulls);
        suite.add("civilian.compositionRegeneratesWithinBudget", PhaseAIntegrationTests::compositionRegeneratesWithinBudget);
        suite.add("civilian.nativeAutomaticAndFreshSectorWait", PhaseAIntegrationTests::nativeAutomaticAndFreshSectorWait);
        suite.add("civilian.savedCompositionAndLegacyPlan", PhaseAIntegrationTests::savedCompositionAndLegacyPlan);
    }
    private static TrafficPlan plan(double preference, boolean returning, int capacity) {
        return TrafficPlan.civilian(CivilianTrafficPolicy.CHARTER, "origin", "station", "Private Charter",
                new CivilianFleetRequest(capacity, 3, 2, preference), .5f, .25f, 90f, returning);
    }
    private static void hull(World world, String id, boolean civilian, HullSize size, ShipTypeHints... hints) {
        ShipHullSpecAPI spec = proxy(ShipHullSpecAPI.class, (m, a) -> {
            if (m.equals("isCivilianNonCarrier")) return civilian;
            if (m.equals("getHullSize")) return size;
            if (m.equals("getFleetPoints")) return 3;
            if (m.equals("getMaxCrew")) return 100f;
            if (m.equals("getMinCrew")) return 5f;
            if (m.equals("getHints")) return hints.length == 0 ? EnumSet.noneOf(ShipTypeHints.class) : EnumSet.copyOf(Arrays.asList(hints));
            return null;
        });
        ShipVariantAPI[] variant = {null};
        variant[0] = proxy(ShipVariantAPI.class, (m, a) -> {
            if (m.equals("getHullSpec")) return spec;
            if (m.equals("getHullVariantId")) return id;
            if (m.equals("clone")) return variant[0];
            return null;
        });
        world.variantSpecs.put(id, variant[0]);
    }
    private static void pool(World world, String faction, String... variants) {
        Map<String, List<String>> roles = new HashMap<String, List<String>>();
        for (String size : new String[]{"Small", "Medium", "Large"}) roles.put("personnel" + size, Arrays.asList(variants));
        world.rolePools.put(faction, roles);
    }
    private static World world() {
        World world = new World();
        world.factionOverrides.put("independent", world.faction("independent"));
        hull(world, "modded_bus", true, HullSize.FRIGATE, ShipTypeHints.LINER);
        hull(world, "indie_bus", true, HullSize.FRIGATE, ShipTypeHints.TRANSPORT);
        pool(world, "a", "modded_bus"); pool(world, "independent", "indie_bus");
        return world;
    }
    private static void factionPoolsAndFallbacks() {
        World world = world();
        check(CivilianShipSelector.resolve(plan(1, false, 10), "a", 1).variants.equals(Arrays.asList("modded_bus")), "Home-only preference uses arbitrary faction roster");
        check(CivilianShipSelector.resolve(plan(0, false, 10), "a", 1).variants.equals(Arrays.asList("indie_bus")), "Independent-first preference works");
        Set<String> selected = new HashSet<String>();
        Random seeds = new Random(721);
        for (int i = 0; i < 100; i++) selected.addAll(CivilianShipSelector.resolve(plan(.65, false, 10), "a", seeds.nextLong()).variants);
        check(selected.size() == 2, "Both eligible pools contribute over repeated departures");
        world.rolePools.remove("a");
        check(CivilianShipSelector.resolve(plan(1, false, 10), "a", 1).variants.equals(Arrays.asList("indie_bus")), "Missing faction transports fall back to Independent");
        pool(world, "a", "modded_bus"); world.rolePools.remove("independent");
        check(CivilianShipSelector.resolve(plan(0, false, 10), "a", 1).variants.equals(Arrays.asList("modded_bus")), "Missing Independent transports fall back to home");
    }
    private static void rejectUnsuitableHulls() {
        World world = world();
        hull(world, "warship", false, HullSize.FRIGATE, ShipTypeHints.TRANSPORT);
        hull(world, "freighter", true, HullSize.FRIGATE);
        hull(world, "station", true, HullSize.FRIGATE, ShipTypeHints.LINER, ShipTypeHints.STATION);
        hull(world, "oversize", true, HullSize.CAPITAL_SHIP, ShipTypeHints.LINER);
        pool(world, "a", "warship", "freighter", "station", "oversize", "missing");
        pool(world, "independent");
        check(world.traffic.start(plan(1, false, 10), 0, 1, false, false) == null && world.traffic.size() == 0
                && world.created.isEmpty(), "No suitable hull means no admission or empty physical fleet");
        pool(world, "independent", "indie_bus");
        TrafficMission accepted = world.traffic.start(plan(1, false, 10), 0, 1, false, false);
        check(accepted.id.equals("ls-1") && accepted.factionId.equals("a"), "Failed selection consumes no ID; fallback hull retains operator faction");
    }
    private static void compositionRegeneratesWithinBudget() {
        World world = world();
        TrafficMission mission = world.traffic.start(plan(1, true, 150), 0, 1, false, false);
        NativeMission entry = world.traffic.active.get(mission.id);
        check(mission.plan.variants.isEmpty() && entry.budget.remaining == 6 && mission.stops.size() == 3
                && entry.route.getSegments().size() == 5 && world.created.isEmpty(), "Admission keeps a profile, budget and fixed itinerary");
        check(world.routes.spawnRoute(entry.route), "Faction composition generates at materialization");
        FakeFleet first = world.created.get(0);
        check(first.members.get(0).getVariant().getHullVariantId().equals("modded_bus"), "Home faction supplies the initial generation");
        String original = first.members.get(0).getId();
        first.members.remove(1);
        world.ships.get(first.members.get(0)).hull = .42f;
        world.routes.despawnRoute(entry.route);
        pool(world, "a", "indie_bus");
        check(world.routes.spawnRoute(entry.route), "A changed role pool can supply the next generation");
        FakeFleet second = world.created.get(1);
        check(second.members.size() == 1 && !second.members.get(0).getId().equals(original)
                && second.members.get(0).getVariant().getHullVariantId().equals("indie_bus")
                && second.members.get(0).getStatus().getHullFraction() == 1
                && entry.budget.remaining == 3 && entry.checkpoint == null,
                "Regenerated identity, composition and condition respect the reduced fleet budget");
    }

    private static Object field(Object instance, String name) throws Exception {
        java.lang.reflect.Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
    private static void nativeAutomaticAndFreshSectorWait() throws Exception {
        World world = world();
        CivilianTrafficPolicy.Config config = LivingSectorPlugin.settings().civilian;
        config.dailySpawnChance = 1; config.charterEnabled = config.linerEnabled = false;
        config.localReturnChance = 0; config.homeFactionPreference = 1;
        TrafficRegistry.register(new CivilianTrafficPolicy(config));
        world.memory.set(RouteManager.KEY, new RouteManager());
        world.advance(10);
        check(world.traffic.size() == 0 && ((Number) field(world.manager, "snapshotReads")).longValue() == 0,
                "Fresh vanilla manager waits without scanning sector or attempting fleet creation");
        world.memory.set(RouteManager.KEY, world.routes);
        world.advance(5);
        check(world.traffic.size() == 1 && world.created.isEmpty() && !LivingSectorPlugin.settings().useNativeRoutes,
                "Phase A resumes at next pass and always uses native admission despite legacy executor flag");
        TrafficMission mission = world.traffic.active.values().iterator().next().mission;
        check(mission.stops.size() == 2 && mission.plan.typeId.equals(CivilianTrafficPolicy.LOCAL), "Automatic local one-way trip uses subtype policy");
        config.enabled = false;
        long scans = ((Number) field(world.manager, "snapshotReads")).longValue();
        world.advance(5);
        check(((Number) field(world.manager, "snapshotReads")).longValue() == scans, "Disabled civilian policy avoids further planning scans");
    }
    private static void savedCompositionAndLegacyPlan() {
        World world = world();
        TrafficMission mission = world.traffic.start(plan(.65, true, 150), 0, 1, false, false);
        XStream xml = new XStream(new com.thoughtworks.xstream.io.xml.DomDriver("UTF-8"));
        XStream.setupDefaultSecurity(xml);
        xml.allowTypesByWildcard(new String[]{"livingsector.**", "java.util.**"});
        TrafficMission restored = (TrafficMission) xml.fromXML(xml.toXML(mission));
        check(restored.plan.variants.equals(mission.plan.variants) && restored.plan.roundTrip && restored.plan.nativeRoute
                && restored.plan.fleetRequest.passengerCapacity == 150 && restored.stops.size() == 3
                && restored.stops.get(1).dwellDays == .25f, "Saved data retains chosen variants, request and return itinerary");
        TrafficPlan old = new TrafficPlan("vip", "origin", "station", "Old VIP", Arrays.asList("mudskipper_Standard"), 1, 180);
        String legacy = xml.toXML(old).replaceAll("(?s)<(budgetId|fleetRequest|nativeRoute|roundTrip|arrivalDays)(?:\\s[^>]*)?>.*?</\\1>", "");
        TrafficPlan loaded = (TrafficPlan) xml.fromXML(legacy);
        check(!loaded.nativeRoute && !loaded.roundTrip && loaded.fleetRequest == null && loaded.budgetId().equals("vip"),
                "Missing Phase A fields retain legacy executor and type budget");
    }
}
