package livingsector.traffic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import livingsector.model.SectorSnapshot;

public final class TrafficContext {
    public static final class Route {
        public final String typeId, originId, destinationId;

        public Route(String typeId, String originId, String destinationId) {
            this.typeId = typeId;
            this.originId = originId;
            this.destinationId = destinationId;
        }
    }

    public final SectorSnapshot sector;
    public final List<Route> activeRoutes;
    public final double day, originCooldownDays;
    private final Map<String, Double> lastDepartures;

    public TrafficContext(SectorSnapshot sector, List<Route> activeRoutes, double day,
                          double originCooldownDays, Map<String, Double> lastDepartures) {
        this.sector = sector;
        this.activeRoutes = Collections.unmodifiableList(new ArrayList<Route>(activeRoutes));
        this.day = day;
        this.originCooldownDays = originCooldownDays;
        this.lastDepartures = Collections.unmodifiableMap(new HashMap<String, Double>(lastDepartures));
    }

    public boolean canDepart(String originId) {
        Double last = lastDepartures.get(originId);
        return last == null || day - last >= originCooldownDays;
    }

    public boolean routeActive(String typeId, String origin, String destination) {
        for (Route route : activeRoutes) {
            if (route.typeId.equals(typeId) && route.originId.equals(origin)
                    && route.destinationId.equals(destination)) return true;
        }
        return false;
    }
}
