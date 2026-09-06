package livingsector.traffic;

/** A policy may calculate a new budget from current game state each planning pass. */
public final class TrafficBudget {
    public final double target, variation, rerollDays, dailyChance, originCooldownDays;
    public final int hardLimit;

    public TrafficBudget(double target, double variation, double rerollDays,
                         double dailyChance, int hardLimit, double originCooldownDays) {
        if (!finite(target) || target < 0 || !finite(variation) || variation < 0 || variation > 1
                || !finite(rerollDays) || rerollDays <= 0 || !finite(dailyChance)
                || dailyChance < 0 || dailyChance > 1 || hardLimit < 1
                || !finite(originCooldownDays) || originCooldownDays < 0) {
            throw new IllegalArgumentException("Invalid traffic budget");
        }
        this.target = target;
        this.variation = variation;
        this.rerollDays = rerollDays;
        this.dailyChance = dailyChance;
        this.hardLimit = hardLimit;
        this.originCooldownDays = originCooldownDays;
    }

    public static double spawnChance(int active, double target, double dailyChance) {
        if (target <= 0) return 0;
        return dailyChance / (1 + Math.pow(active / target, 4));
    }

    /** Probability of at least one opportunity over a window, not a backlog of fleets. */
    public static double intervalChance(double dailyChance, double days) {
        if (!finite(days) || days <= 0) throw new IllegalArgumentException("Invalid planning interval");
        return 1 - Math.pow(1 - dailyChance, days);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
