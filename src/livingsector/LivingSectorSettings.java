package livingsector;

import com.fs.starfarer.api.Global;
import livingsector.traffic.TrafficBudget;
import livingsector.traffic.VipTrafficPolicy;
import org.json.JSONObject;

public final class LivingSectorSettings {
    public boolean enabled = true, debugLogging = false;
    public int globalFleetLimit = 40;
    public final VipTrafficPolicy.Config vip = new VipTrafficPolicy.Config();

    public static LivingSectorSettings load() throws Exception {
        JSONObject root = Global.getSettings().loadJSON("data/config/living_sector.json", LivingSectorPlugin.ID);
        LivingSectorSettings settings = new LivingSectorSettings();
        settings.enabled = root.getBoolean("enabled");
        settings.debugLogging = root.getBoolean("debugLogging");
        settings.globalFleetLimit = root.getInt("globalFleetLimit");
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
        if (settings.globalFleetLimit < 1 || vip.minimumMarketSize < 1
                || !positive(vip.marketsPerAdditionalFleet) || !positive(vip.maximumTarget)
                || !positive(vip.maximumTripDays) || !Double.isFinite(vip.boardingDays)
                || vip.boardingDays < 0 || vip.maximumTripDays <= vip.boardingDays
                || vip.variant.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid Living Sector settings in data/config/living_sector.json");
        }
        new TrafficBudget(vip.baseTarget, vip.targetVariation, vip.targetRerollDays,
                vip.dailySpawnChance, vip.hardLimit, vip.originCooldownDays);
        // Fail at startup with the offending variant, rather than during a campaign tick.
        Global.getSettings().getVariant(vip.variant);
        return settings;
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
