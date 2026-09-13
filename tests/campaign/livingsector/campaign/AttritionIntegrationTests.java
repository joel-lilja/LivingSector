package livingsector.campaign;

import com.thoughtworks.xstream.XStream;
import livingsector.model.FleetBudget;
import livingsector.model.TrafficMission;
import livingsector.model.TrafficMission.State;
import livingsector.traffic.TrafficPlan;
import java.util.*;
import static livingsector.campaign.CampaignFixture.*;
import static livingsector.campaign.IntegrationSuite.check;

final class AttritionIntegrationTests {
    static void register(IntegrationSuite suite) {
        suite.add("budget.beforeFirstMaterialization", AttritionIntegrationTests::beforeFirstMaterialization);
        suite.add("budget.regenerationAndRepeatedDamage", AttritionIntegrationTests::regenerationAndRepeatedDamage);
        suite.add("budget.physicalLossesAndNativeCallbackOrder", AttritionIntegrationTests::physicalLossesAndNativeCallbackOrder);
        suite.add("budget.totalLossWithoutMaterialization", AttritionIntegrationTests::totalLossWithoutMaterialization);
        suite.add("budget.savedWatermarkAndLegacyMigration", AttritionIntegrationTests::savedWatermarkAndLegacyMigration);
        suite.add("budget.physicalLegacyMigration", AttritionIntegrationTests::physicalLegacyMigration);
        suite.add("budget.generationRoundingAndRecorder", AttritionIntegrationTests::generationRoundingAndRecorder);
        suite.add("budget.savedPhysicalBaseline", AttritionIntegrationTests::savedPhysicalBaseline);
        suite.add("budget.emptyPhysicalArrival", AttritionIntegrationTests::emptyPhysicalArrival);
        suite.add("scaling.churnReleasesBindings", AttritionIntegrationTests::churnReleasesBindings);
        suite.add("scaling.admissionListenersAndMaintenanceMembership", AttritionIntegrationTests::admissionListenersAndMaintenanceMembership);
    }
    private static void beforeFirstMaterialization() {
        World world = new World(); NativeMission entry = world.start(false);
        entry.route.getExtra().damage = .5f;
        check(world.routes.spawnRoute(entry.route), "First generation accepts prior aggregate damage");
        FakeFleet fleet = world.created.get(0);
        check(fleet.members.size() == 1 && entry.budget.remaining == 3 && fleet.members.get(0).getStatus().getHullFraction() == 1,
                "First generation fits reduced budget with normal condition");
        check(entry.route.getExtra().fp == 6 && entry.route.getExtra().damage == .5f, "Native budget baseline and damage are preserved");
        world = new World(); entry = world.start(false); entry.route.getExtra().damage = .75f;
        check(!world.routes.spawnRoute(entry.route) && world.created.isEmpty() && entry.mission.state() == State.CANCELLED
                && entry.mission.outcome().contains("fits remaining budget"), "Insufficient positive budget retires explicitly without oversized minimum ship");
    }
    private static void regenerationAndRepeatedDamage() {
        World world = new World(); NativeMission entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        FakeFleet first = world.created.get(0);
        String oldId = first.members.get(0).getId();
        world.ships.get(first.members.get(0)).cr = .31f;
        first.cargo.quantities.put("fuel", 100f);
        world.routes.despawnRoute(entry.route);
        check(entry.checkpoint == null, "No rich checkpoint is retained");
        entry.route.getExtra().damage = .5f;
        world.traffic.maintain(1);
        check(entry.budget.remaining == 3, "Scheduled abstract maintenance applies budget loss without fleet construction");
        int events = entry.mission.events().size();
        world.traffic.maintain(2);
        check(entry.mission.events().size() == events, "Unchanged damage emits no duplicate loss");
        entry.route.getExtra().damage = .25f;
        for (int i = 0; i < 4; i++) {
            check(world.routes.spawnRoute(entry.route), "Reduced budget can rematerialize repeatedly");
            FakeFleet next = world.created.get(world.created.size() - 1);
            check(next.members.size() == 1 && !next.members.get(0).getId().equals(oldId)
                    && next.members.get(0).getRepairTracker().getBaseCR() == .7f && entry.budget.remaining == 3,
                    "Identity/condition regenerate, repeated transitions preserve allowance");
            check(next.cargo.quantities.get("fuel") != 100f, "Cargo uses normal generation defaults");
            world.routes.despawnRoute(entry.route);
            entry.route.getExtra().damage = .5f;
        }
        check(entry.budget.routeDamage == .5f, "Lowered counter never erases the watermark");
    }
    private static void physicalLossesAndNativeCallbackOrder() {
        for (boolean globalFirst : new boolean[]{false, true}) for (boolean nexFirst : new boolean[]{false, true}) {
            World world = new World();
            TrafficPlan plan = new TrafficPlan("vip", "origin", "station", "Four ships",
                    Collections.nCopies(4, "mudskipper_Standard"), 1, 180);
            TrafficMission mission = world.traffic.start(plan, 0, 1, false, true);
            NativeMission entry = world.traffic.active.get(mission.id);
            world.routes.spawnRoute(entry.route); FakeFleet first = world.created.get(0);
            if (nexFirst) Collections.reverse(first.listeners);
            for (int battle = 0; battle < 2; battle++) {
                first.snapshot.clear(); first.snapshot.addAll(first.members); first.members.remove(0);
                world.battle(first, first.api, globalFirst);
            }
            check(entry.route.getExtra().damage == .5f, "Repeated real Nex callbacks use the original FP denominator");
            world.routes.despawnRoute(entry.route);
            check(entry.budget.remaining == 6 && entry.budget.routeDamage == .5f, "Physical losses are compressed once after all callbacks");
            world.reloadHooks();
            check(world.routes.spawnRoute(entry.route), "Regeneration after load hooks retains budget");
            FakeFleet next = world.created.get(1);
            next.snapshot.addAll(next.members); next.members.remove(0);
            if (nexFirst) Collections.reverse(next.listeners);
            world.battle(next, next.api, globalFirst);
            check(entry.route.getExtra().damage == .75f, "Nex cumulative loss remains scaled correctly across generations");
            world.routes.despawnRoute(entry.route);
            check(entry.budget.remaining == 3 && entry.budget.routeDamage == .75f, "No double charge from physical and native reports");
            entry.route.getExtra().damage = .875f;
            world.traffic.maintain(3);
            check(entry.budget.remaining == 1.5f, "Only additional abstract half-of-remaining damage is charged");
        }
    }
    private static void totalLossWithoutMaterialization() {
        for (boolean physicalBefore : new boolean[]{false, true}) {
            World world = new World(); world.manager.debugCommand("on", "all", null);
            NativeMission entry = world.start(false);
            if (physicalBefore) { world.routes.spawnRoute(entry.route); world.routes.despawnRoute(entry.route); }
            int built = world.created.size();
            entry.route.getExtra().damage = 1.5f;
            entry.route.setCurrent(entry.route.getSegments().get(entry.route.getSegments().size() - 1));
            world.routes.advance(2);
            check(entry.mission.state() == State.DESTROYED && world.created.size() == built,
                    "Total abstract damage ends as destruction without materializing or delivering");
            world.traffic.maintain(2); world.traffic.maintain(3);
            check(world.traffic.size() == 0 && world.traffic.recent.size() == 1 && entry.budget == null,
                    "Terminal cleanup releases budget, route and capacity once");
            String report = world.manager.debugCommand("trip", "all", entry.mission.id);
            check(report.contains("ABSTRACT_DAMAGE") && report.contains("DESTROYED"), "Recorder retains loss and terminal outcome");
        }
        World world = new World(); NativeMission entry = world.start(false); entry.route.getExtra().damage = 1f;
        check(!world.routes.spawnRoute(entry.route) && world.created.isEmpty() && entry.mission.state() == State.DESTROYED,
                "Spawn boundary rejects total loss");
        world = new World(); entry = world.start(false); entry.route.getExtra().damage = 1f;
        world.traffic.maintain(2);
        check(world.traffic.size() == 0 && entry.mission.state() == State.DESTROYED, "Maintenance retires total loss");
    }
    private static XStream serializer() {
        XStream xml = new XStream(new com.thoughtworks.xstream.io.xml.DomDriver("UTF-8"));
        XStream.setupDefaultSecurity(xml); xml.allowTypesByWildcard(new String[]{"livingsector.**", "java.util.**"});
        return xml;
    }
    private static FleetCheckpoint legacy(int ships, float damage) {
        FleetCheckpoint result = new FleetCheckpoint(); result.routeDamage = damage;
        for (int i = 0; i < ships; i++) {
            FleetCheckpoint.Ship ship = new FleetCheckpoint.Ship(); ship.id = "old-" + i; ship.hull = .42f; ship.cr = .31f; ship.fleetPoints = 3;
            result.ships.add(ship);
        }
        return result;
    }
    private static void savedWatermarkAndLegacyMigration() {
        World world = new World(); NativeMission entry = world.start(false);
        entry.budget = null; entry.checkpoint = legacy(1, .5f); entry.route.getExtra().damage = .5f;
        XStream xml = serializer(); xml.omitField(NativeMission.class, "route");
        String oldData = xml.toXML(entry);
        check(!oldData.contains("<budget>"), "Old binding has no aggregate field");
        NativeMission restored = (NativeMission) xml.fromXML(oldData);
        restored.route = entry.route; restored.route.setCustom(restored);
        world.traffic.active.put(restored.mission.id, restored);
        world.traffic.onLoad();
        check(restored.budget.remaining == 3 && restored.budget.routeDamage == .5f && restored.checkpoint == null,
                "Legacy data migrates surviving FP and watermark, releases rich objects");
        restored.budget = (FleetBudget) xml.fromXML(xml.toXML(restored.budget));
        check(!restored.budget.applyAbstract(.5f), "Serialized aggregate watermark prevents duplicate damage");
        check(world.routes.spawnRoute(restored.route), "Migrated route regenerates within survivor budget");
        world.routes.despawnRoute(restored.route);
        restored.route.getExtra().damage = .75f; world.traffic.maintain(2);
        check(restored.budget.remaining == 1.5f, "New damage after migration/load applies only once");
        // Old saves before per-ship FP was added derive it from the retained variant.
        world = new World(); entry = world.start(false); entry.budget = null; entry.checkpoint = legacy(1, 0);
        entry.checkpoint.ships.get(0).fleetPoints = 0;
        entry.checkpoint.ships.get(0).variant = world.variantSpecs.get("mudskipper_Standard");
        world.traffic.onLoad();
        check(entry.budget.remaining == 3 && entry.checkpoint == null, "Pre-FP checkpoints migrate from hull specifications");
    }
    private static void physicalLegacyMigration() {
        World world = new World();
        TrafficPlan plan = new TrafficPlan("vip", "origin", "station", "Old physical fleet",
                Collections.nCopies(4, "mudskipper_Standard"), 1, 180);
        TrafficMission mission = world.traffic.start(plan, 0, 1, false, true);
        NativeMission entry = world.traffic.active.get(mission.id);
        world.routes.spawnRoute(entry.route); FakeFleet fleet = world.created.get(0);
        fleet.members.remove(0); world.ships.get(fleet.members.get(0)).hull = .42f;
        fleet.memory.unset("$startingFP"); // Pre-aggregate fleets had no corrected denominator.
        entry.budget = null; entry.checkpoint = legacy(4, 0); entry.route.getExtra().damage = .25f;
        world.traffic.onLoad(); world.traffic.onLoad();
        check(entry.budget.remaining == 9 && entry.budget.physicalFP == 9 && entry.checkpoint == null
                && fleet.members.size() == 3 && fleet.members.get(0).getStatus().getHullFraction() == .42f,
                "Actual loaded physical fleet supersedes stale checkpoint without rebuilding or healing it");
        fleet.snapshot.addAll(fleet.members); fleet.members.remove(1);
        world.battle(fleet, fleet.api, true);
        check(entry.route.getExtra().damage == .5f, "Migrated physical fleet uses the corrected Nex damage denominator too");
        world.routes.despawnRoute(entry.route);
        check(world.routes.spawnRoute(entry.route) && entry.budget.remaining == 6, "First normal post-migration generation respects observed losses");
    }

    private static void generationRoundingAndRecorder() throws Exception {
        World world = new World(); world.manager.debugCommand("on", "all", null);
        NativeMission entry = world.start(false); entry.route.getExtra().damage = .25f;
        for (int i = 0; i < 3; i++) {
            check(world.routes.spawnRoute(entry.route), "Rounding case materializes");
            check(entry.budget.remaining == 4.5f && entry.budget.physicalFP == 3, "Discrete generation can leave budget unspent");
            world.routes.despawnRoute(entry.route);
        }
        check(entry.budget.remaining == 4.5f, "Repeated rounding does not erode budget");
        world.manager.debugCommand("flush", "all", null);
        boolean loss = false, composition = false;
        for (String content : world.commonFiles.values()) for (String line : content.split("\\n")) {
            if (!line.startsWith("{")) continue;
            org.json.JSONObject event = new org.json.JSONObject(line);
            if ("ABSTRACT_DAMAGE".equals(event.optString("kind"))) loss |= event.getDouble("previousBudgetFP") == 6
                    && event.getDouble("remainingBudgetFP") == 4.5 && event.getDouble("accountedRouteDamage") == .25;
            if ("FLEET_COMPOSITION".equals(event.optString("kind"))) composition |= event.getJSONArray("composition").length() == 1;
        }
        check(loss && composition, "Recorder emits structured budget transitions and actual regenerated composition");
        world.manager.debugCommand("off", "all", null);
        int writes = world.recorderWrites;
        world.routes.spawnRoute(entry.route); world.routes.despawnRoute(entry.route);
        check(world.recorderWrites == writes, "Disabled recorder does not write regeneration events");
    }
    private static void savedPhysicalBaseline() {
        World world = new World(); NativeMission entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        FakeFleet fleet = world.created.get(0);
        fleet.members.remove(1);
        entry.budget = (FleetBudget) serializer().fromXML(serializer().toXML(entry.budget));
        world.reloadHooks();
        check(entry.budget.physicalFP == 6 && fleet.members.size() == 1, "Physical save preserves starting FP despite current casualties");
        world.routes.despawnRoute(entry.route);
        check(entry.budget.remaining == 3, "Post-load capture uses the saved physical baseline");
        check(world.routes.spawnRoute(entry.route), "Loaded budget regenerates normally");
        FakeFleet next = world.created.get(1);
        entry.route.setCurrent(entry.route.getSegments().get(entry.route.getSegments().size() - 1));
        next.despawn(com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason.REACHED_DESTINATION, world.destination);
        check(entry.budget.physicalFP == 0 && entry.mission.state() == State.COMPLETED,
                "Physical terminal arrival captures the final budget too");
    }
    private static void churnReleasesBindings() {
        World world = new World();
        for (int i = 0; i < 100; i++) {
            NativeMission entry = world.start(false);
            for (int generation = 0; generation < 3; generation++) {
                check(world.routes.spawnRoute(entry.route), "Churn generation spawns");
                FakeFleet fleet = world.created.get(world.created.size() - 1);
                world.routes.despawnRoute(entry.route);
                check(!fleet.listeners.contains(world.traffic) && world.routes.getFleetToRoute().isEmpty(),
                        "Dematerialized fleets release Living Sector and Nex bindings");
            }
            world.traffic.cancel(entry.mission.id); world.traffic.maintain(2);
            check(world.traffic.size() == 0 && world.routes.getRoutesForSource(NativeTraffic.SOURCE).isEmpty()
                    && entry.route == null && entry.checkpoint == null && entry.budget == null,
                    "Mission turnover releases active route, budget and any checkpoint");
        }
        check(world.traffic.recent.size() == 16 && Collections.frequency(world.listeners, world.traffic) == 1,
                "Long turnover keeps history and global listener counts bounded");
    }
    private static void emptyPhysicalArrival() {
        World world = new World(); NativeMission entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        world.created.get(0).members.clear();
        entry.route.setCurrent(entry.route.getSegments().get(entry.route.getSegments().size() - 1));
        world.created.get(0).despawn(com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason.REACHED_DESTINATION,
                world.destination);
        check(entry.mission.state() == State.DESTROYED && entry.budget.remaining == 0,
                "An empty fleet cannot count as a delivery even when the arrival callback arrives first");
    }
    private static void admissionListenersAndMaintenanceMembership() {
        World world = new World(); NativeMission first = world.start(false); world.routes.spawnRoute(first.route);
        int reads = world.listenerReads;
        for (int i = 0; i < 100; i++) world.start(false);
        check(world.listenerReads == reads, "New admissions never revisit existing physical fleet listeners");
        world.routes.forbidMembershipScan = true;
        world.traffic.maintain(2);
        check(world.traffic.size() == 101, "Maintenance uses indexed membership without list searches");
        world.routes.forbidMembershipScan = false;
        world.traffic.onLoad();
        check(world.listenerReads == reads + 1, "Load reconciliation still revisits the one physical fleet");
    }
}
