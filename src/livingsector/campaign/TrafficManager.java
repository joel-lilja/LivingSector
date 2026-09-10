package livingsector.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import livingsector.LivingSectorPlugin;
import livingsector.LivingSectorSettings;
import livingsector.model.SectorSnapshot;
import livingsector.traffic.TrafficContext;
import livingsector.traffic.TrafficPlan;
import livingsector.traffic.TrafficPolicy;
import livingsector.traffic.TrafficRegistry;
import livingsector.traffic.TrafficScheduler;

/** One persistent campaign script; no per-frame market scans or per-fleet custom scripts. */
public final class TrafficManager implements EveryFrameScript {
    private final TrafficScheduler scheduler = new TrafficScheduler();
    private final List<TrafficJourney> journeys = new ArrayList<TrafficJourney>();
    private final Random random = new Random();
    private final Map<String, Double> retryAfter = new HashMap<String, Double>();
    private double day, nextTick = 1;
    private long spawned;
    // Added lazily: old saves keep their existing direct physical journeys.
    private NativeTraffic nativeTraffic;
    private boolean departuresPaused;
    private DistractionProbe probe;
    private livingsector.model.RecorderState recorderState;
    private transient TrafficRecorder recorder;
    private transient Boolean recorderOverride;
    private transient String recorderError;
    private transient boolean loaded;
    // Deadlines are reset once on load, including migration from the original daily scheduler.
    private transient boolean cadenceInitialized;
    private transient double nextMaintenance;
    private transient long planningPasses, maintenancePasses, snapshotReads;
    private transient double lastUpdateMillis, maxUpdateMillis;

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return false; }

    public void advance(float amount) {
        if (Global.getSector().isPaused()) return;
        LivingSectorSettings settings = LivingSectorPlugin.settings();
        if (!cadenceInitialized) {
            nextTick = day + settings.planningIntervalDays;
            nextMaintenance = day + settings.maintenanceIntervalDays;
            cadenceInitialized = true;
        }
        day += Global.getSector().getClock().convertToDays(amount);
        if (recorder != null) {
            if (recorder.healthy()) recorder.advance();
            else { recorder.close(); recorder = null; if (nativeTraffic != null) nativeTraffic.setRecorder(null); }
        }
        if (probe != null) probe.advance(day);
        boolean maintain = day >= nextMaintenance;
        boolean plan = day >= nextTick;
        if (!maintain && !plan) return;
        // Schedule from now: loading or advancing a long time never produces catch-up bursts.
        if (maintain) nextMaintenance = day + settings.maintenanceIntervalDays;
        if (plan) nextTick = day + settings.planningIntervalDays;
        long started = System.nanoTime();
        PassSnapshot snapshot = new PassSnapshot();
        if (maintain && !journeys.isEmpty()) maintainJourneys(snapshot);
        if (maintain && nativeTraffic != null) nativeTraffic.maintain(day);
        if (plan && settings.enabled && !departuresPaused) planTraffic(snapshot, settings);
        lastUpdateMillis = (System.nanoTime() - started) / 1000000.0;
        maxUpdateMillis = Math.max(maxUpdateMillis, lastUpdateMillis);
    }

    /** Shared only within this update; a diversion can reuse the planning snapshot or vice versa. */
    private final class PassSnapshot implements Supplier<SectorSnapshot> {
        private SectorSnapshot value;
        public SectorSnapshot get() {
            if (value == null) {
                Set<String> factions = new LinkedHashSet<String>();
                for (TrafficJourney journey : journeys) factions.add(journey.fleet.getFaction().getId());
                value = SectorReader.capture(factions);
                snapshotReads++;
            }
            return value;
        }
    }

    private void maintainJourneys(Supplier<SectorSnapshot> snapshot) {
        maintenancePasses++;
        Iterator<TrafficJourney> iterator = journeys.iterator();
        while (iterator.hasNext()) {
            TrafficJourney journey = iterator.next();
            if (recorder != null && recorder.healthy()) journey.observeDebugMovement();
            if (journey.advance(day, snapshot)) {
                if (journey.recorder != null) journey.recorder.forget(journey);
                debug("Finished tracking " + journey.typeId + " from " + journey.originId);
                iterator.remove();
            }
        }
    }

    private void planTraffic(Supplier<SectorSnapshot> snapshot, LivingSectorSettings settings) {
        // Release native arrivals even if maintenance is configured less frequently than planning.
        for (Iterator<TrafficJourney> iterator = journeys.iterator(); iterator.hasNext();) {
            TrafficJourney journey = iterator.next();
            if (!journey.fleet.isAlive()) {
                if (journey.recorder != null) journey.recorder.forget(journey);
                iterator.remove();
            }
        }
        if (activeCount() >= settings.globalFleetLimit) return;
        List<TrafficPolicy> policies = TrafficRegistry.policies();
        for (Iterator<TrafficPolicy> iterator = policies.iterator(); iterator.hasNext();) {
            Double retry = retryAfter.get(iterator.next().getId());
            if (retry != null && day < retry) iterator.remove();
        }
        if (policies.isEmpty()) return;
        planningPasses++;
        Collections.shuffle(policies, random);
        List<TrafficContext.Route> active = new ArrayList<TrafficContext.Route>();
        for (TrafficJourney journey : journeys) active.add(journey.route());
        if (nativeTraffic != null) nativeTraffic.addRoutesTo(active);
        for (TrafficPolicy policy : policies) {
            if (activeCount() >= settings.globalFleetLimit) break;
            try {
                TrafficPlan plan = scheduler.evaluate(policy, snapshot.get(), active, day,
                        settings.planningIntervalDays, random);
                if (plan == null) continue;
                if (settings.useNativeRoutes) {
                    nativeTraffic().start(plan, day, random.nextLong(), false, false);
                    active.add(new TrafficContext.Route(plan.typeId, plan.originId, plan.destinationId));
                } else {
                    CampaignFleetAPI fleet = CivilianFleetFactory.spawn(plan, random);
                    if (fleet == null) continue;
                    TrafficJourney journey = new TrafficJourney(fleet, plan, day);
                    journeys.add(journey);
                    if (recorder != null) recorder.attach(journey, true);
                    active.add(journey.route());
                }
                scheduler.recordDeparture(plan, day);
                spawned++;
                debug("Spawned " + plan.typeId + ": " + plan.originId + " -> " + plan.destinationId
                        + "; active=" + activeCount() + "; target=" + scheduler.target(plan.typeId));
            } catch (RuntimeException ex) {
                retryAfter.put(policy.getId(), day + 30);
                Global.getLogger(TrafficManager.class).error("Living Sector policy failed: "
                        + policy.getId() + "; retrying in 30 campaign days", ex);
            }
        }
    }

    public void onLoad() {
        TrafficRecorder.releasePrevious();
        recorder = null; recorderOverride = null; recorderError = null;
        if (nativeTraffic != null) nativeTraffic.setRecorder(null);
        loaded = true;
        // Remove debug listeners serialized by an earlier enabled recording session.
        for (TrafficJourney journey : journeys) { journey.recorder = null; journey.fleet.removeEventListener(journey); }
        if (nativeTraffic != null) nativeTraffic.onLoad();
        updateRecorder("CAMPAIGN_LOADED");
    }
    public void settingsChanged(LivingSectorSettings previous, LivingSectorSettings current) {
        if (previous.planningIntervalDays != current.planningIntervalDays
                || previous.maintenanceIntervalDays != current.maintenanceIntervalDays) cadenceInitialized = false;
        // Base-target changes are detected by the scheduler; refresh changes to its randomization too.
        if (previous.vip.targetVariation != current.vip.targetVariation
                || previous.vip.targetRerollDays != current.vip.targetRerollDays
                || previous.vip.hardLimit != current.vip.hardLimit) scheduler.invalidateTarget("vip");
        recorderOverride = null;
        if (loaded) updateRecorder("SETTINGS_CHANGED");
    }
    TrafficRecorder recorder() { return recorder; }
    void recorderFailure(String error) { recorderError = error; }
    private void updateRecorder(String reason) {
        boolean enabled = recorderOverride == null ? LivingSectorPlugin.settings().debugTrafficHistory : recorderOverride;
        if (!enabled) {
            if (recorder != null) { recorder.marker("RECORDING_STOPPED", reason); recorder.close(); recorder = null; }
            if (nativeTraffic != null) nativeTraffic.setRecorder(null);
            return;
        }
        if (recorder != null) {
            recorder.budget(LivingSectorPlugin.settings().debugHistoryMiB);
            recorder.settingsChanged();
            return;
        }
        if (recorderError != null) return; // Explicit off/on or load is required after an I/O failure.
        if (recorderState == null) recorderState = new livingsector.model.RecorderState();
        try {
            recorder = new TrafficRecorder(this, recorderState, LivingSectorPlugin.settings().debugHistoryMiB, reason);
            if (nativeTraffic != null) nativeTraffic.setRecorder(recorder);
            for (TrafficJourney journey : journeys) recorder.attach(journey, false);
            if (nativeTraffic != null) for (NativeMission entry : nativeTraffic.active.values()) {
                entry.debugSegment = null; entry.debugMovement = null;
                recorder.attach(entry.mission, false);
            }
        } catch (Exception ex) {
            recorderError = ex.getMessage();
            Global.getLogger(TrafficManager.class).warn("Living Sector recorder could not start; gameplay continues", ex);
        }
    }
    public void recordSave(String kind) {
        if (recorder != null) { recorder.marker(kind, "campaign save boundary"); recorder.flush(); }
    }
    public String debugCommand(String action, String window, String id) {
        if ("on".equals(action) || "off".equals(action)) {
            boolean on = "on".equals(action);
            if (recorder != null && !recorder.healthy()) { recorder.close(); recorder = null; }
            recorderOverride = on;
            if (on) recorderError = null;
            updateRecorder(on ? "CONSOLE_ENABLED" : "CONSOLE_DISABLED");
            return recorderStatus() + "\nConsole override lasts until campaign load or the next Luna settings apply.";
        }
        if ("status".equals(action)) return recorderStatus();
        if ("flush".equals(action)) { if (recorder != null) recorder.flush(); return recorderStatus(); }
        if (recorder != null) recorder.flush();
        if ("export".equals(action)) return recorderStatus() + "\nStructured export is already in saves/common/living-sector-debug/slot-*.jsonl."
                + " Copy those files before they rotate; no duplicate export files are created outside the disk allowance.";
        if (!"summary".equals(action) && !"trip".equals(action) && !"runs".equals(action)) throw new IllegalArgumentException("Unknown recorder action");
        try { return TrafficRecorder.report(recorderState, day, LivingSectorPlugin.settings().debugHistoryMiB, action, window, id); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Recorder report unavailable: " + ex.getMessage(), ex); }
    }
    private String recorderStatus() {
        return "Traffic recorder: " + (recorder == null ? "OFF" : recorder.status())
                + "; allowance=" + LivingSectorPlugin.settings().debugHistoryMiB + " MiB"
                + (recorder == null && recorderState != null ? "; last run=" + recorderState.runId : "")
                + (recorderError == null ? "" : "; ERROR=" + recorderError);
    }
    public int activeCount() { return journeys.size() + (nativeTraffic == null ? 0 : nativeTraffic.size()); }
    public double day() { return day; }
    public void pauseDepartures(boolean paused) { departuresPaused = paused; }
    public boolean departuresPaused() { return departuresPaused; }
    NativeTraffic nativeTraffic() {
        if (nativeTraffic == null) { nativeTraffic = new NativeTraffic(); nativeTraffic.setRecorder(recorder); }
        return nativeTraffic;
    }
    NativeTraffic existingNativeTraffic() { return nativeTraffic; }
    DistractionProbe probe() { return probe; }
    void setProbe(DistractionProbe value) { probe = value; }
    public static TrafficManager get() {
        if (Global.getSector() != null) {
            for (EveryFrameScript script : Global.getSector().getScripts()) {
                if (script instanceof TrafficManager) return (TrafficManager) script;
            }
        }
        throw new IllegalStateException("Living Sector manager is not installed in this campaign");
    }

    /** Available from Console Commands: runcode ...Console.showMessage(TrafficManager.status()); */
    public static String status() {
        if (Global.getSector() == null) return "No campaign loaded";
        for (EveryFrameScript script : Global.getSector().getScripts()) {
            if (!(script instanceof TrafficManager)) continue;
            TrafficManager manager = (TrafficManager) script;
            StringBuilder out = new StringBuilder("Living Sector: date=")
                    .append(Global.getSector().getClock().getDateString())
                    .append("; elapsed days=").append(roundDays(manager.day))
                    .append(" (since mod installed)");
            if (manager.cadenceInitialized) {
                out.append("; next planning in=").append(roundDays(Math.max(0, manager.nextTick - manager.day)))
                        .append("d; next maintenance in=")
                        .append(roundDays(Math.max(0, manager.nextMaintenance - manager.day))).append('d');
            }
            out.append("\nActive=").append(manager.activeCount())
                    .append("; automatic departures paused=").append(manager.departuresPaused)
                    .append("; total automatic departures=").append(manager.spawned)
                    .append("; planning passes=").append(manager.planningPasses)
                    .append("; maintenance passes=").append(manager.maintenancePasses)
                    .append("; sector scans=").append(manager.snapshotReads)
                    .append("; last/max update ms=").append(manager.lastUpdateMillis)
                    .append('/').append(manager.maxUpdateMillis);
            for (TrafficPolicy policy : TrafficRegistry.policies()) {
                out.append("; ").append(policy.getId()).append(" target=")
                        .append(Math.round(manager.scheduler.target(policy.getId()) * 10) / 10.0);
            }
            for (TrafficJourney journey : manager.journeys) {
                CampaignFleetAPI fleet = journey.fleet;
                LocationAPI location = fleet.getContainingLocation();
                out.append('\n').append(journey.typeId).append(" [fleet ID=").append(fleet.getId())
                        .append("]: ").append(journey.originId)
                        .append(" -> ").append(journey.destinationId)
                        .append(" (").append(location == null || !fleet.isAlive() ? "despawned"
                                : location.getName()).append(')');
                if (location != null && fleet.isAlive()) {
                    out.append("; position=").append(Math.round(fleet.getLocation().x))
                            .append(", ").append(Math.round(fleet.getLocation().y));
                    out.append("\n  Console: jump ").append(location.isHyperspace() ? "hyperspace" : location.getId())
                            .append("\n  Then: goto ").append(fleet.getId());
                }
            }
            if (manager.nativeTraffic != null) out.append(TrafficDebug.describe(manager.nativeTraffic, manager.day));
            if (manager.probe != null) out.append('\n').append(manager.probe.summary());
            return out.toString();
        }
        return "Living Sector manager is not installed";
    }

    private static double roundDays(double value) { return Math.round(value * 100) / 100.0; }

    static void debug(String message) {
        if (LivingSectorPlugin.settings().debugLogging) Global.getLogger(TrafficManager.class).info(message);
    }
}
