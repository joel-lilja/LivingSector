package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import livingsector.debug.RotatingLog;
import livingsector.debug.RecorderReport;
import livingsector.model.RecorderState;
import livingsector.model.TrafficMission;
import org.json.JSONObject;

/** Runtime-only controller. Failures stop recording, never the campaign. */
final class TrafficRecorder {
    private static TrafficRecorder current;
    private final TrafficManager manager;
    private final RecorderState cursor;
    private final RotatingLog log;
    private final Map<String, TrafficMission> missions = new LinkedHashMap<String, TrafficMission>();
    private final Map<String, TrafficJourney> direct = new LinkedHashMap<String, TrafficJourney>();
    private long nextFlush = System.nanoTime() + 30_000_000_000L;
    private boolean healthy = true;
    private BattleHistory battles;

    static RotatingLog.Store store() {
        return new RotatingLog.Store() {
            public boolean exists(String name) { return Global.getSettings().fileExistsInCommon(name); }
            public String read(String name) throws IOException { return Global.getSettings().readTextFileFromCommon(name); }
            public void write(String name, String data) throws IOException { Global.getSettings().writeTextFileToCommon(name, data); }
            public void delete(String name) { Global.getSettings().deleteTextFileFromCommon(name); }
        };
    }
    static void releasePrevious() {
        if (current != null) { current.close(); current = null; }
    }
    TrafficRecorder(TrafficManager manager, RecorderState cursor, int mib, String reason) throws IOException {
        this.manager = manager; this.cursor = cursor;
        log = new RotatingLog(store(), mib);
        log.setBudget(mib);
        String parent = cursor.runId;
        long parentSequence = cursor.sequence;
        double parentDay = cursor.day;
        if (cursor.campaignId == null) cursor.campaignId = UUID.randomUUID().toString();
        cursor.runId = UUID.randomUUID().toString();
        cursor.sequence = 0;
        current = this;
        try {
            JSONObject event = base("RUN_START").put("parentRun", value(parent)).put("parentSequence", parentSequence)
                    .put("parentDay", parentDay).put("detail", reason)
                    .put("settings", Global.getSettings().loadJSON("data/config/living_sector.json", "living_sector"));
            // The actual in-memory settings include Luna/console overrides.
            event.put("effectiveSettings", settingsSnapshot()).put("gameVersion", Global.getSettings().getVersionString());
            JSONObject versions = new JSONObject();
            for (String id : new String[]{"living_sector", "nexerelin", "lunalib"}) {
                com.fs.starfarer.api.ModSpecAPI spec = Global.getSettings().getModManager().getModSpec(id);
                if (spec != null) versions.put(id, spec.getVersion());
            }
            event.put("modVersions", versions);
            write(event);
            flush();
            if (healthy) battles = new BattleHistory(this);
        } catch (Exception ex) { fail(ex); }
    }
    boolean healthy() { return healthy; }
    String status() {
        return (healthy ? "ON" : "STOPPED after error") + "; run=" + cursor.runId + "; events=" + cursor.sequence
                + "; disk bytes=" + log.usedBytes() + "; buffered bytes=" + log.bufferedBytes();
    }
    private JSONObject base(String kind) throws org.json.JSONException {
        return new JSONObject().put("schema", 1).put("campaign", cursor.campaignId).put("run", cursor.runId)
                .put("seq", cursor.sequence + 1).put("day", manager.day()).put("date", Global.getSector().getClock().getDateString())
                .put("kind", kind);
    }
    private void write(JSONObject event) throws IOException {
        log.append(event.toString());
        cursor.sequence++;
        cursor.day = manager.day();
    }
    void marker(String kind, String detail) {
        if (!healthy) return;
        try { write(base(kind).put("detail", detail)); } catch (Exception ex) { fail(ex); }
    }
    void attach(TrafficMission mission, boolean created) {
        if (!healthy || !mission.active() || missions.containsKey(mission.id)) return;
        missions.put(mission.id, mission);
        mission.setEventSink(this::nativeEvent);
        nativeEvent(mission, created ? "CREATED" : "OBSERVED_EXISTING", created ? "admitted" : "recording began during this trip");
    }
    private void nativeEvent(TrafficMission mission, String kind, String detail) {
        nativeEvent(mission, kind, detail, null, null);
    }
    private void nativeEvent(TrafficMission mission, String kind, String detail, JSONObject battle, String battleFleet) {
        nativeEvent(mission, kind, detail, battle, battleFleet, null);
    }
    private void nativeEvent(TrafficMission mission, String kind, String detail, JSONObject battle, String battleFleet,
                             org.json.JSONArray composition) {
        if (!healthy) return;
        try {
            JSONObject event = base(kind).put("trip", mission.id).put("executor", "native").put("type", mission.plan.typeId)
                    .put("faction", mission.factionId).put("origin", mission.plan.originId).put("destination", mission.plan.destinationId)
                    .put("fleet", value(mission.fleetId() == null ? mission.lastFleetId() : mission.fleetId()))
                    .put("test", mission.test).put("state", mission.state().name()).put("leg", mission.leg() + 1)
                    .put("generation", mission.generation()).put("detail", clipped(detail));
            NativeMission entry = manager.nativeTraffic().active.get(mission.id);
            if (entry != null && entry.budget != null) {
                event.put("initialBudgetFP", entry.budget.initial).put("remainingBudgetFP", entry.budget.remaining)
                        .put("accountedRouteDamage", entry.budget.routeDamage);
                if (entry.debugPreviousFP != null) event.put("previousBudgetFP", entry.debugPreviousFP);
            }
            if (composition != null) event.put("composition", composition);
            if ("CREATED".equals(kind) || "OBSERVED_EXISTING".equals(kind)) {
                event.put("budget", mission.plan.budgetId()).put("roundTrip", mission.stops.size() > 2)
                        .put("variants", new org.json.JSONArray(mission.plan.variants));
                if (mission.plan.fleetRequest != null) event.put("requestedPassengerCapacity", mission.plan.fleetRequest.passengerCapacity);
            }
            if (battle != null) event.put("battle", battle).put("fleet", battleFleet);
            write(event);
            if (battles != null && (!mission.active() || "DEMATERIALIZED".equals(kind))) {
                battles.retire(mission.lastFleetId(), "DESTROYED".equals(kind));
            }
            if (!mission.active()) { missions.remove(mission.id); mission.setEventSink(null); }
        } catch (Exception ex) { fail(ex); }
    }
    void attach(TrafficJourney journey, boolean created) {
        if (!healthy || direct.containsKey(journey.historyId())) return;
        direct.put(journey.historyId(), journey);
        journey.recorder = this;
        journey.debugOutcome = null;
        journey.debugMovement = null;
        if (battles != null) battles.track(journey.fleet, detail -> directEvent(journey, "BATTLE", livingsector.debug.BattleDetails.summary(detail), detail));
        if (!journey.fleet.getEventListeners().contains(journey)) journey.fleet.addEventListener(journey);
        directEvent(journey, created ? "CREATED" : "OBSERVED_EXISTING", created ? "physical departure admitted" : "recording began during this trip");
    }
    void directEvent(TrafficJourney journey, String kind, String detail) {
        directEvent(journey, kind, detail, null);
    }
    private void directEvent(TrafficJourney journey, String kind, String detail, JSONObject battle) {
        if (!healthy) return;
        try {
            JSONObject event = base(kind).put("trip", journey.historyId()).put("executor", "direct").put("type", journey.typeId)
                    .put("faction", journey.fleet.getFaction().getId()).put("origin", journey.originId).put("destination", journey.destinationId)
                    .put("fleet", journey.fleet.getId()).put("test", false)
                    .put("state", journey.debugOutcome == null ? "ACTIVE" : journey.debugOutcome).put("detail", clipped(detail));
            if (battle != null) event.put("battle", battle);
            write(event);
            if (battles != null && RecorderReport.terminal(kind)) battles.retire(journey.fleet.getId(), "DESTROYED".equals(kind));
        } catch (Exception ex) { fail(ex); }
    }
    void forget(TrafficJourney journey) {
        if (journey.debugOutcome == null) directEvent(journey, "UNKNOWN", "fleet is no longer alive; despawn callback unavailable");
        direct.remove(journey.historyId());
        journey.fleet.removeEventListener(journey);
        journey.recorder = null;
        if (battles != null) battles.retire(journey.fleet.getId(), "DESTROYED".equals(journey.debugOutcome));
    }
    void trackBattleFleet(TrafficMission mission, CampaignFleetAPI fleet) {
        if (healthy && battles != null && fleet != null) battles.track(fleet,
                detail -> nativeEvent(mission, "BATTLE", livingsector.debug.BattleDetails.summary(detail), detail, fleet.getId()));
    }
    void materialized(NativeMission entry, CampaignFleetAPI fleet) {
        if (!healthy) return;
        try {
            org.json.JSONArray ships = new org.json.JSONArray();
            for (com.fs.starfarer.api.fleet.FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                ships.put(new JSONObject().put("ship", member.getId())
                        .put("variant", member.getVariant().getHullVariantId()).put("fp", member.getFleetPointCost()));
            }
            nativeEvent(entry.mission, "FLEET_COMPOSITION", "generated ships; identity changes are not battle casualties",
                    null, null, ships);
        } catch (Exception ex) { fail(ex); }
    }
    void battle(CampaignFleetAPI fleet, CampaignFleetAPI winner, com.fs.starfarer.api.campaign.BattleAPI battle) {
        if (battles != null) battles.reportBattleOccurred(fleet, winner, battle);
    }
    void observe(NativeMission entry) {
        if (!healthy) return;
        CampaignFleetAPI fleet = NativeTraffic.fleet(entry);
        if (fleet == null) return;
        String movement = movement(fleet);
        if (!movement.equals(entry.debugMovement)) {
            entry.debugMovement = movement;
            entry.mission.note("MOVEMENT_OBSERVED", movement);
        }
    }
    static String movement(CampaignFleetAPI fleet) {
        return "location=" + (fleet.getContainingLocation() == null ? "unplaced" : fleet.getContainingLocation().getId())
                + "; assignment=" + (fleet.getCurrentAssignment() == null ? "none" : fleet.getCurrentAssignment().getAssignment());
    }
    void advance() {
        if (battles != null) battles.advance();
        if (healthy && System.nanoTime() >= nextFlush) flush();
    }
    void flush() {
        if (!healthy) return;
        try { log.flush(); nextFlush = System.nanoTime() + 30_000_000_000L; } catch (Exception ex) { fail(ex); }
    }
    void budget(int mib) { if (healthy) try { log.setBudget(mib); } catch (Exception ex) { fail(ex); } }
    void settingsChanged() {
        if (!healthy) return;
        try { write(base("SETTINGS_CHANGED").put("effectiveSettings", settingsSnapshot()).put("detail", "configuration applied")); }
        catch (Exception ex) { fail(ex); }
    }
    private static JSONObject settingsSnapshot() throws org.json.JSONException {
        // Starsector's script loader forbids java.lang.reflect.Field, even for public settings.
        // Read the effective values explicitly so Luna overrides are still recorded.
        livingsector.LivingSectorSettings settings = livingsector.LivingSectorPlugin.settings();
        livingsector.traffic.VipTrafficPolicy.Config vip = settings.vip;
        livingsector.traffic.CivilianTrafficPolicy.Config c = settings.civilian;
        return new JSONObject()
                .put("enabled", settings.enabled).put("debugLogging", settings.debugLogging)
                .put("useNativeRoutes", settings.useNativeRoutes)
                .put("debugTrafficHistory", settings.debugTrafficHistory).put("debugHistoryMiB", settings.debugHistoryMiB)
                .put("globalFleetLimit", settings.globalFleetLimit)
                .put("planningIntervalDays", settings.planningIntervalDays).put("maintenanceIntervalDays", settings.maintenanceIntervalDays)
                .put("civilian", new JSONObject().put("enabled", c.enabled).put("includeStations", c.includeStations)
                        .put("localEnabled", c.localEnabled).put("linerEnabled", c.linerEnabled).put("charterEnabled", c.charterEnabled)
                        .put("minimumMarketSize", c.minimumMarketSize).put("hardLimit", c.hardLimit)
                        .put("baseTarget", c.baseTarget).put("marketsPerAdditionalFleet", c.marketsPerAdditionalFleet)
                        .put("maximumTarget", c.maximumTarget).put("targetVariation", c.targetVariation)
                        .put("targetRerollDays", c.targetRerollDays).put("dailySpawnChance", c.dailySpawnChance)
                        .put("originCooldownDays", c.originCooldownDays).put("localWeight", c.localWeight)
                        .put("linerWeight", c.linerWeight).put("charterWeight", c.charterWeight)
                        .put("homeFactionPreference", c.homeFactionPreference).put("localReturnChance", c.localReturnChance)
                        .put("linerReturnChance", c.linerReturnChance).put("charterReturnChance", c.charterReturnChance))
                .put("vip", new JSONObject()
                        .put("enabled", vip.enabled).put("includeStations", vip.includeStations)
                        .put("minimumMarketSize", vip.minimumMarketSize).put("hardLimit", vip.hardLimit)
                        .put("baseTarget", vip.baseTarget).put("marketsPerAdditionalFleet", vip.marketsPerAdditionalFleet)
                        .put("maximumTarget", vip.maximumTarget).put("targetVariation", vip.targetVariation)
                        .put("targetRerollDays", vip.targetRerollDays).put("dailySpawnChance", vip.dailySpawnChance)
                        .put("originCooldownDays", vip.originCooldownDays).put("maximumTripDays", vip.maximumTripDays)
                        .put("boardingDays", vip.boardingDays).put("variant", vip.variant));
    }
    void close() {
        flush();
        healthy = false;
        if (battles != null) { battles.close(); battles = null; }
        for (TrafficMission mission : missions.values()) mission.setEventSink(null);
        missions.clear();
        for (TrafficJourney journey : direct.values()) { journey.fleet.removeEventListener(journey); journey.recorder = null; }
        direct.clear();
        if (current == this) current = null;
    }
    void fail(Exception ex) {
        healthy = false;
        manager.recorderFailure(ex.getMessage());
        // Do not mutate fleet listener collections while a callback may be dispatching.
        for (TrafficMission mission : missions.values()) mission.setEventSink(null);
        Global.getLogger(TrafficRecorder.class).warn("Living Sector recorder stopped; gameplay continues", ex);
    }
    static String report(RecorderState cursor, double day, int mib, String action, String window, String id) throws IOException {
        RotatingLog reader = new RotatingLog(store(), mib); // Read-only; never rotates or creates files.
        return RecorderReport.report(reader, cursor, day, action, window, id);
    }
    private static Object value(String text) { return text == null ? JSONObject.NULL : text; }
    private static String clipped(String text) { return text == null ? "" : text.length() <= 2048 ? text : text.substring(0, 2048) + " [truncated]"; }
}
