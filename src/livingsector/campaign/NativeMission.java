package livingsector.campaign;

import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import livingsector.model.TrafficMission;
import livingsector.model.FleetBudget;

/** Native bindings stay in the campaign adapter, outside the pure mission model. */
final class NativeMission {
    final TrafficMission mission;
    RouteData route;
    FleetCheckpoint checkpoint; // Legacy serialized field, released on aggregate migration.
    FleetBudget budget; // Absent in pre-aggregate saves.
    String cancelReason;
    String returnMarketId;
    boolean returning;
    int distanceDespawns, battles;
    transient Integer debugSegment;
    transient String debugMovement;
    transient Float debugPreviousFP;
    NativeMission(TrafficMission mission) { this.mission = mission; }
}
