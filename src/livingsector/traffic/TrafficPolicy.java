package livingsector.traffic;

import java.util.Random;
import livingsector.model.SectorSnapshot;

/** Implement this to add a traffic algorithm. Policies are stateless and are not saved. */
public interface TrafficPolicy {
    String getId();
    TrafficBudget budget(SectorSnapshot sector);
    /** Return null when there is currently no suitable trip. */
    TrafficPlan plan(TrafficContext context, Random random);
}
