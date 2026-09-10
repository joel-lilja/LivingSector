package livingsector;

import com.fs.starfarer.api.Global;
import livingsector.traffic.TrafficBudget;
import livingsector.traffic.VipTrafficPolicy;
import org.json.JSONObject;

public final class LivingSectorSettings {
    public boolean enabled = true, debugLogging = false;
    public boolean useNativeRoutes = false;
    public boolean debugTrafficHistory = false;
    public int debugHistoryMiB = 32;
    public int globalFleetLimit = 40;
    public double planningIntervalDays = 5, maintenanceIntervalDays = 2;
    public final VipTrafficPolicy.Config vip = new VipTrafficPolicy.Config();

    public static LivingSectorSettings load() throws Exception {
        JSONObject root = Global.getSettings().loadJSON("data/config/living_sector.json", LivingSectorPlugin.ID);
        LivingSectorSettings settings = new LivingSectorSettings();
        settings.enabled = root.getBoolean("enabled");
        settings.debugLogging = root.getBoolean("debugLogging");
        settings.useNativeRoutes = root.optBoolean("useNativeRoutes", false);
        settings.debugTrafficHistory = root.optBoolean("debugTrafficHistory", false);
        settings.debugHistoryMiB = root.optInt("debugHistoryMiB", 32);
        settings.globalFleetLimit = root.getInt("globalFleetLimit");
        settings.planningIntervalDays = root.optDouble("planningIntervalDays", 5);
        settings.maintenanceIntervalDays = root.optDouble("maintenanceIntervalDays", 2);
        JSONObject json = root.getJSONObject("vip");
        VipTrafficPolicy.Config vip = settings.vip;
        vip.enabled = json.getBoolean("enabled");
        vip.includeStations = json.getBoolean("includeStations");
        vip.minimumMarketSize = json.getInt("minimumMarketSize");
        vip.baseTarget = json.getDouble("baseTarget");
        vip.marketsPerAdditionalFleet = json.getDouble("marketsPerAdditionalFleet");
        vip.maximumTarget = json.getDouble("maximumTarget");
        vip.targetVariation = json.getDouble("targetVariation");
        vip.targetRerollDays = json.getDouble("targetRerollDays");
        vip.dailySpawnChance = json.getDouble("dailySpawnChance");
        vip.hardLimit = json.getInt("hardLimit");
        vip.originCooldownDays = json.getDouble("originCooldownDays");
        vip.maximumTripDays = (float) json.getDouble("maximumTripDays");
        vip.boardingDays = (float) json.getDouble("boardingDays");
        vip.variant = json.getString("variant");
        settings.validate();
        return settings;
    }

    public void validate() {
        if (debugHistoryMiB < 8 || debugHistoryMiB > 128 || globalFleetLimit < 1 || !positive(planningIntervalDays)
                || !positive(maintenanceIntervalDays) || vip.minimumMarketSize < 1
                || !positive(vip.marketsPerAdditionalFleet) || !positive(vip.maximumTarget)
                || !positive(vip.maximumTripDays) || !Double.isFinite(vip.boardingDays)
                || vip.boardingDays < 0 || vip.maximumTripDays <= vip.boardingDays
                || vip.variant.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid Living Sector settings");
        }
        new TrafficBudget(vip.baseTarget, vip.targetVariation, vip.targetRerollDays,
                vip.dailySpawnChance, vip.hardLimit, vip.originCooldownDays);
        // Fail at startup with the offending variant, rather than during a campaign tick.
        Global.getSettings().getVariant(vip.variant);
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
