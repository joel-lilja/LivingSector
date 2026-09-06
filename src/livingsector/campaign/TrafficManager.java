package livingsector.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
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
        boolean maintain = day >= nextMaintenance;
        boolean plan = day >= nextTick;
        if (!maintain && !plan) return;
        // Schedule from now: loading or advancing a long time never produces catch-up bursts.
        if (maintain) nextMaintenance = day + settings.maintenanceIntervalDays;
        if (plan) nextTick = day + settings.planningIntervalDays;
        long started = System.nanoTime();
        PassSnapshot snapshot = new PassSnapshot();
        if (maintain && !journeys.isEmpty()) maintainJourneys(snapshot);
        if (plan && settings.enabled) planTraffic(snapshot, settings);
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
            if (journey.advance(day, snapshot)) {
                debug("Finished tracking " + journey.typeId + " from " + journey.originId);
                iterator.remove();
            }
        }
    }

    private void planTraffic(Supplier<SectorSnapshot> snapshot, LivingSectorSettings settings) {
        // Release native arrivals even if maintenance is configured less frequently than planning.
        for (Iterator<TrafficJourney> iterator = journeys.iterator(); iterator.hasNext();) {
            if (!iterator.next().fleet.isAlive()) iterator.remove();
        }
        if (journeys.size() >= settings.globalFleetLimit) return;
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
        for (TrafficPolicy policy : policies) {
            if (journeys.size() >= settings.globalFleetLimit) break;
            try {
                TrafficPlan plan = scheduler.evaluate(policy, snapshot.get(), active, day,
                        settings.planningIntervalDays, random);
                if (plan == null) continue;
                CampaignFleetAPI fleet = CivilianFleetFactory.spawn(plan, random);
                if (fleet == null) continue;
                TrafficJourney journey = new TrafficJourney(fleet, plan, day);
                journeys.add(journey);
                active.add(journey.route());
                scheduler.recordDeparture(plan, day);
                spawned++;
                debug("Spawned " + plan.typeId + ": " + plan.originId + " -> " + plan.destinationId
                        + "; active=" + journeys.size() + "; target=" + scheduler.target(plan.typeId));
            } catch (RuntimeException ex) {
                retryAfter.put(policy.getId(), day + 30);
                Global.getLogger(TrafficManager.class).error("Living Sector policy failed: "
                        + policy.getId() + "; retrying in 30 campaign days", ex);
            }
        }
    }

    /** Available from Console Commands: runcode ...Console.showMessage(TrafficManager.status()); */
    public static String status() {
        if (Global.getSector() == null) return "No campaign loaded";
        for (EveryFrameScript script : Global.getSector().getScripts()) {
            if (!(script instanceof TrafficManager)) continue;
            TrafficManager manager = (TrafficManager) script;
            StringBuilder out = new StringBuilder("Living Sector: active=").append(manager.journeys.size())
                    .append(", total spawned=").append(manager.spawned)
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
                out.append('\n').append(journey.typeId).append(": ").append(journey.originId)
                        .append(" -> ").append(journey.destinationId)
                        .append(" (").append(journey.fleet.getContainingLocation() == null ? "despawned"
                                : journey.fleet.getContainingLocation().getName()).append(')');
            }
            return out.toString();
        }
        return "Living Sector manager is not installed";
    }

    static void debug(String message) {
        if (LivingSectorPlugin.settings().debugLogging) Global.getLogger(TrafficManager.class).info(message);
    }
}
