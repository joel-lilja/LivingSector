package livingsector.campaign;

import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.thoughtworks.xstream.XStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import livingsector.LivingSectorPlugin;
import livingsector.debug.RecorderReport;
import livingsector.debug.RotatingLog;
import livingsector.model.RecorderState;
import livingsector.traffic.*;
import org.json.JSONObject;
import org.lazywizard.console.BaseCommand.CommandContext;
import org.lazywizard.console.BaseCommand.CommandResult;
import org.lazywizard.console.overlay.v2.panels.ConsoleOverlayPanel;
import static livingsector.campaign.CampaignFixture.*;
import static livingsector.campaign.IntegrationSuite.check;

final class RecorderIntegrationTests {
    static void register(IntegrationSuite suite) {
        suite.add("recorder.settingsWithoutReflection", RecorderIntegrationTests::settingsWithoutReflection);
        suite.add("recorder.offHasNoIO", RecorderIntegrationTests::offHasNoIO);
        suite.add("recorder.nativeLifecycleAndStop", RecorderIntegrationTests::nativeLifecycleAndStop);
        suite.add("recorder.directArrival", () -> directOutcome(FleetDespawnReason.REACHED_DESTINATION, "COMPLETED"));
        suite.add("recorder.directDestruction", () -> directOutcome(FleetDespawnReason.DESTROYED_BY_BATTLE, "DESTROYED"));
        suite.add("recorder.directDiversion", RecorderIntegrationTests::directDiversion);
        suite.add("recorder.saveRollbackBranches", RecorderIntegrationTests::saveRollbackBranches);
        suite.add("recorder.oldSaveBaselineAndFailure", RecorderIntegrationTests::oldSaveBaselineAndFailure);
        suite.add("recorder.periodQueriesAndGaps", RecorderIntegrationTests::periodQueriesAndGaps);
    }
    private static void settingsWithoutReflection() throws Exception {
        new World();
        livingsector.LivingSectorSettings settings = LivingSectorPlugin.settings();
        settings.debugHistoryMiB = 16;
        settings.planningIntervalDays = 7;
        settings.vip.dailySpawnChance = .17;
        settings.vip.variant = "custom_shuttle_Standard";
        // Model the live script loader's refusal to resolve reflection classes for mod code.
        // The harness itself still uses reflection to invoke the private snapshot and verify its data.
        String target = "livingsector.campaign.TrafficRecorder";
        java.net.URL jar = TrafficRecorder.class.getProtectionDomain().getCodeSource().getLocation();
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{jar},
                TrafficRecorder.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("java.lang.reflect.")) {
                    throw new SecurityException("File access and reflection are not allowed to scripts. (" + name + ")");
                }
                if (!name.equals(target)) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }) {
            java.lang.reflect.Method snapshot = loader.loadClass(target).getDeclaredMethod("settingsSnapshot");
            snapshot.setAccessible(true);
            JSONObject data = (JSONObject) snapshot.invoke(null);
            checkSnapshot(settings, data);
            checkSnapshot(settings.vip, data.getJSONObject("vip"));
            checkSnapshot(settings.civilian, data.getJSONObject("civilian"));
        }
    }
    private static void checkSnapshot(Object settings, JSONObject data) throws Exception {
        for (Field field : settings.getClass().getFields()) {
            if (field.getName().equals("vip") || field.getName().equals("civilian")) continue;
            Object expected = field.get(settings), actual = data.get(field.getName());
            if (expected instanceof Number) {
                check(Math.abs(((Number) expected).doubleValue() - ((Number) actual).doubleValue()) < .000001,
                        "Recorder retains effective numeric setting " + field.getName());
            } else check(expected.equals(actual), "Recorder retains effective setting " + field.getName());
        }
    }
    static String command(String args) {
        ConsoleOverlayPanel.setOutput("");
        CommandResult result = new livingsector.console.LivingSectorCommand().runCommand(args, CommandContext.CAMPAIGN_MAP);
        check(result == CommandResult.SUCCESS, "Command succeeds: ls " + args + "; " + ConsoleOverlayPanel.getOutput());
        return ConsoleOverlayPanel.getOutput();
    }
    static List<JSONObject> events(World world) throws Exception {
        List<JSONObject> result = new ArrayList<JSONObject>();
        for (String value : world.commonFiles.values()) for (String line : value.split("\n")) {
            JSONObject event = new JSONObject(line);
            if (event.has("kind")) result.add(event);
        }
        return result;
    }
    static long count(World world, String kind) throws Exception {
        return events(world).stream().filter(e -> kind.equals(e.optString("kind"))).count();
    }
    static void forceDirectPolicy() {
        TrafficRegistry.register(new TrafficPolicy() {
            public String getId() { return "recorder_test"; }
            public TrafficBudget budget(livingsector.model.SectorSnapshot sector) { return new TrafficBudget(10, 0, 25, 1, 1, 0); }
            public TrafficPlan plan(TrafficContext context, Random random) {
                return new TrafficPlan(getId(), "origin", "station", "Recorded traffic", Arrays.asList("mudskipper_Standard"), .5f, 180);
            }
        });
    }
    private static void offHasNoIO() throws Exception {
        World world = new World();
        world.manager.onLoad();
        NativeMission nativeTrip = world.start(false);
        world.routes.spawnRoute(nativeTrip.route);
        world.routes.despawnRoute(nativeTrip.route);
        forceDirectPolicy();
        world.advance(6);
        for (int i = 0; i < 1000; i++) world.advance(.00001f);
        new LivingSectorPlugin().beforeGameSave();
        new LivingSectorPlugin().afterGameSave();
        command("debug status");
        check(world.recorderReads == 0 && world.recorderWrites == 0 && world.recorderDeletes == 0,
                "Disabled recording performs zero filesystem calls during traffic, frames, save hooks and status");
        check(world.manager.recorder() == null && !nativeTrip.mission.hasEventSink(), "No recorder or mission sink exists while off");
    }
    private static void nativeLifecycleAndStop() throws Exception {
        World world = new World();
        world.manager.onLoad();
        command("debug on");
        NativeMission entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        FakeFleet fleet = world.created.get(0);
        world.battle(fleet, fleet.api, true);
        world.routes.despawnRoute(entry.route);
        world.routes.spawnRoute(entry.route);
        world.routes.despawnRoute(entry.route);
        for (int i = 0; i < 60; i++) world.advance(1);
        command("debug flush");
        check(count(world, "CREATED") == 1 && count(world, "MATERIALIZED") == 2 && count(world, "DEMATERIALIZED") == 2,
                "Native recording follows identity across physical generations without duplicate spawn events");
        check(count(world, "BATTLE") == 1 && count(world, "BUDGET_CAPTURED") == 2 && count(world, "COMPLETED") == 1,
                "Battle, budget captures and offscreen completion survive mission archival: " + command("debug summary all"));
        check(command("debug summary 180").contains("COMPLETED=1"), "Completed trip remains queryable after leaving active history");
        command("debug off");
        int reads = world.recorderReads, writes = world.recorderWrites, deletes = world.recorderDeletes;
        NativeMission unrecorded = world.start(false);
        world.routes.spawnRoute(unrecorded.route);
        world.advance(1);
        new LivingSectorPlugin().beforeGameSave();
        check(reads == world.recorderReads && writes == world.recorderWrites && deletes == world.recorderDeletes,
                "After the explicit final stop flush there is no further recorder I/O");
    }
    private static void directOutcome(FleetDespawnReason reason, String kind) throws Exception {
        World world = new World();
        world.manager.onLoad(); command("debug on"); forceDirectPolicy(); world.advance(6);
        FakeFleet fleet = world.created.get(0);
        fleet.despawn(reason, world.destination);
        fleet.despawn(reason, world.destination); // Duplicate/late event must not duplicate the outcome.
        world.manager.pauseDepartures(true);
        world.advance(3); command("debug flush");
        check(count(world, "CREATED") == 1 && count(world, kind) == 1 && count(world, "UNKNOWN") == 0,
                "Direct traffic records exactly one known outcome and releases its listener");
        check(fleet.listeners.stream().noneMatch(l -> l instanceof TrafficJourney), "Finished direct trip no longer holds recorder listener");
    }
    private static void directDiversion() throws Exception {
        World world = new World();
        world.manager.onLoad(); command("debug on"); forceDirectPolicy(); world.advance(6);
        world.markets.remove("station"); world.advance(3);
        world.created.get(0).despawn(FleetDespawnReason.REACHED_DESTINATION, world.origin);
        command("debug flush");
        check(count(world, "DIVERTED") == 1 && count(world, "CANCELLED") == 1 && count(world, "COMPLETED") == 0,
                "Diversion docking is not reported as successful original delivery");
    }
    private static Field cursorField() throws Exception {
        Field field = TrafficManager.class.getDeclaredField("recorderState"); field.setAccessible(true); return field;
    }
    private static XStream serializer() {
        XStream x = new XStream(new com.thoughtworks.xstream.io.xml.DomDriver("UTF-8"));
        XStream.setupDefaultSecurity(x); x.allowTypesByWildcard(new String[]{"livingsector.**", "java.util.**"}); return x;
    }
    private static void saveRollbackBranches() throws Exception {
        World world = new World();
        LivingSectorPlugin.settings().debugTrafficHistory = true;
        world.manager.onLoad();
        NativeMission trip = world.start(false);
        LivingSectorPlugin plugin = new LivingSectorPlugin();
        plugin.beforeGameSave();
        RecorderState saved = (RecorderState) serializer().fromXML(serializer().toXML(cursorField().get(world.manager)));
        String oldRun = saved.runId;
        long savedSequence = saved.sequence;
        check(!serializer().toXML(trip.mission).contains("TrafficRecorder"), "Mission serialization omits its recorder sink");
        plugin.afterGameSave();
        trip.mission.note("abandoned future"); command("debug flush");
        cursorField().set(world.manager, saved); // Restore the cursor actually serialized by the save.
        world.manager.onLoad();
        command("debug flush");
        JSONObject boundary = events(world).stream().filter(e -> "RUN_START".equals(e.optString("kind"))
                && !oldRun.equals(e.optString("run"))).findFirst().orElseThrow(() -> new AssertionError("Missing new branch"));
        check(boundary.getString("parentRun").equals(oldRun) && boundary.getLong("parentSequence") == savedSequence,
                "New branch points to the saved cursor, not the discarded future tail");
        check(!command("debug summary all").contains("abandoned future"), "Default summary excludes abandoned future events");
        check(command("debug summary all " + oldRun).contains("abandoned future"), "Old branch remains separately inspectable");
        check(count(world, "CREATED") == 1 && count(world, "OBSERVED_EXISTING") == 1, "Load baseline never invents a second departure");
    }
    private static void oldSaveBaselineAndFailure() throws Exception {
        World world = new World();
        NativeMission existing = world.start(false);
        LivingSectorPlugin.settings().debugTrafficHistory = true;
        world.manager.onLoad(); command("debug flush");
        check(count(world, "CREATED") == 0 && count(world, "OBSERVED_EXISTING") == 1, "Old saves start with explicitly partial observations");
        world.failRecorderWrites = true;
        existing.mission.note("will fail on flush");
        String status = command("debug flush");
        check(status.contains("ERROR="), "Recorder disk failure is visible to the user");
        int writes = world.recorderWrites, reads = world.recorderReads;
        for (int i = 0; i < 1000; i++) { existing.mission.note("after failure"); world.advance(.00001f); }
        check(world.recorderWrites == writes && world.recorderReads == reads && existing.mission.active(),
                "Failed recorder stops I/O and gameplay continues");
        world.failRecorderWrites = false;
        check(command("debug on").contains("ON"), "Explicit enable retries a recovered disk");
    }
    private static void periodQueriesAndGaps() throws Exception {
        RotatingLogTests.MemoryStore store = new RotatingLogTests.MemoryStore();
        RotatingLog log = new RotatingLog(store, 32);
        log.append("{\"campaign\":\"c\",\"run\":\"r\",\"seq\":1,\"day\":0,\"kind\":\"RUN_START\",\"parentRun\":null}");
        log.append("{\"campaign\":\"c\",\"run\":\"r\",\"seq\":2,\"day\":10,\"kind\":\"CREATED\",\"trip\":\"ls-1\"}");
        log.append("{\"campaign\":\"c\",\"run\":\"r\",\"seq\":4,\"day\":200,\"kind\":\"COMPLETED\",\"trip\":\"ls-1\"}");
        log.flush();
        RecorderState cursor = new RecorderState(); cursor.campaignId = "c"; cursor.runId = "r"; cursor.sequence = 4;
        String recent = RecorderReport.report(log, cursor, 250, "summary", "100", null);
        check(recent.contains("COMPLETED=1") && !recent.contains("CREATED=1") && recent.contains("mean days=190.00"),
                "Period filters outcomes by event date while retaining duration evidence from earlier creation");
        check(recent.contains("missing sequence/ancestry sections=1"), "Missing events are disclosed");
        check(RecorderReport.report(log, cursor, 250, "summary", "all", null).contains("CREATED=1"), "All-time query includes retained earlier creation");
    }
}
