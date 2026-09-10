package livingsector.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.procgen.themes.RouteFleetAssignmentAI;

/** Native placement and travel, with passive civilian stops and a final docking assignment. */
public final class NativeTrafficAssignmentAI extends RouteFleetAssignmentAI {
    public NativeTrafficAssignmentAI(CampaignFleetAPI fleet, RouteData route) { super(fleet, route); }

    @Override public void advance(float amount) {
        NativeMission entry = NativeTraffic.entry(route);
        if (entry == null || entry.cancelReason != null || !entry.mission.active() || fleet.getBattle() != null) return;
        // An elapsed final visit is normal completion, not the base class's expired-route return fallback.
        super.advance(amount, false);
        NativeTraffic.observeProgress(entry);
    }

    @Override protected void addLocalAssignment(RouteSegment current, boolean justSpawned) {
        if (justSpawned) RouteLocationCalculator.setLocation(fleet, current.getProgress(), current.from, current.from);
        fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, current.from, Math.max(.01f, current.daysMax - current.elapsed),
                "visiting " + current.from.getName(), goNextScript(current));
    }

    @Override protected String getTravelActionText(RouteSegment segment) { return "transporting passengers to " + segment.to.getName(); }
    @Override protected String getStartingActionText(RouteSegment segment) { return "boarding passengers at " + segment.from.getName(); }
    @Override protected String getEndingActionText(RouteSegment segment) { return "disembarking passengers at " + segment.getDestination().getName(); }
}
