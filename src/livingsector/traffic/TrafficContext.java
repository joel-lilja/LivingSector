package livingsector.traffic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import livingsector.model.SectorSnapshot;

public final class TrafficContext {
    public static final class Route {
        public final String typeId, originId, destinationId;
        public final boolean roundTrip;

        public Route(String typeId, String originId, String destinationId) {
            this(typeId, originId, destinationId, false);
        }
        public Route(String typeId, String originId, String destinationId, boolean roundTrip) {
            this.typeId = typeId;
            this.originId = originId;
            this.destinationId = destinationId;
            this.roundTrip = roundTrip;
        }
    }

    public final SectorSnapshot sector;
    public final List<Route> activeRoutes;
    public final double day, originCooldownDays;
    private final Map<String, Double> lastDepartures;
    private final Map<String, Integer> counts = new HashMap<String, Integer>();
    private final Map<String, Set<Pair>> pairs = new HashMap<String, Set<Pair>>();
    private final Map<Pair, Boolean> civilianPairs = new HashMap<Pair, Boolean>();

    private static final class Pair {
        final String from, to;
        Pair(String from, String to) { this.from = from; this.to = to; }
        @Override public int hashCode() { return 31 * from.hashCode() + to.hashCode(); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Pair)) return false;
            Pair pair = (Pair) other;
            return from.equals(pair.from) && to.equals(pair.to);
        }
    }

    public TrafficContext(SectorSnapshot sector, List<Route> activeRoutes, double day,
                          double originCooldownDays, Map<String, Double> lastDepartures) {
        this.sector = sector;
        this.activeRoutes = Collections.unmodifiableList(new ArrayList<Route>(activeRoutes));
        this.day = day;
        this.originCooldownDays = originCooldownDays;
        this.lastDepartures = Collections.unmodifiableMap(new HashMap<String, Double>(lastDepartures));
        // A planning snapshot owns its indexes. Rebuilding on each pass avoids stale saved caches.
        for (Route route : this.activeRoutes) {
            counts.put(route.typeId, counts.getOrDefault(route.typeId, 0) + 1);
            Pair pair = new Pair(route.originId, route.destinationId);
            Set<Pair> routes = pairs.get(route.typeId);
            if (routes == null) { routes = new HashSet<Pair>(); pairs.put(route.typeId, routes); }
            routes.add(pair);
            if (CivilianTrafficPolicy.isCivilian(route.typeId))
                civilianPairs.put(pair, route.roundTrip || Boolean.TRUE.equals(civilianPairs.get(pair)));
        }
    }

    public boolean canDepart(String originId) {
        Double last = lastDepartures.get(originId);
        return last == null || day - last >= originCooldownDays;
    }

    public boolean routeActive(String typeId, String origin, String destination) {
        Set<Pair> routes = pairs.get(typeId);
        return routes != null && routes.contains(new Pair(origin, destination));
    }
    public int count(TrafficPolicy policy) {
        int result = 0;
        for (Map.Entry<String, Integer> count : counts.entrySet())
            if (policy.acceptsType(count.getKey())) result += count.getValue();
        return result;
    }
    public boolean civilianConflict(String origin, String destination, boolean returning) {
        if (civilianPairs.containsKey(new Pair(origin, destination))) return true;
        Boolean reverse = civilianPairs.get(new Pair(destination, origin));
        return reverse != null && (returning || reverse);
    }
}
