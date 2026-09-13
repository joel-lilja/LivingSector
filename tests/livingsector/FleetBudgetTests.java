package livingsector;

import java.util.*;
import livingsector.model.FleetBudget;
import livingsector.traffic.*;

final class FleetBudgetTests {
    static void run() {
        Random random = new Random(8923);
        for (int trial = 0; trial < 5000; trial++) {
            float initial = 1 + random.nextInt(1000);
            float damage = random.nextFloat(), middle = damage / 2;
            FleetBudget direct = new FleetBudget(initial), split = new FleetBudget(initial);
            direct.applyAbstract(damage);
            split.applyAbstract(middle); split.applyAbstract(damage);
            check(Math.abs(direct.remaining - initial * (1 - damage)) < .0002, "Budget conserves remaining fraction");
            check(Math.abs(direct.remaining - split.remaining) < .0002, "Observation frequency does not change loss");
            check(!direct.applyAbstract(damage) && !direct.applyAbstract(middle), "Repeated/lowered damage is idempotent");
            if (direct.remaining > 0) {
                float allowance = direct.remaining, generated = allowance * .8f;
                direct.beginPhysical(generated); direct.endPhysical(generated, damage);
                check(direct.remaining == allowance, "Generation rounding does not consume allowance");
                direct.beginPhysical(generated); direct.endPhysical(generated / 2, damage);
                check(Math.abs(direct.remaining - allowance / 2) < .0002, "Physical casualties reduce allowance proportionally");
                direct.endPhysical(generated / 2, damage);
                check(Math.abs(direct.remaining - allowance / 2) < .0002, "Duplicate dematerialization cannot charge twice");
            }
            direct.applyAbstract(2);
            check(direct.remaining == 0 && direct.routeDamage == 1, "Full loss is terminal");
        }
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            boolean rejected = false;
            try { new FleetBudget(3).applyAbstract(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Non-finite input is diagnosed");
        }
        List<TrafficContext.Route> routes = new ArrayList<TrafficContext.Route>();
        String[] types = {CivilianTrafficPolicy.LOCAL, CivilianTrafficPolicy.LINER, "vip", "other"};
        for (int i = 0; i < 5000; i++) routes.add(new TrafficContext.Route(types[i % 4], "p" + random.nextInt(500),
                "p" + random.nextInt(500), random.nextBoolean()));
        TrafficContext context = new TrafficContext(null, routes, 0, 0, Collections.<String, Double>emptyMap());
        check(context.count(new CivilianTrafficPolicy(new CivilianTrafficPolicy.Config())) == 3750, "Indexed counts include all civilian subtypes and legacy VIPs");
        for (int trial = 0; trial < 1000; trial++) {
            String from = "p" + random.nextInt(500), to = "p" + random.nextInt(500), type = types[trial % 4];
            boolean returning = random.nextBoolean(), conflict = false, active = false;
            for (TrafficContext.Route route : routes) {
                if (route.typeId.equals(type) && route.originId.equals(from) && route.destinationId.equals(to)) active = true;
                if (CivilianTrafficPolicy.isCivilian(route.typeId) && ((route.originId.equals(from) && route.destinationId.equals(to))
                        || ((returning || route.roundTrip) && route.originId.equals(to) && route.destinationId.equals(from)))) conflict = true;
            }
            check(context.routeActive(type, from, to) == active && context.civilianConflict(from, to, returning) == conflict,
                    "Indexed queries match independent scan including both return-trip directions");
        }
        routes.clear();
        check(context.count(new CivilianTrafficPolicy(new CivilianTrafficPolicy.Config())) == 3750,
                "Mutating source routes cannot invalidate a planning snapshot");
        check(new TrafficContext(null, routes, 0, 0, Collections.<String, Double>emptyMap())
                .count(new CivilianTrafficPolicy(new CivilianTrafficPolicy.Config())) == 0, "Next snapshot releases completed routes");
        System.out.println("PASS: 5000 aggregate-budget trials and 1000 indexed queries over 5000 routes");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
