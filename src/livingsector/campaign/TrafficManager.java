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

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return false; }

    public void advance(float amount) {
        if (Global.getSector().isPaused()) return;
        day += Global.getSector().getClock().convertToDays(amount);
        if (day < nextTick) return;
        nextTick = day + 1; // No catch-up burst after a large time step.
        tick();
    }

    private void tick() {
        Set<String> factions = new LinkedHashSet<String>();
        for (TrafficJourney journey : journeys) factions.add(journey.fleet.getFaction().getId());
        SectorSnapshot snapshot = SectorReader.capture(factions);
        Iterator<TrafficJourney> iterator = journeys.iterator();
        while (iterator.hasNext()) {
            TrafficJourney journey = iterator.next();
            if (journey.advance(snapshot, day)) {
                debug("Finished tracking " + journey.typeId + " from " + journey.originId);
                iterator.remove();
            }
        }
        LivingSectorSettings settings = LivingSectorPlugin.settings();
        // Disabling departures still lets existing journeys finish and be cleaned up.
        if (!settings.enabled) return;
        List<TrafficPolicy> policies = TrafficRegistry.policies();
        Collections.shuffle(policies, random); // Fair access to the shared fleet limit.
        for (TrafficPolicy policy : policies) {
            if (journeys.size() >= settings.globalFleetLimit) break;
            Double retry = retryAfter.get(policy.getId());
            if (retry != null && day < retry) continue;
            try {
                List<TrafficContext.Route> active = new ArrayList<TrafficContext.Route>();
                for (TrafficJourney journey : journeys) active.add(journey.route());
                TrafficPlan plan = scheduler.evaluate(policy, snapshot, active, day, random);
                if (plan == null) continue;
                CampaignFleetAPI fleet = CivilianFleetFactory.spawn(plan, random);
                if (fleet == null) continue;
                journeys.add(new TrafficJourney(fleet, plan, day));
                scheduler.recordDeparture(plan, day);
                spawned++;
                debug("Spawned " + plan.typeId + ": " + plan.originId + " -> " + plan.destinationId
                        + "; active=" + journeys.size() + "; target=" + scheduler.target(plan.typeId));
            } catch (RuntimeException ex) {
                // One faulty extension should not stop other traffic types or flood the log every frame.
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
                    .append(", total spawned=").append(manager.spawned);
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
