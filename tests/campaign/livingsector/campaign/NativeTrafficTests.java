package livingsector.campaign;

import com.fs.starfarer.api.*;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.campaign.listeners.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.*;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import exerelin.campaign.battle.NexWarSimScript;
import exerelin.campaign.fleets.NexRouteManager;
import java.lang.reflect.*;
import java.util.*;
import static livingsector.campaign.CampaignFixture.*;
import livingsector.LivingSectorPlugin;
import livingsector.LivingSectorSettings;
import livingsector.model.TrafficMission;
import livingsector.model.TrafficMission.State;
import livingsector.traffic.TrafficPlan;
import org.lwjgl.util.vector.Vector2f;

/** Runs actual native route timing/listeners and our adapter with fake campaign entities. */
public final class NativeTrafficTests {
    private static int checks;
    static void register(IntegrationSuite suite) {
        suite.add("native.freshSectorRequiresNexManager", NativeTrafficTests::freshSectorRequiresNexManager);
        suite.add("native.globalBattleNotificationsDoNotBelongToMissions", NativeTrafficTests::globalBattleNotificationsDoNotBelongToMissions);
        suite.add("native.abstractRoundTrip", NativeTrafficTests::abstractRoundTrip);
        suite.add("native.physicalCheckpointCycle", NativeTrafficTests::physicalCheckpointCycle);
        suite.add("native.callbackOrderingAndFailures", NativeTrafficTests::callbackOrderingAndFailures);
        suite.add("native.cancellationAndNativeStrength", NativeTrafficTests::cancellationAndNativeStrength);
        suite.add("native.debugTravelIgnoresConsoleOverlay", NativeTrafficTests::debugTravelIgnoresConsoleOverlay);
        suite.add("native.probeDoesNotProjectAcrossTimeJumps", NativeTrafficTests::probeDoesNotProjectAcrossTimeJumps);
    }

    private static void freshSectorRequiresNexManager() {
        World world = new World();
        RouteManager vanilla = new RouteManager();
        RouteData unrelated = vanilla.addRoute("other-mod", world.markets.get("origin"), 1L,
                new RouteManager.OptionalFleetData(), null, null);
        world.memory.set(RouteManager.KEY, vanilla);
        for (int attempt = 0; attempt < 2; attempt++) {
            String message = rejectedStart(world);
            check(message.contains("save and load") && message.contains("No mission was created"),
                    "Fresh-sector rejection explains the save/load prerequisite");
        }
        check(RouteManager.getInstance() == vanilla && vanilla.getRoutesForSource("other-mod").contains(unrelated),
                "Readiness check preserves the installed manager and unrelated routes");
        check(world.traffic.size() == 0 && world.traffic.recent.isEmpty() && world.created.isEmpty()
                        && world.listeners.isEmpty() && vanilla.getRoutesForSource(NativeTraffic.SOURCE).isEmpty(),
                "Repeated rejected starts create no mission, fleet, route, history or listener");

        RouteManager unsupported = new RouteManager() { };
        world.memory.set(RouteManager.KEY, unsupported);
        check(rejectedStart(world).contains(unsupported.getClass().getName()),
                "An unsupported replacement is identified for compatibility diagnosis");
        check(RouteManager.getInstance() == unsupported, "Readiness check never replaces another mod's manager");

        // Simulate the manager becoming available; actual save serialization remains a live test.
        world.memory.set(RouteManager.KEY, world.routes);
        NativeMission started = world.start(false);
        check(started.mission.id.equals("ls-1") && world.traffic.size() == 1,
                "Retry after Nex becomes available starts the first mission without consumed IDs");
    }

    private static String rejectedStart(World world) {
        try {
            world.start(false);
        } catch (IllegalStateException expected) {
            return expected.getMessage();
        }
        throw new AssertionError("Unsupported route manager should reject admission");
    }

    private static void globalBattleNotificationsDoNotBelongToMissions() {
        World world = new World();
        NativeMission entry = world.start(false);
        BattleAPI battle = proxy(BattleAPI.class, (m, a) -> null);
        FakeFleet other = new FakeFleet(world, "unrelated-patrol");
        int events = entry.mission.events().size();
        // CampaignEngine broadcasts this through ListenerUtil with a null fleet, by API contract.
        ListenerUtil.reportBattleOccurred(null, other.api, battle);
        check(entry.mission.active() && entry.battles == 0 && entry.mission.events().size() == events,
                "Global battle broadcast does not crash or change an abstract mission");

        world.routes.spawnRoute(entry.route);
        FakeFleet fleet = world.created.get(0);
        world.traffic.onLoad();
        world.traffic.onLoad();
        events = entry.mission.events().size();
        ListenerUtil.reportBattleOccurred(null, null, battle);
        world.traffic.reportBattleOccurred(other.api, other.api, battle);
        check(entry.battles == 0 && entry.mission.events().size() == events
                        && fleet.id.equals(entry.mission.fleetId()) && entry.route.getExtra().damage == 0f,
                "Global and unrelated battles leave the physical mission, binding and damage unchanged after load");

        // A directly attached fleet listener receives a non-null fleet; those events still matter.
        for (FleetEventListener listener : new ArrayList<FleetEventListener>(fleet.listeners)) {
            if (listener == world.traffic) listener.reportBattleOccurred(fleet.api, null, battle);
        }
        ListenerUtil.reportBattleOccurred(null, fleet.api, battle);
        check(entry.mission.active() && entry.battles == 1 && entry.mission.events().size() == events + 1,
                "The owned fleet's battle is recorded once despite the accompanying global notification");
    }

    private static void abstractRoundTrip() {
        World world = new World();
        NativeMission entry = world.start(true);
        check(entry.route.getSegments().size() == 5, "Return itinerary compiles to boarding, outbound, visit, inbound, docking");
        check(entry.route.getSegments().get(2).from == world.destination, "Station is a valid intermediate target");
        world.routes.advance(2f);
        check(entry.route.getCurrentIndex() == 1 && entry.mission.active(), "Boarding advances without a physical fleet");
        world.routes.advance(20f);
        check(entry.route.getCurrentIndex() == 2 && entry.mission.active(), "Reaching station does not complete return trip");
        world.routes.advance(2f);
        world.routes.advance(20f);
        world.routes.advance(2f);
        check(entry.mission.state() == State.COMPLETED, "Native final segment completes the abstract mission");
        world.traffic.maintain(46);
        check(world.traffic.size() == 0 && world.traffic.recent.size() == 1, "Finished native route releases capacity once");
        world.traffic.reportRouteRemoved(entry.route == null ? world.lastRoute : entry.route);
        world.traffic.maintain(47);
        check(world.traffic.recent.size() == 1, "Duplicate removal cannot duplicate outcome history");
    }

    private static void physicalCheckpointCycle() {
        World world = new World();
        NativeMission entry = world.start(false);
        check(world.routes.spawnRoute(entry.route), "Actual Nex spawn hook creates our physical fleet");
        FakeFleet first = world.created.get(world.created.size() - 1);
        check(first.members.size() == 2 && entry.mission.generation() == 1, "One physical generation with two test ships");
        check(first.listeners.contains(world.routes), "Nex attaches its native fleet listener");
        String survivorId = first.members.get(0).getId();
        FakeShip survivor = world.ships.get(first.members.get(0));
        survivor.hull = .42f;
        survivor.cr = .31f;
        first.members.remove(1);
        // Native battle callbacks can update route damage after our listener sees the battle.
        world.traffic.reportBattleOccurred(first.api, first.api, null);
        entry.route.getExtra().damage = .5f;
        world.routes.despawnRoute(entry.route);
        check(entry.mission.active() && entry.mission.fleetId() == null, "Native distance despawn retains mission and clears physical ID");
        check(entry.checkpoint.ships.size() == 1 && entry.checkpoint.routeDamage == .5f, "Checkpoint imports survivors and already-applied native damage");
        check(world.routes.spawnRoute(entry.route), "Native route can materialize again");
        FakeFleet second = world.created.get(world.created.size() - 1);
        check(second.members.size() == 1 && second.members.get(0).getId().equals(survivorId), "Lost ship stays absent and survivor ID persists");
        check(second.members.get(0).getStatus().getHullFraction() == .42f
                && second.members.get(0).getRepairTracker().getBaseCR() == .31f, "Hull and base CR survive reappearance without healing");
        check(entry.route.getExtra().damage == .5f, "Checkpoint import does not apply native loss twice");
        check(!first.id.equals(second.id) && entry.mission.generation() == 2, "Fleet ID changes while mission identity persists");
        world.traffic.reportRouteFleetDespawned(first.api, entry.route);
        check(second.id.equals(entry.mission.fleetId()), "Late despawn for old fleet cannot clear new binding");
        world.traffic.onLoad();
        world.traffic.onLoad();
        check(Collections.frequency(world.listeners, world.traffic) == 1, "Load hook does not multiply transient route listeners");
        check(Collections.frequency(second.listeners, world.traffic) == 1, "Load hook does not multiply fleet listeners");
    }

    private static void callbackOrderingAndFailures() {
        World world = new World();
        NativeMission entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        FakeFleet fleet = world.created.get(0);
        // A route-removal callback may precede the fleet's terminal reason.
        world.traffic.reportRouteRemoved(entry.route);
        fleet.despawn(FleetDespawnReason.DESTROYED_BY_BATTLE, null);
        world.traffic.maintain(3);
        check(entry.mission.state() == State.DESTROYED && world.traffic.size() == 0, "Destruction wins even when route removal is reported first");
        world.traffic.reportFleetDespawnedToListener(fleet.api, FleetDespawnReason.REACHED_DESTINATION, world.destination);
        check(entry.mission.state() == State.DESTROYED, "Late arrival cannot revive a destroyed mission");

        world = new World(); entry = world.start(false);
        world.failFactory = true;
        check(!world.routes.spawnRoute(entry.route) && entry.route.isExpired(), "A failed factory returns null and Nex expires the route");
        check(entry.mission.state() == State.FAILED, "Spawn failure is retained as a diagnosed outcome");
        world.routes.advance(1);
        world.traffic.maintain(1);
        check(world.traffic.size() == 0 && world.fleets.isEmpty(), "Failed spawn leaves no fleet and releases capacity");

        world = new World(); entry = world.start(true);
        world.routes.spawnRoute(entry.route);
        fleet = world.created.get(0);
        entry.route.setCurrent(entry.route.getSegments().get(2));
        fleet.despawn(FleetDespawnReason.REACHED_DESTINATION, world.destination);
        check(entry.mission.state() == State.FAILED, "Premature intermediate docking despawn is detected, not reported as success");

        world = new World(); entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        fleet = world.created.get(0);
        entry.route.setCurrent(entry.route.getSegments().get(2));
        fleet.despawn(FleetDespawnReason.REACHED_DESTINATION, world.destination);
        check(entry.mission.state() == State.COMPLETED, "Native final docking reports successful physical completion");
    }

    private static void cancellationAndNativeStrength() {
        World world = new World();
        NativeMission entry = world.start(false);
        check(NexWarSimScript.getFactionStrengthReport(world.faction, world.enemy, world.system, false).entries.isEmpty(),
                "Actual Nex strength report excludes our abstract civilian route");
        check(NexWarSimScript.getFactionAndAlliedStrength(world.faction, world.enemy, world.system) == 0,
                "Actual Nex allied-strength query gets zero contribution from abstract civilian route");
        world.routes.spawnRoute(entry.route);
        check(NexWarSimScript.getFactionStrengthReport(world.faction, world.enemy, world.system, false).entries.isEmpty(),
                "Actual Nex strength report excludes both route and physical civilian fleet");
        check(NexWarSimScript.getFactionAndAlliedStrength(world.faction, world.enemy, world.system) == 0,
                "Actual Nex allied-strength query excludes both representations");
        FakeFleet fleet = world.created.get(0);
        fleet.inBattle = true;
        world.traffic.cancel(entry.mission.id);
        world.traffic.maintain(4);
        check(!entry.returning && fleet.alive, "Cancellation never redirects or removes a fleet in battle");
        fleet.inBattle = false;
        world.traffic.maintain(5);
        check(entry.returning && fleet.assigned == FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, "Cancellation gives a normal return assignment");
        fleet.despawn(FleetDespawnReason.REACHED_DESTINATION, world.origin);
        check(entry.mission.state() == State.CANCELLED, "Cancelled return is not counted as delivery");

        world = new World(); entry = world.start(false);
        world.war = true;
        check(!world.routes.spawnRoute(entry.route), "Diplomacy is rechecked at materialization");
        check(entry.mission.state() == State.CANCELLED && world.created.isEmpty(), "War cancels before any fleet is built");
    }

    private static void debugTravelIgnoresConsoleOverlay() {
        World world = new World();
        NativeMission entry = world.start(false);
        Vector2f position = new Vector2f();
        final boolean[] battle = {false};
        final InteractionDialogAPI[] interaction = {null};
        final LocationAPI[] location = {world.system};
        final int[] moves = {0};
        world.ui = proxy(CampaignUIAPI.class, (m, a) -> {
            if (m.equals("isShowingDialog")) return true; // Console's hidden message dialog stays open.
            if (m.equals("getCurrentInteractionDialog")) return interaction[0];
            return null;
        });
        world.hyperspace = proxy(LocationAPI.class, (m, a) -> {
            if (m.equals("addEntity")) location[0] = world.hyperspace;
            return null;
        });
        world.player = proxy(CampaignFleetAPI.class, (m, a) -> {
            switch (m) {
                case "getBattle": return battle[0] ? proxy(BattleAPI.class, (n, v) -> null) : null;
                case "getContainingLocation": return location[0];
                case "getLocation": return position;
                case "setLocation": position.set((Float) a[0], (Float) a[1]); moves[0]++; break;
                default: break;
            }
            return null;
        });
        // Exercise both commands, not just the condition used by their guard.
        String id = entry.mission.id;
        TrafficDebug.visit(id);
        check(position.equals(new Vector2f(3500, 500)), "Visit moves the player near the route with the console overlay open");
        Vector2f routePosition = new Vector2f(entry.route.getInterpolatedHyperLocation());
        TrafficDebug.away(id);
        check(location[0] == world.hyperspace && position.equals(new Vector2f(routePosition.x + 12000, routePosition.y)),
                "Away moves the player 12 LY into hyperspace with the console overlay open");
        for (boolean inBattle : new boolean[]{false, true}) {
            battle[0] = inBattle;
            interaction[0] = inBattle ? null : proxy(InteractionDialogAPI.class, (m, a) -> null);
            for (boolean away : new boolean[]{false, true}) {
                boolean blocked = false;
                int movesBefore = moves[0];
                Vector2f before = new Vector2f(position);
                LocationAPI locationBefore = location[0];
                try {
                    if (away) TrafficDebug.away(id); else TrafficDebug.visit(id);
                } catch (IllegalStateException expected) {
                    blocked = expected.getMessage().contains("interaction/battle");
                }
                check(blocked && moves[0] == movesBefore && position.equals(before) && location[0] == locationBefore,
                        "Debug travel remains blocked without moving the player during " + (inBattle ? "battle" : "an interaction"));
            }
        }
        check(entry.mission.active() && entry.mission.generation() == 0 && world.created.isEmpty(),
                "Debug travel does not spawn a fleet or change mission lifecycle");
    }

    private static void probeDoesNotProjectAcrossTimeJumps() {
        World world = new World();
        NativeMission entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        FakeFleet civilian = world.created.get(0);
        FakeFleet patrol = new FakeFleet(world, "patrol");
        world.fleets.add(patrol.api);
        SectorEntityToken[] target = {civilian.api};
        com.fs.starfarer.api.campaign.ai.TacticalModulePlugin tactical = proxy(
                com.fs.starfarer.api.campaign.ai.TacticalModulePlugin.class,
                (m, a) -> m.equals("getTarget") ? target[0] : null);
        patrol.ai = proxy(com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI.class,
                (m, a) -> m.equals("getTacticalModule") ? tactical : null);
        DistractionProbe probe = new DistractionProbe(world.system, 0, 30);
        probe.advance(0);
        probe.advance(.25);
        target[0] = null;
        probe.advance(.5);
        probe.advance(10);
        check(probe.summary().contains("targeting LS=0.50"), "Probe integrates sampled pursuit intervals without inventing later pursuit");
        check(probe.summary().contains("unsampled gap=9.25d"), "Large time jump is reported as missing coverage");
        probe.stop(10);
        String stopped = probe.summary();
        probe.advance(20);
        check(stopped.equals(probe.summary()), "Stopped diagnostic does no further sampling");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
