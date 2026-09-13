package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import livingsector.LivingSectorPlugin;
import livingsector.model.TrafficMission.State;
import org.lazywizard.console.BaseCommand.CommandContext;
import org.lazywizard.console.BaseCommand.CommandResult;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.overlay.v2.panels.ConsoleOverlayPanel;
import org.lwjgl.util.vector.Vector2f;
import static livingsector.campaign.CampaignFixture.*;
import static livingsector.campaign.IntegrationSuite.check;

/** Exercises production entry points with the installed console, plugin, event bus and Nex code. */
final class EntryPointIntegrationTests {
    static void register(IntegrationSuite suite) {
        suite.add("startup.pluginAndRepeatedLoad", EntryPointIntegrationTests::pluginAndRepeatedLoad);
        suite.add("console.freshSectorRecovery", EntryPointIntegrationTests::freshSectorRecovery);
        suite.add("console.testVisitStatusVerify", EntryPointIntegrationTests::testVisitStatusVerify);
        suite.add("console.contextAndArgumentErrors", EntryPointIntegrationTests::contextAndArgumentErrors);
        suite.add("console.interactionAndBattleGuards", EntryPointIntegrationTests::interactionAndBattleGuards);
        suite.add("console.visitAcrossLocations", EntryPointIntegrationTests::visitAcrossLocations);
        suite.add("console.abstractVisitAndAway", EntryPointIntegrationTests::abstractVisitAndAway);
        suite.add("console.teleportRepairsStaleLocation", EntryPointIntegrationTests::teleportRepairsStaleLocation);
        suite.add("events.battleAndSurvivors.globalFirst", () -> battleAndSurvivors(true));
        suite.add("events.battleAndSurvivors.fleetFirst", () -> battleAndSurvivors(false));
        suite.add("events.despawnOrders", EntryPointIntegrationTests::despawnOrders);
        suite.add("lifecycle.roundTripThroughClock", EntryPointIntegrationTests::roundTripThroughClock);
        suite.add("lifecycle.cancelAfterEndpointRemoved", EntryPointIntegrationTests::cancelAfterEndpointRemoved);
    }

    private static String command(String args, CommandResult expected) {
        ConsoleOverlayPanel.setOutput("");
        CommandResult result = configuredCommand().runCommand(args, CommandContext.CAMPAIGN_MAP);
        check(result == expected, "ls " + args + ": expected " + expected + ", got " + result + "; " + ConsoleOverlayPanel.getOutput());
        return ConsoleOverlayPanel.getOutput();
    }

    private static BaseCommand configuredCommand() {
        try {
            String csv = new String(Files.readAllBytes(Paths.get("data/console/commands.csv")), StandardCharsets.UTF_8);
            org.json.JSONArray commands = org.json.CDL.toJSONArray(csv);
            for (int i = 0; i < commands.length(); i++) {
                org.json.JSONObject row = commands.getJSONObject(i);
                if (row.getString("command").equals("ls")) {
                    return Class.forName(row.getString("class")).asSubclass(BaseCommand.class).getDeclaredConstructor().newInstance();
                }
            }
            throw new AssertionError("No ls command in commands.csv");
        } catch (Exception ex) { throw new AssertionError("Cannot load configured console entry point", ex); }
    }

    private static void pluginAndRepeatedLoad() throws Exception {
        World world = new World();
        world.scripts.clear();
        org.json.JSONObject info = new org.json.JSONObject(new String(Files.readAllBytes(Paths.get("mod_info.json")), StandardCharsets.UTF_8));
        com.fs.starfarer.api.BaseModPlugin plugin = Class.forName(info.getString("modPlugin"))
                .asSubclass(com.fs.starfarer.api.BaseModPlugin.class).getDeclaredConstructor().newInstance();
        plugin.onApplicationLoad(); // Reads the repository's real config and registers the VIP provider.
        plugin.onGameLoad(true);
        TrafficManager installed = TrafficManager.get();
        plugin.onGameLoad(false);
        check(world.scripts.size() == 1 && TrafficManager.get() == installed, "Load hooks must install exactly one manager");
        check(LivingSectorPlugin.settings().vip.includeStations, "Repository settings include stations");
        check(installed.existingNativeTraffic() == null, "Loading a campaign alone does not create native missions");
        check(command("test origin station", CommandResult.SUCCESS).contains("Created ls-1"), "Loaded plugin accepts a command");
        NativeMission entry = installed.existingNativeTraffic().active.get("ls-1");
        world.routes.spawnRoute(entry.route);
        world.reloadHooks();
        world.reloadHooks();
        check(TrafficManager.get() == installed && installed.activeCount() == 1 && entry.mission.generation() == 1,
                "Reload hooks retain the physical mission without generating another fleet");
        ListenerUtil.reportBattleOccurred(null, null, proxy(BattleAPI.class, (m, a) -> null));
    }

    private static void freshSectorRecovery() {
        World world = new World();
        RouteManager vanilla = new RouteManager();
        world.memory.set(RouteManager.KEY, vanilla);
        check(command("test origin station", CommandResult.ERROR).contains("save and load"), "Console explains initial save/load");
        check(world.manager.activeCount() == 0 && RouteManager.getInstance() == vanilla, "Rejected command has no admitted mission");
        world.memory.set(RouteManager.KEY, world.routes);
        check(command("test origin station", CommandResult.SUCCESS).contains("ls-1"), "Same campaign can retry admission");
    }

    private static void testVisitStatusVerify() {
        World world = new World();
        check(command("test", CommandResult.SUCCESS).contains("ls-1"), "Automatic endpoint search creates a mission");
        command("visit ls-1", CommandResult.SUCCESS);
        check(world.created.isEmpty(), "Visiting an abstract route only moves the player");
        command("verify ls-1", CommandResult.SUCCESS);
        NativeMission entry = world.traffic.active.get("ls-1");
        world.routes.spawnRoute(entry.route);
        check(command("status ls-1", CommandResult.SUCCESS).contains("PHYSICAL"), "Status works on a real native binding");
        check(command("verify ls-1", CommandResult.SUCCESS).contains("PASS"), "Nex civilian strength verification succeeds");
        command("lose ls-1", CommandResult.SUCCESS);
        command("damage ls-1", CommandResult.SUCCESS);
        check(world.created.get(0).members.size() == 1, "Loss command removes one test ship");
        command("away ls-1", CommandResult.SUCCESS);
        check(world.player.getContainingLocation() == world.hyperspace, "Away crosses to hyperspace");
        command("pause", CommandResult.SUCCESS);
        world.advance(1);
        check(world.manager.departuresPaused() && world.manager.day() == 1, "Pausing departures allows mission maintenance time to advance");
        command("resume", CommandResult.SUCCESS);
        check(!world.manager.departuresPaused(), "Resume clears override");
    }

    private static void contextAndArgumentErrors() {
        World world = new World();
        BaseCommand command = configuredCommand();
        for (CommandContext context : CommandContext.values()) {
            if (!context.isInCampaign()) check(command.runCommand("test", context) == CommandResult.WRONG_CONTEXT,
                    "Commands reject " + context + " before touching campaign state");
        }
        for (String args : Arrays.asList("test origin", "visit", "status a b", "probe start 1 2", "unknown")) {
            command(args, CommandResult.BAD_SYNTAX);
        }
        command("visit missing", CommandResult.ERROR);
        command("test missing station", CommandResult.ERROR);
        command("probe start banana", CommandResult.ERROR);
        command("help", CommandResult.SUCCESS);
        check(world.manager.activeCount() == 0 && world.created.isEmpty(), "Rejected inputs leave no traffic");
    }

    private static void interactionAndBattleGuards() {
        World world = new World();
        command("checkpoint origin station", CommandResult.SUCCESS);
        NativeMission entry = world.traffic.active.get("ls-1");
        check(entry.mission.plan.boardingDays == 45f, "Checkpoint command retains its long boarding period");
        FakeFleet player = world.fleetObjects.get(world.player);
        player.assigned = FleetAssignment.GO_TO_LOCATION;
        Vector2f before = new Vector2f(player.position);
        for (boolean battle : new boolean[]{false, true}) {
            world.interaction = battle ? null : proxy(InteractionDialogAPI.class, (m, a) -> null);
            player.inBattle = battle;
            command("visit ls-1", CommandResult.ERROR);
            command("away ls-1", CommandResult.ERROR);
            check(player.location == world.system && Global.getSector().getCurrentLocation() == world.system
                            && player.position.equals(before) && player.assigned == FleetAssignment.GO_TO_LOCATION,
                    "Rejected teleport preserves membership, active location, position and assignments");
        }
        world.interaction = null;
        player.inBattle = false;
        command("visit ls-1", CommandResult.SUCCESS);
    }

    private static void visitAcrossLocations() {
        World world = new World();
        NativeMission entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        FakeFleet fleet = world.created.get(0);
        FakeFleet player = world.fleetObjects.get(world.player);
        float elapsed = entry.route.getCurrent().elapsed;
        for (LocationAPI destination : new LocationAPI[]{world.hyperspace, world.system}) {
            fleet.location.removeEntity(fleet.api);
            destination.addEntity(fleet.api); // Arrange a physical shuttle in either location.
            player.assigned = FleetAssignment.GO_TO_LOCATION;
            command("visit ls-1", CommandResult.SUCCESS);
            check(player.location == destination && Global.getSector().getCurrentLocation() == destination,
                    "Visit synchronizes player membership and engine location in " + destination.getName());
            check(player.position.x == fleet.position.x + 500 && player.position.y == fleet.position.y + 500,
                    "Visit places the player beside the physical shuttle");
            check(player.assigned == null, "Teleport clears assignments referring to the previous location");
        }
        check(world.created.size() == 1 && entry.mission.generation() == 1
                        && entry.route.getCurrent().elapsed == elapsed && entry.route.getActiveFleet() == fleet.api,
                "Visiting across locations retains the native fleet, generation and route progress");
    }

    private static void abstractVisitAndAway() {
        World world = new World();
        NativeMission entry = world.start(false);
        command("away ls-1", CommandResult.SUCCESS);
        check(world.player.getContainingLocation() == world.hyperspace
                        && Global.getSector().getCurrentLocation() == world.hyperspace,
                "Away activates hyperspace as well as moving the player there");
        command("visit ls-1", CommandResult.SUCCESS);
        check(world.player.getContainingLocation() == world.system && Global.getSector().getCurrentLocation() == world.system,
                "Visit activates the abstract route's boarding system");
        check(world.created.isEmpty() && entry.mission.generation() == 0 && entry.route.getCurrent().elapsed == 0,
                "Teleporting to/from an abstract route does not force spawning or advance it");
    }

    private static void teleportRepairsStaleLocation() {
        World world = new World();
        world.start(false);
        world.currentLocation = world.hyperspace; // State left by a previous broken teleport.
        command("visit ls-1", CommandResult.SUCCESS);
        check(Global.getSector().getCurrentLocation() == world.system && world.player.getContainingLocation() == world.system,
                "Visit repairs stale engine location even when entity membership is already correct");
        command("away ls-1", CommandResult.SUCCESS);
        world.currentLocation = world.system;
        command("away ls-1", CommandResult.SUCCESS);
        check(Global.getSector().getCurrentLocation() == world.hyperspace && world.player.getContainingLocation() == world.hyperspace,
                "Away also repairs stale engine location without requiring another location crossing");
    }

    private static void battleAndSurvivors(boolean globalFirst) {
        World world = new World();
        NativeMission entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        FakeFleet fleet = world.created.get(0);
        fleet.snapshot.addAll(fleet.members); // Snapshot of members before the battle, as used by Nex.
        fleet.members.remove(1);
        String survivor = fleet.members.get(0).getId();
        world.battle(fleet, fleet.api, globalFirst); // Includes Nex's actual damage callback.
        check(entry.battles == 1 && entry.route.getExtra().damage == .5f, "Native battle loss contributes once in either dispatch order");
        world.routes.despawnRoute(entry.route);
        world.reloadHooks();
        world.routes.spawnRoute(entry.route);
        FakeFleet restored = world.created.get(1);
        check(restored.members.size() == 1 && !survivor.equals(restored.members.get(0).getId()) && entry.budget.remaining == 3, "Battle losses persist as reduced budget through native regeneration");
        check(entry.route.getExtra().damage == .5f && entry.mission.generation() == 2, "Restoration does not apply native damage again");
    }

    private static void despawnOrders() {
        for (boolean globalFirst : new boolean[]{false, true}) {
            World world = new World();
            NativeMission entry = world.start(false);
            world.routes.spawnRoute(entry.route);
            FakeFleet fleet = world.created.get(0);
            if (globalFirst) ListenerUtil.reportFleetDespawnedToListener(fleet.api, FleetDespawnReason.DESTROYED_BY_BATTLE, null);
            fleet.despawn(FleetDespawnReason.DESTROYED_BY_BATTLE, null);
            world.advance(2);
            world.advance(2);
            check(entry.mission.state() == State.DESTROYED && world.manager.activeCount() == 0 && world.traffic.recent.size() == 1,
                    "Duplicate global/direct despawn releases a destroyed mission once");
        }
    }

    private static void roundTripThroughClock() {
        World world = new World();
        command("roundtrip origin station", CommandResult.SUCCESS);
        world.manager.pauseDepartures(true);
        world.paused = true;
        world.advance(10);
        check(world.manager.day() == 0, "Paused campaign does not advance the scheduler");
        world.paused = false;
        for (int day = 0; day < 60; day++) world.advance(1);
        check(world.manager.activeCount() == 0 && world.traffic.recent.size() == 1
                        && world.traffic.recent.get(0).state() == State.COMPLETED,
                "Clock and manager maintenance complete/archive an abstract return trip");
    }

    private static void cancelAfterEndpointRemoved() {
        World world = new World();
        command("test origin station", CommandResult.SUCCESS);
        world.markets.remove("station");
        world.advance(2);
        world.advance(2);
        check(world.manager.activeCount() == 0 && world.traffic.recent.get(0).state() == State.CANCELLED,
                "Live endpoint removal cancels and archives an abstract trip");
    }
}
