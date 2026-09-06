package livingsector.traffic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Description of a civilian point-to-point trip; execution belongs to the game adapter. */
public final class TrafficPlan {
    public final String typeId, originId, destinationId, fleetName;
    public final List<String> variants;
    public final float boardingDays, maximumTripDays;

    public TrafficPlan(String typeId, String originId, String destinationId, String fleetName,
                       List<String> variants, float boardingDays, float maximumTripDays) {
        if (typeId == null || originId == null || destinationId == null
                || originId.equals(destinationId) || fleetName == null || variants.isEmpty()
                || !Float.isFinite(boardingDays) || boardingDays < 0
                || !Float.isFinite(maximumTripDays) || maximumTripDays <= boardingDays) {
            throw new IllegalArgumentException("Invalid traffic plan");
        }
        this.typeId = typeId;
        this.originId = originId;
        this.destinationId = destinationId;
        this.fleetName = fleetName;
        this.variants = Collections.unmodifiableList(new ArrayList<String>(variants));
        this.boardingDays = boardingDays;
        this.maximumTripDays = maximumTripDays;
    }
}
