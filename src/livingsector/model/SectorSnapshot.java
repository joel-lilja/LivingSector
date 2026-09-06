package livingsector.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable decision input. No live game objects are exposed to traffic policies. */
public final class SectorSnapshot {
    public static final class Port {
        public final String id, name, factionId, systemId;
        public final int size;
        public final float stability, xLY, yLY;
        public final boolean planet, playerOwned;

        public Port(String id, String name, String factionId, String systemId,
                    int size, float stability, float xLY, float yLY,
                    boolean planet, boolean playerOwned) {
            this.id = id;
            this.name = name;
            this.factionId = factionId;
            this.systemId = systemId;
            this.size = size;
            this.stability = stability;
            this.xLY = xLY;
            this.yLY = yLY;
            this.planet = planet;
            this.playerOwned = playerOwned;
        }

        public double distanceLY(Port other) {
            return Math.hypot(xLY - other.xLY, yLY - other.yLY);
        }
    }

    public final List<Port> ports;
    private final Map<String, Port> byId;
    private final Map<String, Set<String>> hostilities;

    public SectorSnapshot(List<Port> ports, Map<String, Set<String>> hostilities) {
        this.ports = Collections.unmodifiableList(new ArrayList<Port>(ports));
        byId = new LinkedHashMap<String, Port>();
        for (Port port : ports) byId.put(port.id, port);
        this.hostilities = new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, Set<String>> entry : hostilities.entrySet()) {
            this.hostilities.put(entry.getKey(), new HashSet<String>(entry.getValue()));
        }
    }

    public Port port(String id) { return byId.get(id); }

    /** Check both directions, including asymmetric relations supplied by other mods. */
    public boolean peaceful(String first, String second) {
        return !hostile(first, second) && !hostile(second, first);
    }

    private boolean hostile(String first, String second) {
        Set<String> enemies = hostilities.get(first);
        return enemies != null && enemies.contains(second);
    }
}
