package livingsector.debug;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads bounded post-battle evidence from the API; never alters fleets or takes new snapshots. */
public final class BattleDetails {
    private BattleDetails() { }
    private static final int MAX_FLEETS = 16, MAX_SHIPS = 32;

    public static JSONObject capture(CampaignFleetAPI fleet, CampaignFleetAPI winner, BattleAPI battle,
                                     boolean destroyed, String source) throws Exception {
        JSONObject data = new JSONObject().put("source", source).put("fleet", fleet(fleet))
                .put("primaryWinner", winner == null ? JSONObject.NULL : fleet(winner))
                .put("fleetDestroyed", destroyed);
        if (fleet.getContainingLocation() != null) {
            data.put("location", clipped(fleet.getContainingLocation().getId()))
                    .put("x", fleet.getLocation().x).put("y", fleet.getLocation().y);
        }
        List<CampaignFleetAPI> allies = null, opponents = null;
        String result = "unknown", sidesSource = "unavailable";
        if (battle != null) {
            data.put("battleSeed", battle.getSeed()).put("playerInvolved", battle.isPlayerInvolved());
            List<CampaignFleetAPI> one = battle.getSnapshotSideOne(), two = battle.getSnapshotSideTwo();
            if (contains(one, fleet)) { allies = one; opponents = two; sidesSource = "snapshot"; }
            else if (contains(two, fleet)) { allies = two; opponents = one; sidesSource = "snapshot"; }
            else {
                one = battle.getSideOne(); two = battle.getSideTwo();
                if (contains(one, fleet)) { allies = one; opponents = two; sidesSource = "current"; }
                else if (contains(two, fleet)) { allies = two; opponents = one; sidesSource = "current"; }
            }
            if (winner != null) {
                if (contains(allies, winner) || winner == fleet) result = "victory";
                else if (contains(opponents, winner)) result = "defeat";
                else if (battle.wasFleetVictorious(fleet, winner)) result = "victory";
                else if (battle.wasFleetDefeated(fleet, winner)) result = "defeat";
            }
        }
        data.put("result", result).put("sidesSource", sidesSource)
                .put("allies", fleets(allies)).put("opponents", fleets(opponents))
                .put("allyCount", allies == null ? JSONObject.NULL : allies.size())
                .put("opponentCount", opponents == null ? JSONObject.NULL : opponents.size());
        List<FleetMemberAPI> after = destroyed ? Collections.<FleetMemberAPI>emptyList() : fleet.getFleetData().getMembersListCopy();
        List<FleetMemberAPI> before = fleet.getFleetData().getSnapshot();
        Set<String> survivors = new HashSet<String>();
        for (FleetMemberAPI member : after) survivors.add(member.getId());
        JSONArray missing = new JSONArray();
        int lost = 0;
        if (before != null && !before.isEmpty()) for (FleetMemberAPI member : before) {
            if (!survivors.contains(member.getId())) {
                if (lost < MAX_SHIPS) missing.put(clipped(member.getId()));
                lost++;
            }
        }
        boolean knownBefore = before != null && !before.isEmpty();
        data.put("shipsBefore", knownBefore ? before.size() : JSONObject.NULL)
                .put("shipsAfter", after.size()).put("afterCountSource", destroyed ? "despawn" : "fleet_members")
                .put("shipsMissingFromFleet", knownBefore ? lost : JSONObject.NULL).put("missingShipIds", missing)
                .put("missingShipIdsTruncated", lost > MAX_SHIPS);
        return data;
    }
    public static String summary(JSONObject data) {
        return "result=" + data.optString("result") + "; opponents=" + data.optJSONArray("opponents")
                + "; ships before=" + data.opt("shipsBefore") + "; after=" + data.optInt("shipsAfter");
    }
    private static JSONArray fleets(List<CampaignFleetAPI> fleets) throws Exception {
        JSONArray result = new JSONArray();
        if (fleets != null) for (CampaignFleetAPI fleet : fleets) {
            if (result.length() >= MAX_FLEETS) break;
            if (fleet != null) result.put(fleet(fleet));
        }
        return result;
    }
    private static JSONObject fleet(CampaignFleetAPI fleet) throws Exception {
        return new JSONObject().put("id", clipped(fleet.getId())).put("name", clipped(fleet.getName()))
                .put("faction", fleet.getFaction() == null ? JSONObject.NULL : clipped(fleet.getFaction().getId()));
    }
    private static boolean contains(List<CampaignFleetAPI> fleets, CampaignFleetAPI target) {
        if (fleets == null || target == null) return false;
        for (CampaignFleetAPI fleet : fleets) if (fleet == target || (fleet != null && target.getId().equals(fleet.getId()))) return true;
        return false;
    }
    private static String clipped(String value) { return value == null ? "" : value.substring(0, Math.min(160, value.length())); }
}
