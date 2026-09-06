package livingsector.traffic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import livingsector.model.SectorSnapshot;
import livingsector.model.SectorSnapshot.Port;

/** First policy: occasional private travel, mildly biased toward larger colonies and shorter routes. */
public final class VipTrafficPolicy implements TrafficPolicy {
    public static final String ID = "vip";

    public static final class Config {
        public boolean enabled = true, includeStations = true;
        public int minimumMarketSize = 3, hardLimit = 30;
        public double baseTarget = 2, marketsPerAdditionalFleet = 12, maximumTarget = 20;
        public double targetVariation = .25, targetRerollDays = 25, dailySpawnChance = .35;
        public double originCooldownDays = 10;
        public float maximumTripDays = 180, boardingDays = .5f;
        public String variant = "mudskipper_Standard";
    }

    private final Config config;
    public VipTrafficPolicy(Config config) { this.config = config; }
    public String getId() { return ID; }

    public TrafficBudget budget(SectorSnapshot sector) {
        int ports = eligiblePorts(sector).size();
        double target = !config.enabled || ports < 2 ? 0 : Math.min(config.maximumTarget,
                config.baseTarget + ports / config.marketsPerAdditionalFleet);
        return new TrafficBudget(target, config.targetVariation, config.targetRerollDays,
                config.dailySpawnChance, config.hardLimit, config.originCooldownDays);
    }

    public TrafficPlan plan(TrafficContext context, Random random) {
        if (!config.enabled) return null;
        List<Port> origins = eligiblePorts(context.sector);
        List<Port> destinations = new ArrayList<Port>(origins);
        for (int i = origins.size() - 1; i >= 0; i--) {
            if (!context.canDepart(origins.get(i).id)) origins.remove(i);
        }
        // Weighted picks without replacement ensure a dead-end origin cannot suppress all traffic.
        while (!origins.isEmpty()) {
            Port from = pick(origins, null, random);
            origins.remove(from);
            List<Port> candidates = new ArrayList<Port>();
            for (Port to : destinations) {
                if (!from.id.equals(to.id) && context.sector.peaceful(from.factionId, to.factionId)
                        && !context.routeActive(ID, from.id, to.id)) candidates.add(to);
            }
            if (candidates.isEmpty()) continue;
            Port to = pick(candidates, from, random);
            return new TrafficPlan(ID, from.id, to.id, "VIP Shuttle",
                    Collections.singletonList(config.variant), config.boardingDays, config.maximumTripDays);
        }
        return null;
    }

    private List<Port> eligiblePorts(SectorSnapshot sector) {
        List<Port> result = new ArrayList<Port>();
        for (Port port : sector.ports) {
            if (port.size >= config.minimumMarketSize && (port.planet || config.includeStations)) result.add(port);
        }
        return result;
    }

    private Port pick(List<Port> ports, Port origin, Random random) {
        double total = 0;
        for (Port port : ports) total += weight(port, origin);
        double roll = random.nextDouble() * total;
        for (Port port : ports) {
            roll -= weight(port, origin);
            if (roll < 0) return port;
        }
        return ports.get(ports.size() - 1);
    }

    private double weight(Port port, Port origin) {
        double size = Math.max(1, port.size - 2);
        // Compress both size and distance advantages while preserving their ordering.
        double weight = origin == null ? size : size / (1 + origin.distanceLY(port) / 10);
        return Math.sqrt(weight);
    }
}
