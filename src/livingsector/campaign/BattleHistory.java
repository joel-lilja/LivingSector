package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import livingsector.debug.BattleDetails;
import org.json.JSONObject;

/** Debug-only battle correlation. No sector scan, saved state, or combat changes. */
final class BattleHistory implements FleetEventListener {
    interface Sink { void accept(JSONObject details); }
    private static final class Binding {
        final CampaignFleetAPI fleet;
        final Sink sink;
        boolean destroyed;
        Binding(CampaignFleetAPI fleet, Sink sink) { this.fleet = fleet; this.sink = sink; }
    }
    private final TrafficRecorder recorder;
    private final Map<String, Binding> fleets = new HashMap<String, Binding>();
    private final Set<String> retired = new HashSet<String>();
    private final Map<BattleAPI, Set<String>> reported = new WeakHashMap<BattleAPI, Set<String>>();

    BattleHistory(TrafficRecorder recorder) {
        this.recorder = recorder;
        Global.getSector().getListenerManager().addListener(this, true);
    }
    void track(CampaignFleetAPI fleet, Sink sink) {
        if (fleet == null || !recorder.healthy()) return;
        fleets.put(fleet.getId(), new Binding(fleet, sink));
        retired.remove(fleet.getId());
    }
    void retire(String id, boolean destroyed) {
        Binding binding = fleets.get(id);
        if (binding == null) return;
        binding.destroyed |= destroyed;
        retired.add(id);
    }
    void advance() {
        // Keep casualties through the synchronous despawn/battle dispatch, then release references.
        for (String id : retired) fleets.remove(id);
        retired.clear();
    }
    void close() {
        Global.getSector().getListenerManager().removeListener(this);
        fleets.clear(); retired.clear(); reported.clear();
    }
    @Override public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (fleet != null) retire(fleet.getId(), reason == FleetDespawnReason.DESTROYED_BY_BATTLE || reason == FleetDespawnReason.NO_MEMBERS);
    }
    @Override public void reportBattleOccurred(CampaignFleetAPI subject, CampaignFleetAPI winner, BattleAPI battle) {
        if (!recorder.healthy() || fleets.isEmpty()) return;
        try {
            if (subject != null) record(subject, winner, battle, "fleet_callback");
            else if (battle != null) {
                // The pre-battle snapshot includes casualties absent from surviving fleet callbacks.
                List<CampaignFleetAPI> participants = battle.getSnapshotBothSides();
                if (participants == null || participants.isEmpty()) participants = battle.getBothSides();
                if (participants != null) for (CampaignFleetAPI fleet : new ArrayList<CampaignFleetAPI>(participants)) {
                    if (fleet != null) record(fleet, winner, battle, "global_callback");
                }
            }
        } catch (Exception ex) { recorder.fail(ex); }
    }
    private void record(CampaignFleetAPI fleet, CampaignFleetAPI winner, BattleAPI battle, String source) throws Exception {
        Binding binding = fleets.get(fleet.getId());
        if (binding == null) return;
        if (battle != null) {
            Set<String> seen = reported.get(battle);
            if (seen != null && seen.contains(fleet.getId())) return;
            if (seen == null) {
                // Weak keys do not retain old battles; also bound entries before the next GC.
                if (reported.size() >= 128) reported.clear();
                seen = new HashSet<String>(); reported.put(battle, seen);
            }
            seen.add(fleet.getId());
        }
        binding.sink.accept(BattleDetails.capture(binding.fleet, winner, battle, binding.destroyed, source));
    }
}
