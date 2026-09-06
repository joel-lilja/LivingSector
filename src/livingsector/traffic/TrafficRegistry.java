package livingsector.traffic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Register additional policies during onApplicationLoad(). Registrations are not serialized. */
public final class TrafficRegistry {
    private static final Map<String, TrafficPolicy> POLICIES = new LinkedHashMap<String, TrafficPolicy>();
    private TrafficRegistry() { }

    public static void register(TrafficPolicy policy) {
        if (policy == null || policy.getId() == null || policy.getId().trim().isEmpty()) {
            throw new IllegalArgumentException("Traffic policy requires an ID");
        }
        if (POLICIES.containsKey(policy.getId())) {
            throw new IllegalArgumentException("Duplicate traffic policy: " + policy.getId());
        }
        POLICIES.put(policy.getId(), policy);
    }

    public static List<TrafficPolicy> policies() { return new ArrayList<TrafficPolicy>(POLICIES.values()); }
}
