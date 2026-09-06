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
import livingsector.traffic.TrafficPlan;
import org.lwjgl.util.vector.Vector2f;

/** Headless contract tests against the installed game interfaces, not a campaign simulation. */
public final class JourneyTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Field settings = LivingSectorPlugin.class.getDeclaredField("settings");
        settings.setAccessible(true);
        settings.set(null, new LivingSectorSettings());
        Global.setSettings(proxy(SettingsAPI.class, (method, values) ->
                method.equals("getUnitsPerLightYear") ? 1000f : null));
        EconomyAPI economy = proxy(EconomyAPI.class, (method, values) -> {
            if (!method.equals("getMarket")) return null;
            SectorEntityToken entity = proxy(SectorEntityToken.class, (name, arguments) ->
                    name.equals("getId") ? values[0] : null);
            return proxy(MarketAPI.class, (name, arguments) -> name.equals("getPrimaryEntity") ? entity : null);
        });
        Global.setSector(proxy(SectorAPI.class, (method, values) -> method.equals("getEconomy") ? economy : null));

        SectorSnapshot peace = sector("a", "b", false);
        FakeFleet fleet = new FakeFleet();
        TrafficJourney journey = journey(fleet);
        check(!journey.advance(peace, 1) && fleet.assignment == null, "Safe trip keeps native assignments");
        check(!journey.advance(sector("a", "b", true), 2), "War diverts a live trip");
        check(journey.diverted && journey.destinationId.equals("origin"), "War returns passengers to origin");
        check(fleet.assignment == FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, "Diversion navigates and docks normally");
        int assignments = fleet.assigned;
        journey.advance(sector("a", "b", true), 3);
        check(assignments == fleet.assigned, "Safe diversion is not reset every day");

        fleet = new FakeFleet();
        journey = journey(fleet);
        journey.advance(sector("b", "a", true), 2);
        check(journey.destinationId.equals("destination"), "Hostile conquest of origin finds a safe destination");

        fleet = new FakeFleet();
        journey = journey(fleet);
        SectorSnapshot lostDestination = new SectorSnapshot(Collections.singletonList(port("origin", "a")), enemies(false));
        journey.advance(lostDestination, 2);
        check(journey.destinationId.equals("origin"), "Removed destination triggers return");

        fleet = new FakeFleet();
        fleet.battle = true;
        journey = journey(fleet);
        check(!journey.advance(peace, 200) && fleet.alive, "Timeout never removes a fleet in battle");
        fleet.battle = false;
        fleet.visible = true;
        check(!journey.advance(peace, 201) && fleet.assignment == FleetAssignment.HOLD,
                "Timed-out fleet waits while visible");
        fleet.visible = false;
        check(journey.advance(peace, 202) && !fleet.alive, "Timed-out fleet retires off screen");

        fleet = new FakeFleet();
        journey = journey(fleet);
        check(journey.advance(sector("b", "b", true), 2), "No safe port retires an unseen fleet");
        fleet = new FakeFleet();
        fleet.visible = true;
        journey = journey(fleet);
        check(!journey.advance(sector("b", "b", true), 2) && journey.retiring,
                "No safe port waits while visible");
        journey.advance(peace, 3);
        check(journey.diverted && !journey.retiring, "A waiting fleet can divert when peace returns");

        fleet = new FakeFleet();
        fleet.alive = false;
        check(journey(fleet).advance(peace, 2), "Already-despawned fleets are released");
        fleet = new FakeFleet();
        fleet.empty = true;
        check(journey(fleet).advance(peace, 2), "Destroyed fleets release capacity");
        System.out.println("PASS: " + checks + " journey assertions against installed Starsector API");
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
