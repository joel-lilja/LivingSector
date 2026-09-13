package livingsector.traffic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Description of a civilian point-to-point trip; execution belongs to the game adapter. */
public final class TrafficPlan {
    public final String typeId, originId, destinationId, fleetName;
    public final List<String> variants;
    public final float boardingDays, maximumTripDays;
    // Absent in old saves: null/false/zero preserve the original executor and itinerary.
    public final String budgetId;
    public final CivilianFleetRequest fleetRequest;
    public final boolean nativeRoute, roundTrip;
    public final float arrivalDays;

    public TrafficPlan(String typeId, String originId, String destinationId, String fleetName,
                       List<String> variants, float boardingDays, float maximumTripDays) {
        this(typeId, originId, destinationId, fleetName, variants, boardingDays, maximumTripDays,
                typeId, null, false, false, 1f);
    }
    private TrafficPlan(String typeId, String originId, String destinationId, String fleetName,
                        List<String> variants, float boardingDays, float maximumTripDays, String budgetId,
                        CivilianFleetRequest fleetRequest, boolean nativeRoute, boolean roundTrip, float arrivalDays) {
        if (typeId == null || originId == null || destinationId == null
                || originId.equals(destinationId) || fleetName == null || variants == null || (variants.isEmpty() && fleetRequest == null)
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
        this.budgetId = budgetId; this.fleetRequest = fleetRequest; this.nativeRoute = nativeRoute;
        this.roundTrip = roundTrip; this.arrivalDays = arrivalDays;
    }
    public static TrafficPlan civilian(String type, String from, String to, String name, CivilianFleetRequest request,
                                       float boarding, float arrival, float lifetime, boolean returnTrip) {
        if (request == null || !Float.isFinite(arrival) || arrival <= 0) throw new IllegalArgumentException("Invalid civilian itinerary");
        return new TrafficPlan(type, from, to, name, Collections.<String>emptyList(), boarding, lifetime,
                CivilianTrafficPolicy.ID, request, true, returnTrip, arrival);
    }
    public TrafficPlan withVariants(List<String> selected) {
        if (selected == null || selected.isEmpty()) throw new IllegalArgumentException("A fleet needs selected variants");
        return new TrafficPlan(typeId, originId, destinationId, fleetName, selected, boardingDays, maximumTripDays,
                budgetId, fleetRequest, nativeRoute, roundTrip, arrivalDays);
    }
    /** New civilian missions keep a generation profile; explicit legacy/test rosters remain templates. */
    public TrafficPlan withoutSelectedVariants() {
        if (fleetRequest == null || variants.isEmpty()) return this;
        return new TrafficPlan(typeId, originId, destinationId, fleetName, Collections.<String>emptyList(),
                boardingDays, maximumTripDays, budgetId, fleetRequest, nativeRoute, roundTrip, arrivalDays);
    }
    public String budgetId() { return budgetId == null ? typeId : budgetId; }
}
