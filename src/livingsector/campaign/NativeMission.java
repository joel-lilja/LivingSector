package livingsector.campaign;

import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import livingsector.model.TrafficMission;

/** Native bindings stay in the campaign adapter, outside the pure mission model. */
final class NativeMission {
    final TrafficMission mission;
    RouteData route;
    FleetCheckpoint checkpoint;
    String cancelReason;
    String returnMarketId;
    boolean returning;
    int distanceDespawns, battles;
    transient Integer debugSegment;
    transient String debugMovement;
    NativeMission(TrafficMission mission) { this.mission = mission; }
}
