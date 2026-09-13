package livingsector.model;

/** Saved aggregate civilian size. Physical ships are authoritative until dematerialization. */
public final class FleetBudget {
    public final int version = 1;
    public float initial, remaining, routeDamage, physicalFP;

    public FleetBudget(float points) {
        if (!Float.isFinite(points) || points < 0) throw new IllegalArgumentException("Invalid fleet budget");
        initial = remaining = points;
    }

    public static FleetBudget migrate(float survivors, float accountedDamage) {
        FleetBudget result = new FleetBudget(survivors);
        result.routeDamage = normalized(accountedDamage);
        return result;
    }

    /** Returns whether new abstract damage was accounted for, including fractional losses. */
    public boolean applyAbstract(float damage) {
        float current = normalized(damage);
        if (current <= routeDamage) return false;
        remaining = current == 1 ? 0 : (float) (remaining * (1.0 - current) / (1.0 - routeDamage));
        routeDamage = current;
        return true;
    }

    public void beginPhysical(float points) {
        if (!Float.isFinite(points) || points <= 0 || points > remaining + .0001f || physicalFP > 0)
            throw new IllegalArgumentException("Physical fleet exceeds budget or already exists");
        physicalFP = points;
    }

    /** Capture once, after native battle callbacks; rounding and hull repairs do not change allowance. */
    public void endPhysical(float survivors, float damage) {
        if (!Float.isFinite(survivors) || survivors < 0) throw new IllegalArgumentException("Invalid survivor FP");
        float current = normalized(damage);
        if (physicalFP > 0) remaining *= Math.min(1, survivors / physicalFP);
        physicalFP = 0;
        routeDamage = Math.max(routeDamage, current);
    }

    public static float normalized(float damage) {
        if (!Float.isFinite(damage)) throw new IllegalArgumentException("Non-finite native damage");
        return Math.max(0, Math.min(1, damage));
    }
}
