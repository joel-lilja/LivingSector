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
        if (!healthy) return;
        try {
            write(base(kind).put("trip", mission.id).put("executor", "native").put("type", mission.plan.typeId)
                    .put("faction", mission.factionId).put("origin", mission.plan.originId).put("destination", mission.plan.destinationId)
                    .put("fleet", value(mission.fleetId() == null ? mission.lastFleetId() : mission.fleetId()))
                    .put("test", mission.test).put("state", mission.state().name()).put("leg", mission.leg() + 1)
                    .put("generation", mission.generation()).put("detail", clipped(detail)));
            if (!mission.active()) { missions.remove(mission.id); mission.setEventSink(null); }
        } catch (Exception ex) { fail(ex); }
    }
    void attach(TrafficJourney journey, boolean created) {
        if (!healthy || direct.containsKey(journey.historyId())) return;
        direct.put(journey.historyId(), journey);
        journey.recorder = this;
        journey.debugOutcome = null;
        journey.debugMovement = null;
        if (!journey.fleet.getEventListeners().contains(journey)) journey.fleet.addEventListener(journey);
        directEvent(journey, created ? "CREATED" : "OBSERVED_EXISTING", created ? "physical departure admitted" : "recording began during this trip");
    }
    void directEvent(TrafficJourney journey, String kind, String detail) {
        if (!healthy) return;
        try {
            write(base(kind).put("trip", journey.historyId()).put("executor", "direct").put("type", journey.typeId)
                    .put("faction", journey.fleet.getFaction().getId()).put("origin", journey.originId).put("destination", journey.destinationId)
                    .put("fleet", journey.fleet.getId()).put("test", false)
                    .put("state", journey.debugOutcome == null ? "ACTIVE" : journey.debugOutcome).put("detail", clipped(detail)));
        } catch (Exception ex) { fail(ex); }
    }
    void forget(TrafficJourney journey) {
        if (journey.debugOutcome == null) directEvent(journey, "UNKNOWN", "fleet is no longer alive; despawn callback unavailable");
        direct.remove(journey.historyId());
        journey.fleet.removeEventListener(journey);
        journey.recorder = null;
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
    private static JSONObject settingsSnapshot() throws Exception {
        livingsector.LivingSectorSettings settings = livingsector.LivingSectorPlugin.settings();
        JSONObject effective = new JSONObject(), vip = new JSONObject();
        for (java.lang.reflect.Field field : settings.getClass().getFields()) {
            if (!field.getName().equals("vip")) effective.put(field.getName(), field.get(settings));
        }
        for (java.lang.reflect.Field field : settings.vip.getClass().getFields()) vip.put(field.getName(), field.get(settings.vip));
        return effective.put("vip", vip);
    }
    void close() {
        flush();
        healthy = false;
        for (TrafficMission mission : missions.values()) mission.setEventSink(null);
        missions.clear();
        for (TrafficJourney journey : direct.values()) { journey.fleet.removeEventListener(journey); journey.recorder = null; }
        direct.clear();
        if (current == this) current = null;
    }
    private void fail(Exception ex) {
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
