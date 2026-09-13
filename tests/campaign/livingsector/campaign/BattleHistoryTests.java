package livingsector.campaign;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import static livingsector.campaign.CampaignFixture.*;
import static livingsector.campaign.IntegrationSuite.check;
import static livingsector.campaign.RecorderIntegrationTests.*;

final class BattleHistoryTests {
    static void register(IntegrationSuite suite) {
        suite.add("battleHistory.directCasualtyGlobalOnly", () -> casualty(false));
        suite.add("battleHistory.nativeCasualtyGlobalOnly", () -> casualty(true));
        suite.add("battleHistory.callbackOrdersAndRepeatBattles", BattleHistoryTests::callbackOrders);
        suite.add("battleHistory.missingEvidenceAndDisable", BattleHistoryTests::missingEvidenceAndDisable);
        suite.add("battleHistory.enableDuringExistingTrip", BattleHistoryTests::existingTrip);
    }
    private static FakeFleet trip(World world, boolean nativeRoute) {
        if (nativeRoute) {
            NativeMission entry = world.start(false); world.routes.spawnRoute(entry.route);
        } else { forceDirectPolicy(); world.advance(6); }
        return world.created.get(0);
    }
    private static CampaignFleetAPI enemy(World world) {
        return proxy(CampaignFleetAPI.class, (m, a) -> {
            if (m.equals("getId")) return "hostile-fleet";
            if (m.equals("getName")) return "Hostile patrol";
            if (m.equals("getFaction")) return world.enemy;
            return null;
        });
    }
    private static BattleAPI battle(FakeFleet own, CampaignFleetAPI enemy) {
        return proxy(BattleAPI.class, (m, a) -> {
            if (m.equals("getSnapshotBothSides")) return Arrays.asList(own.api, enemy);
            if (m.equals("getBothSides")) return own.alive ? Arrays.asList(own.api, enemy) : Collections.singletonList(enemy);
            if (m.equals("getSnapshotSideOne")) return Collections.singletonList(own.api);
            if (m.equals("getSnapshotSideTwo") || m.equals("getSideTwo")) return Collections.singletonList(enemy);
            if (m.equals("getSideOne")) return own.alive ? Collections.singletonList(own.api) : Collections.emptyList();
            if (m.equals("getSeed")) return 8877L;
            return null;
        });
    }
    private static JSONObject lastBattle(World world) throws Exception {
        JSONObject result = null;
        for (JSONObject e : events(world)) if ("BATTLE".equals(e.optString("kind"))) result = e;
        check(result != null, "Battle event was written");
        return result.getJSONObject("battle");
    }
    private static void casualty(boolean nativeRoute) throws Exception {
        World world = new World(); world.manager.onLoad(); command("debug on");
        FakeFleet own = trip(world, nativeRoute);
        own.snapshot.addAll(own.members);
        int before = own.members.size();
        CampaignFleetAPI enemy = enemy(world); BattleAPI battle = battle(own, enemy);
        own.members.clear(); own.despawn(FleetDespawnReason.DESTROYED_BY_BATTLE, null);
        // No callback to the eliminated fleet. Only the global post-battle snapshot remains.
        ListenerUtil.reportBattleOccurred(null, enemy, battle);
        ListenerUtil.reportBattleOccurred(null, enemy, battle);
        command("debug flush");
        check(count(world, "DESTROYED") == 1 && count(world, "BATTLE") == 1,
                "Destroyed fleet gets one outcome and one detailed battle despite missing attached callback");
        JSONObject detail = lastBattle(world);
        check(detail.getString("result").equals("defeat") && detail.getBoolean("fleetDestroyed")
                && detail.getInt("shipsBefore") == before && detail.getInt("shipsAfter") == 0
                && detail.getInt("shipsMissingFromFleet") == before, "Snapshot identifies defeat and full ship loss");
        check(detail.getJSONArray("opponents").getJSONObject(0).getString("id").equals("hostile-fleet")
                && detail.getJSONArray("opponents").getJSONObject(0).getString("faction").equals("pirates"),
                "Opponent identity and faction come from battle evidence");
        world.manager.pauseDepartures(true); world.advance(3);
        ListenerUtil.reportBattleOccurred(null, enemy, battle(own, enemy)); command("debug flush");
        check(count(world, "BATTLE") == 1, "Retired bindings release after callback dispatch instead of tracking dead fleets forever");
    }
    private static void callbackOrders() throws Exception {
        for (boolean nativeRoute : new boolean[]{false, true}) for (boolean globalFirst : new boolean[]{false, true}) {
            defaults(); World world = new World(); world.manager.onLoad(); command("debug on");
            FakeFleet own = trip(world, nativeRoute); own.snapshot.addAll(own.members);
            if (nativeRoute) own.members.remove(1);
            CampaignFleetAPI enemy = enemy(world); BattleAPI battle = battle(own, enemy);
            if (globalFirst) ListenerUtil.reportBattleOccurred(null, own.api, battle);
            for (FleetEventListener listener : new ArrayList<FleetEventListener>(own.listeners)) listener.reportBattleOccurred(own.api, own.api, battle);
            if (!globalFirst) ListenerUtil.reportBattleOccurred(null, own.api, battle);
            command("debug flush");
            check(count(world, "BATTLE") == 1, "Global and attached notifications count the same battle once in either order");
            JSONObject detail = lastBattle(world);
            check(detail.getString("result").equals("victory") && detail.getInt("shipsMissingFromFleet") == (nativeRoute ? 1 : 0),
                    "Surviving fleets preserve snapshot loss counts and winner side");
            ListenerUtil.reportBattleOccurred(null, own.api, battle(own, enemy)); command("debug flush");
            check(count(world, "BATTLE") == 2, "A later battle for the same fleet is a distinct event");
        }
    }
    private static void missingEvidenceAndDisable() throws Exception {
        World world = new World(); world.manager.onLoad(); command("debug on");
        FakeFleet own = trip(world, false);
        world.battle(own, null, true); command("debug flush");
        JSONObject detail = lastBattle(world);
        check(detail.isNull("shipsBefore") && detail.getString("result").equals("unknown")
                && detail.isNull("opponentCount"), "Missing snapshots and winner are explicit unknowns");
        CampaignFleetAPI enemy = enemy(world);
        world.manager.recorder().battle(enemy, enemy, battle(own, enemy)); command("debug flush");
        check(count(world, "BATTLE") == 1, "Unowned fleet callbacks do not create traffic history");
        command("debug off");
        int writes = world.recorderWrites, reads = world.recorderReads;
        ListenerUtil.reportBattleOccurred(null, enemy, battle(own, enemy)); world.battle(own, enemy, false);
        check(world.recorderWrites == writes && world.recorderReads == reads
                && world.listeners.stream().noneMatch(l -> l instanceof BattleHistory), "Disabling removes the global recorder listener and all recorder I/O");
    }
    private static void existingTrip() throws Exception {
        for (boolean nativeRoute : new boolean[]{false, true}) {
            defaults(); World world = new World(); world.manager.onLoad();
            FakeFleet own = trip(world, nativeRoute); own.snapshot.addAll(own.members);
            command("debug on");
            ListenerUtil.reportBattleOccurred(null, own.api, battle(own, enemy(world))); command("debug flush");
            check(count(world, "OBSERVED_EXISTING") == 1 && count(world, "CREATED") == 0 && count(world, "BATTLE") == 1,
                    "Enabling mid-trip binds the existing physical fleet without inventing a departure");
        }
    }
}
