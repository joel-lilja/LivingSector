package livingsector.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.TacticalModulePlugin;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit, temporary diagnostic; samples one location, never changes another fleet's AI. */
final class DistractionProbe {
    private final LocationAPI location;
    private final double started, until;
    private double next, last;
    private double observedFleetDays, targetingDays, missedDays;
    private int samples, truncatedSamples, previousObservers, previousTargets;
    private boolean running = true;
    private final List<String> targets = new ArrayList<String>();

    DistractionProbe(LocationAPI location, double day, double duration) {
        if (location == null || !Double.isFinite(duration) || duration <= 0 || duration > 365) {
            throw new IllegalArgumentException("Probe duration must be between 0 and 365 campaign days");
        }
        this.location = location;
        started = last = next = day;
        until = day + duration;
    }

    void advance(double day) {
        if (!running || day < next) return;
        double elapsed = Math.max(0, Math.min(day, until) - last);
        // Never project one observed target across a long unobserved time jump.
        double covered = samples == 0 ? 0 : Math.min(elapsed, .25);
        observedFleetDays += previousObservers * covered;
        targetingDays += previousTargets * covered;
        missedDays += Math.max(0, elapsed - covered);
        last = Math.min(day, until);
        if (day >= until) { running = false; return; }
        next = day + .25;
        samples++;
        previousObservers = previousTargets = 0;
        targets.clear();
        int scanned = 0;
        for (CampaignFleetAPI fleet : location.getFleets()) {
            if (++scanned > 500) { truncatedSamples++; break; }
            if (!fleet.isAlive() || fleet.isPlayerFleet() || fleet.isStationMode()
                    || fleet.getMemoryWithoutUpdate().contains(CivilianFleetFactory.TYPE_KEY)
                    || fleet.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_TRADE_FLEET)
                    || !(fleet.getAI() instanceof ModularFleetAIAPI)) continue;
            previousObservers++;
            TacticalModulePlugin tactical = ((ModularFleetAIAPI) fleet.getAI()).getTacticalModule();
            SectorEntityToken target = tactical.getTarget();
            if (tactical.isFleeing() || !(target instanceof CampaignFleetAPI)
                    || !target.getMemoryWithoutUpdate().contains(CivilianFleetFactory.TYPE_KEY)) continue;
            previousTargets++;
            if (targets.size() < 6) {
                targets.add(fleet.getName() + " [" + fleet.getId() + "] -> " + target.getName()
                        + " [" + target.getId() + "]; pursuit=" + round(tactical.getPursuitDays()) + "d"
                        + "; assignment=" + (fleet.getCurrentAssignment() == null ? "none" : fleet.getCurrentAssignment().getAssignment()));
            }
        }
    }

    void stop(double day) { advance(day); running = false; }

    String summary() {
        StringBuilder out = new StringBuilder("Probe ").append(running ? "RUNNING" : "STOPPED")
                .append(" at ").append(location.getName()).append("; elapsed=").append(round(last - started))
                .append("d; samples=").append(samples).append("; NPC fleet-days observed=").append(round(observedFleetDays))
                .append("; targeting LS=").append(round(targetingDays)).append(" (")
                .append(round(observedFleetDays == 0 ? 0 : 100 * targetingDays / observedFleetDays)).append("%)")
                .append("; unsampled gap=").append(round(missedDays)).append("d; truncated samples=").append(truncatedSamples)
                .append("\nSample estimate of targeting, not proof that an operation was delayed.");
        for (String target : targets) out.append('\n').append(target);
        return out.toString();
    }

    private static String round(double value) { return String.format(Locale.ROOT, "%.2f", value); }
}
