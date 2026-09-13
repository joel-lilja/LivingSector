package livingsector.traffic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import livingsector.model.SectorSnapshot;
import livingsector.model.SectorSnapshot.Port;

/** One civilian budget; three distinct trip purposes compete for each departure opportunity. */
public final class CivilianTrafficPolicy implements TrafficPolicy {
    public static final String ID = "civilian", LOCAL = "civilian_local", LINER = "civilian_liner", CHARTER = "civilian_charter";
    public static final class Config {
        public boolean enabled = true, includeStations = true, localEnabled = true, linerEnabled = true, charterEnabled = true;
        public int minimumMarketSize = 3, hardLimit = 30;
        public double baseTarget = 2, marketsPerAdditionalFleet = 12, maximumTarget = 20;
        public double targetVariation = .25, targetRerollDays = 25, dailySpawnChance = .35, originCooldownDays = 10;
        public double localWeight = 5, linerWeight = 3, charterWeight = 2, homeFactionPreference = .65;
        public double localReturnChance = .35, linerReturnChance = .5, charterReturnChance = .25;
    }
    private Config config;
    public CivilianTrafficPolicy(Config config) { this.config = config; }
    public void updateConfig(Config config) { this.config = config; }
    public String getId() { return ID; }
    public boolean requiresNativeRoutes() { return true; }
    public boolean acceptsType(String type) { return isCivilian(type); }
    public static boolean isCivilian(String type) { return LOCAL.equals(type) || LINER.equals(type) || CHARTER.equals(type) || "vip".equals(type); }
    public boolean enabled() { return config.enabled && (weight(LOCAL) + weight(LINER) + weight(CHARTER) > 0); }
    public TrafficBudget budget(SectorSnapshot sector) {
        int ports = ports(sector).size();
        double target = !enabled() || ports < 2 ? 0 : Math.min(config.maximumTarget, config.baseTarget + ports / config.marketsPerAdditionalFleet);
        return new TrafficBudget(target, config.targetVariation, config.targetRerollDays, config.dailySpawnChance, config.hardLimit, config.originCooldownDays);
    }
    public TrafficPlan plan(TrafficContext context, Random random) {
        if (!enabled()) return null;
        List<Port> ports = ports(context.sector);
        List<String> types = new ArrayList<String>();
        for (String type : new String[]{LOCAL, LINER, CHARTER}) if (weight(type) > 0) types.add(type);
        // A type with no valid routes must not suppress other kinds of travel.
        while (!types.isEmpty()) {
            String type = pickType(types, random); types.remove(type);
            boolean returnTrip = random.nextDouble() < (LOCAL.equals(type) ? config.localReturnChance
                    : LINER.equals(type) ? config.linerReturnChance : config.charterReturnChance);
            List<Port> origins = new ArrayList<Port>();
            for (Port port : ports) if (context.canDepart(port.id)) origins.add(port);
            while (!origins.isEmpty()) {
                Port from = pickPort(origins, null, random); origins.remove(from);
                List<Port> destinations = new ArrayList<Port>();
                for (Port to : ports) {
                    boolean sameSystem = from.systemId.equals(to.systemId);
                    if (from.id.equals(to.id) || (LOCAL.equals(type) && !sameSystem) || (LINER.equals(type) && sameSystem)
                            || !context.sector.peaceful(from.factionId, to.factionId) || context.civilianConflict(from.id, to.id, returnTrip)) continue;
                    destinations.add(to);
                }
                if (destinations.isEmpty()) continue;
                Port to = pickPort(destinations, from, random);
                if (LOCAL.equals(type)) return TrafficPlan.civilian(type, from.id, to.id, "Passenger Shuttle",
                        new CivilianFleetRequest(30, 1, 2, config.homeFactionPreference), .25f, .25f, 30f, returnTrip);
                if (LINER.equals(type)) return TrafficPlan.civilian(type, from.id, to.id, "Passenger Liner",
                        new CivilianFleetRequest(150 * Math.max(1, Math.min(from.size, to.size) - 2), 3, 3, config.homeFactionPreference),
                        2f, 1.5f, 180f, returnTrip);
                return TrafficPlan.civilian(type, from.id, to.id, "Private Charter",
                        new CivilianFleetRequest(10, 1, 2, config.homeFactionPreference), .5f, .25f, 90f, returnTrip);
            }
        }
        return null;
    }
    private List<Port> ports(SectorSnapshot sector) {
        List<Port> result = new ArrayList<Port>();
        for (Port port : sector.ports) if (port.size >= config.minimumMarketSize && (port.planet || config.includeStations)) result.add(port);
        return result;
    }
    private double weight(String type) {
        if (LOCAL.equals(type)) return config.localEnabled ? config.localWeight : 0;
        if (LINER.equals(type)) return config.linerEnabled ? config.linerWeight : 0;
        return config.charterEnabled ? config.charterWeight : 0;
    }
    private String pickType(List<String> types, Random random) {
        double total = 0; for (String type : types) total += weight(type);
        double roll = random.nextDouble() * total;
        for (String type : types) { roll -= weight(type); if (roll < 0) return type; }
        return types.get(types.size() - 1);
    }
    private Port pickPort(List<Port> ports, Port from, Random random) {
        double total = 0; for (Port port : ports) total += portWeight(port, from);
        double roll = random.nextDouble() * total;
        for (Port port : ports) { roll -= portWeight(port, from); if (roll < 0) return port; }
        return ports.get(ports.size() - 1);
    }
    private double portWeight(Port port, Port from) {
        return Math.sqrt(Math.max(1, port.size - 2) / (from == null ? 1 : 1 + from.distanceLY(port) / 10));
    }
}
