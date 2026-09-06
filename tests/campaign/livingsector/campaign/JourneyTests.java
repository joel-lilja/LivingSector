package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import java.lang.reflect.*;
import java.util.*;
import livingsector.LivingSectorPlugin;
import livingsector.LivingSectorSettings;
import livingsector.model.SectorSnapshot;
import livingsector.model.SectorSnapshot.Port;
import livingsector.traffic.*;
import org.lwjgl.util.vector.Vector2f;

/** Headless contract tests against the installed game interfaces, not a campaign simulation. */
public final class JourneyTests {
    private static int checks, fallbackReads, marketScans;
    private static boolean paused;
    private static SectorSnapshot observed;

    public static void main(String[] args) throws Exception {
        Field settings = LivingSectorPlugin.class.getDeclaredField("settings");
        settings.setAccessible(true);
        settings.set(null, new LivingSectorSettings());
        Global.setSettings(proxy(SettingsAPI.class, (method, values) ->
                method.equals("getUnitsPerLightYear") ? 1000f : null));
        EconomyAPI economy = proxy(EconomyAPI.class, (method, values) -> {
            if (method.equals("getMarketsCopy")) { marketScans++; return Collections.emptyList(); }
            return method.equals("getMarket") ? market(observed.port((String) values[0])) : null;
        });
        CampaignClockAPI clock = proxy(CampaignClockAPI.class, (method, values) ->
                method.equals("convertToDays") ? values[0] : null);
        Global.setSector(proxy(SectorAPI.class, (method, values) -> {
            if (method.equals("getEconomy")) return economy;
            if (method.equals("getFaction")) return faction((String) values[0]);
            if (method.equals("getClock")) return clock;
            if (method.equals("isPaused")) return paused;
            return null;
        }));

        SectorSnapshot peace = sector("a", "b", false);
        FakeFleet fleet = new FakeFleet();
        TrafficJourney journey = journey(fleet);
        check(!advance(journey, peace, 1) && fleet.assignment == null, "Safe trip keeps native assignments");
        check(!advance(journey, sector("a", "b", true), 2), "War diverts a live trip");
        check(journey.diverted && journey.destinationId.equals("origin"), "War returns passengers to origin");
        check(fleet.assignment == FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, "Diversion navigates and docks normally");
        check(fallbackReads == 0, "Safe routes and return-to-origin diversions do not scan the sector");
        int assignments = fleet.assigned;
        advance(journey, sector("a", "b", true), 3);
        check(assignments == fleet.assigned, "Safe diversion is not reset every day");

        fleet = new FakeFleet();
        journey = journey(fleet);
        advance(journey, sector("b", "a", true), 2);
        check(journey.destinationId.equals("destination"), "Hostile conquest of origin finds a safe destination");

        fleet = new FakeFleet();
        journey = journey(fleet);
        SectorSnapshot lostDestination = new SectorSnapshot(Collections.singletonList(port("origin", "a")), enemies(false));
        advance(journey, lostDestination, 2);
        check(journey.destinationId.equals("origin"), "Removed destination triggers return");

        fleet = new FakeFleet();
        fleet.battle = true;
        journey = journey(fleet);
        check(!advance(journey, peace, 200) && fleet.alive, "Timeout never removes a fleet in battle");
        fleet.battle = false;
        fleet.visible = true;
        check(!advance(journey, peace, 201) && fleet.assignment == FleetAssignment.HOLD,
                "Timed-out fleet waits while visible");
        fleet.visible = false;
        check(advance(journey, peace, 202) && !fleet.alive, "Timed-out fleet retires off screen");

        fleet = new FakeFleet();
        journey = journey(fleet);
        check(advance(journey, sector("b", "b", true), 2), "No safe port retires an unseen fleet");
        fleet = new FakeFleet();
        fleet.visible = true;
        journey = journey(fleet);
        check(!advance(journey, sector("b", "b", true), 2) && journey.retiring,
                "No safe port waits while visible");
        advance(journey, peace, 3);
        check(journey.diverted && !journey.retiring, "A waiting fleet can divert when peace returns");

        fleet = new FakeFleet();
        fleet.alive = false;
        check(advance(journey(fleet), peace, 2), "Already-despawned fleets are released");
        fleet = new FakeFleet();
        fleet.empty = true;
        check(advance(journey(fleet), peace, 2), "Destroyed fleets release capacity");
        managerCadenceTests(settings);
        System.out.println("PASS: " + checks + " journey assertions against installed Starsector API");
    }

    private static boolean advance(TrafficJourney journey, SectorSnapshot snapshot, double day) {
        observed = snapshot;
        return journey.advance(day, () -> { fallbackReads++; return snapshot; });
    }

    private static FactionAPI faction(String id) {
        return proxy(FactionAPI.class, (method, values) -> {
            if (method.equals("getId")) return id;
            if (method.equals("isHostileTo")) return !observed.peaceful(id, (String) values[0]);
            return null;
        });
    }

    private static MarketAPI market(Port port) {
        if (port == null) return null;
        SectorEntityToken entity = proxy(SectorEntityToken.class, (method, values) -> {
            if (method.equals("getId")) return port.id;
            if (method.equals("isAlive")) return true;
            return null;
        });
        StarSystemAPI system = proxy(StarSystemAPI.class, (method, values) ->
                method.equals("getJumpPoints") ? Collections.singletonList(entity) : null);
        return proxy(MarketAPI.class, (method, values) -> {
            switch (method) {
                case "getId": return port.id;
                case "getName": return port.name;
                case "getFactionId": return port.factionId;
                case "getSize": return port.size;
                case "getPrimaryEntity": return entity;
                case "getStarSystem": return system;
                case "isInEconomy": return true;
                default: return null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void managerCadenceTests(Field settingsField) throws Exception {
        TrafficRegistry.register(new TrafficPolicy() {
            public String getId() { return "test_idle"; }
            public TrafficBudget budget(SectorSnapshot snapshot) { return new TrafficBudget(0, 0, 25, 0, 10, 0); }
            public TrafficPlan plan(TrafficContext context, Random random) { throw new AssertionError("Zero budget"); }
        });
        LivingSectorSettings config = new LivingSectorSettings();
        settingsField.set(null, config);
        observed = sector("a", "b", false);
        TrafficManager manager = new TrafficManager();
        marketScans = 0;
        for (int day = 0; day < 30; day++) manager.advance(1);
        check(marketScans == 6, "Thirty campaign days perform six planning scans instead of thirty");
        paused = true;
        manager.advance(1000);
        check(marketScans == 6, "Paused time does no planning work");
        paused = false;
        manager.advance(1000);
        check(marketScans == 7, "A large time step performs one pass without catch-up scans");

        config.enabled = false;
        manager = new TrafficManager();
        marketScans = 0;
        for (int day = 0; day < 30; day++) manager.advance(1);
        check(marketScans == 0, "Disabled idle mod never scans markets");
        Field journeysField = TrafficManager.class.getDeclaredField("journeys");
        journeysField.setAccessible(true);
        FakeFleet live = new FakeFleet();
        ((List<TrafficJourney>) journeysField.get(manager)).add(journey(live));
        int before = fallbackReads;
        for (int day = 0; day < 30; day++) manager.advance(1);
        check(marketScans == 0 && fallbackReads == before && live.alive,
                "Disabled spawning still maintains healthy fleets without sector scans");
        Field maintenance = TrafficManager.class.getDeclaredField("maintenancePasses");
        maintenance.setAccessible(true);
        check(maintenance.getLong(manager) == 15, "Maintenance runs on its independent two-day cadence");

        config.enabled = true;
        config.globalFleetLimit = 1;
        manager = new TrafficManager();
        ((List<TrafficJourney>) journeysField.get(manager)).add(journey(new FakeFleet()));
        marketScans = 0;
        for (int day = 0; day < 30; day++) manager.advance(1);
        check(marketScans == 0, "A full fleet cap skips planning scans while maintaining live routes");

        config.planningIntervalDays = 10;
        config.maintenanceIntervalDays = 3;
        manager = new TrafficManager();
        marketScans = 0;
        for (int day = 0; day < 30; day++) manager.advance(1);
        check(marketScans == 3, "Custom planning interval is respected");
        Field initialized = TrafficManager.class.getDeclaredField("cadenceInitialized");
        initialized.setAccessible(true);
        initialized.setBoolean(manager, false); // Transient state after load; old saves also default false.
        Field oldDeadline = TrafficManager.class.getDeclaredField("nextTick");
        oldDeadline.setAccessible(true);
        oldDeadline.setDouble(manager, 1);
        manager.advance(1);
        check(marketScans == 3, "Reload rebases an old daily deadline instead of scanning immediately");
        manager.advance(9);
        check(marketScans == 4, "Reloaded manager resumes the configured cadence");

        config.globalFleetLimit = 40;
        config.planningIntervalDays = 5;
        config.maintenanceIntervalDays = 5;
        observed = sector("b", "b", true);
        manager = new TrafficManager();
        for (int i = 0; i < 2; i++) {
            FakeFleet stranded = new FakeFleet();
            stranded.visible = true;
            ((List<TrafficJourney>) journeysField.get(manager)).add(journey(stranded));
        }
        marketScans = 0;
        manager.advance(5);
        check(marketScans == 1, "Multiple fallback searches and planning share one snapshot per update");
        settingsField.set(null, new LivingSectorSettings());
    }

    private static TrafficJourney journey(FakeFleet fleet) {
        return new TrafficJourney(fleet.api, new TrafficPlan("vip", "origin", "destination", "VIP Shuttle",
                Collections.singletonList("mudskipper_Standard"), .5f, 180), 0);
    }

    private static SectorSnapshot sector(String originFaction, String destinationFaction, boolean war) {
        return new SectorSnapshot(Arrays.asList(port("origin", originFaction), port("destination", destinationFaction)), enemies(war));
    }

    private static Map<String, Set<String>> enemies(boolean war) {
        Map<String, Set<String>> map = new HashMap<String, Set<String>>();
        if (war) map.put("b", Collections.singleton("a"));
        return map;
    }

    private static Port port(String id, String faction) {
        return new Port(id, id, faction, "system", 5, 5, 0, 0, true, false);
    }

    private interface Answer { Object get(String method, Object[] args); }

    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object value = answer.get(method.getName(), args);
            if (value != null) return value;
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) return false;
            if (returns == int.class) return 0;
            if (returns == float.class) return 0f;
            if (returns == double.class) return 0d;
            if (returns == long.class) return 0L;
            return null;
        }));
    }

    private static final class FakeFleet {
        boolean alive = true, visible, empty, battle;
        int assigned;
        FleetAssignment assignment;
        final FactionAPI faction = proxy(FactionAPI.class, (method, args) -> method.equals("getId") ? "a" : null);
        final MemoryAPI memory = proxy(MemoryAPI.class, (method, args) -> null);
        final CampaignFleetAPI api = proxy(CampaignFleetAPI.class, (method, args) -> {
            switch (method) {
                case "isAlive": return alive;
                case "isEmpty": return empty;
                case "isVisibleToPlayerFleet": return visible;
                case "getBattle": return battle ? proxy(BattleAPI.class, (name, values) -> null) : null;
                case "getFaction": return faction;
                case "getLocationInHyperspace": return new Vector2f();
                case "getMemoryWithoutUpdate": return memory;
                case "despawn": alive = false; break;
                case "clearAssignments": assignment = null; break;
                case "addAssignment": assignment = (FleetAssignment) args[0]; assigned++; break;
                default: break;
            }
            return null;
        });
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
