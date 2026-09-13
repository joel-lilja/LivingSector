package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickMode;
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickParams;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.ShipRolePick;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import livingsector.traffic.CivilianFleetRequest;
import livingsector.traffic.TrafficPlan;

/** Bounded faction-role selection at admission and materialization; no per-frame hull scans. */
final class CivilianShipSelector {
    private CivilianShipSelector() { }
    static TrafficPlan resolve(TrafficPlan plan, String factionId, long seed) {
        if (plan.fleetRequest == null || !plan.variants.isEmpty()) return plan;
        return generate(plan, factionId, seed, Float.MAX_VALUE);
    }
    static TrafficPlan generate(TrafficPlan plan, String factionId, long seed, float budget) {
        if (!Float.isFinite(budget) || budget < 0) throw new IllegalArgumentException("Invalid generation budget");
        if (plan.fleetRequest == null) {
            // Old explicit plans and console experiments use their variant IDs as a bounded template.
            List<String> selected = new ArrayList<String>();
            for (String variant : plan.variants) {
                float points = points(variant);
                if (points <= budget) { selected.add(variant); budget -= points; }
            }
            return selected.isEmpty() ? null : plan.withVariants(selected);
        }
        CivilianFleetRequest request = plan.fleetRequest;
        FactionAPI home = Global.getSector().getFaction(factionId), independent = Global.getSector().getFaction("independent");
        Random random = new Random(seed);
        List<String> variants = new ArrayList<String>();
        float seats = 0;
        for (int ship = 0; ship < request.maximumShips && seats < request.passengerCapacity; ship++) {
            boolean homeFirst = random.nextDouble() < request.homeFactionPreference;
            FactionAPI first = homeFirst ? home : independent, second = homeFirst ? independent : home;
            String variant = pick(first, request, random, budget);
            if (variant == null && second != first) variant = pick(second, request, random, budget);
            if (variant == null) break;
            variants.add(variant);
            budget -= points(variant);
            ShipHullSpecAPI hull = Global.getSettings().getVariant(variant).getHullSpec();
            seats += Math.max(0, hull.getMaxCrew() - hull.getMinCrew());
        }
        // Capacity is a desired fleet size, not a guarantee of actual cargo/passenger simulation.
        return variants.isEmpty() ? null : plan.withVariants(variants);
    }
    private static String pick(FactionAPI faction, CivilianFleetRequest request, Random random, float budget) {
        if (faction == null) return null;
        int preferred = request.passengerCapacity < 150 ? (random.nextDouble() < .75 ? 1 : 2)
                : request.passengerCapacity >= 500 ? 3 : 2;
        preferred = Math.min(preferred, request.maximumHullSize);
        int[] sizes = {preferred, 1, 2, 3};
        boolean[] tried = new boolean[4];
        for (int size : sizes) {
            if (tried[size] || size > request.maximumHullSize) continue;
            tried[size] = true;
            String suffix = size == 1 ? "Small" : size == 2 ? "Medium" : "Large";
            for (String role : new String[]{"personnel" + suffix, "liner" + suffix}) {
                List<ShipRolePick> picks = faction.pickShip(role, new ShipPickParams(ShipPickMode.ALL, (int) Math.min(1000, budget), null, true),
                        id -> suitable(id, request, budget), random);
                if (picks != null) for (ShipRolePick pick : picks) {
                    if (pick != null && !pick.isFighterWing() && suitable(pick.variantId, request, budget)) return pick.variantId;
                }
            }
        }
        return null;
    }
    static float points(TrafficPlan plan) {
        float total = 0;
        for (String variant : plan.variants) total += points(variant);
        return total;
    }
    private static float points(String id) {
        ShipVariantAPI variant = Global.getSettings().getVariant(id);
        float points = variant == null || variant.getHullSpec() == null ? 0 : variant.getHullSpec().getFleetPoints();
        if (!Float.isFinite(points) || points <= 0) throw new IllegalArgumentException("Missing or invalid fleet points: " + id);
        return points;
    }
    private static boolean suitable(String id, CivilianFleetRequest request, float budget) {
        if (id == null) return false;
        ShipVariantAPI variant = Global.getSettings().getVariant(id);
        if (variant == null) return false;
        ShipHullSpecAPI hull = variant.getHullSpec();
        if (hull == null || !hull.isCivilianNonCarrier() || hull.getHints().contains(ShipTypeHints.STATION)
                || hull.getHints().contains(ShipTypeHints.MODULE) || hull.getHints().contains(ShipTypeHints.UNBOARDABLE)
                || !(hull.getHints().contains(ShipTypeHints.TRANSPORT) || hull.getHints().contains(ShipTypeHints.LINER))) return false;
        int size = hull.getHullSize() == HullSize.FRIGATE ? 1 : hull.getHullSize() == HullSize.DESTROYER ? 2
                : hull.getHullSize() == HullSize.CRUISER ? 3 : 99;
        return size <= request.maximumHullSize && hull.getMaxCrew() > hull.getMinCrew()
                && Float.isFinite(hull.getFleetPoints()) && hull.getFleetPoints() > 0 && hull.getFleetPoints() <= budget;
    }
}
