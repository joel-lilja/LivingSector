package livingsector.traffic;

import java.util.Random;
import livingsector.model.SectorSnapshot;

/** Implement this to add a traffic algorithm. Policies are stateless and are not saved. */
public interface TrafficPolicy {
    String getId();
    default boolean acceptsType(String typeId) { return getId().equals(typeId); }
    default boolean enabled() { return true; }
    default boolean requiresNativeRoutes() { return false; }
    TrafficBudget budget(SectorSnapshot sector);
    /** Return null when there is currently no suitable trip. */
    TrafficPlan plan(TrafficContext context, Random random);
}
