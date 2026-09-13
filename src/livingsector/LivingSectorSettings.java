package livingsector;

import com.fs.starfarer.api.Global;
import livingsector.traffic.TrafficBudget;
import livingsector.traffic.VipTrafficPolicy;
import livingsector.traffic.CivilianTrafficPolicy;
import org.json.JSONObject;

public final class LivingSectorSettings {
    public boolean enabled = true, debugLogging = false;
    public boolean useNativeRoutes = false;
    public boolean debugTrafficHistory = false;
    public int debugHistoryMiB = 32;
    public int globalFleetLimit = 40;
    public double planningIntervalDays = 5, maintenanceIntervalDays = 2;
    public final VipTrafficPolicy.Config vip = new VipTrafficPolicy.Config();
    public final CivilianTrafficPolicy.Config civilian = new CivilianTrafficPolicy.Config();

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
        JSONObject civ = root.optJSONObject("civilian");
        if (civ != null) {
            CivilianTrafficPolicy.Config c = settings.civilian;
            c.enabled = civ.optBoolean("enabled", c.enabled);
            c.includeStations = civ.optBoolean("includeStations", c.includeStations);
            c.localEnabled = civ.optBoolean("localEnabled", c.localEnabled);
            c.linerEnabled = civ.optBoolean("linerEnabled", c.linerEnabled);
            c.charterEnabled = civ.optBoolean("charterEnabled", c.charterEnabled);
            c.minimumMarketSize = civ.optInt("minimumMarketSize", c.minimumMarketSize);
            c.hardLimit = civ.optInt("hardLimit", c.hardLimit);
            c.baseTarget = civ.optDouble("baseTarget", c.baseTarget);
            c.marketsPerAdditionalFleet = civ.optDouble("marketsPerAdditionalFleet", c.marketsPerAdditionalFleet);
            c.maximumTarget = civ.optDouble("maximumTarget", c.maximumTarget);
            c.targetVariation = civ.optDouble("targetVariation", c.targetVariation);
            c.targetRerollDays = civ.optDouble("targetRerollDays", c.targetRerollDays);
            c.dailySpawnChance = civ.optDouble("dailySpawnChance", c.dailySpawnChance);
            c.originCooldownDays = civ.optDouble("originCooldownDays", c.originCooldownDays);
            c.localWeight = civ.optDouble("localWeight", c.localWeight);
            c.linerWeight = civ.optDouble("linerWeight", c.linerWeight);
            c.charterWeight = civ.optDouble("charterWeight", c.charterWeight);
            c.homeFactionPreference = civ.optDouble("homeFactionPreference", c.homeFactionPreference);
            c.localReturnChance = civ.optDouble("localReturnChance", c.localReturnChance);
            c.linerReturnChance = civ.optDouble("linerReturnChance", c.linerReturnChance);
            c.charterReturnChance = civ.optDouble("charterReturnChance", c.charterReturnChance);
        }
        settings.validate();
        return settings;
    }

    public void validate() {
        CivilianTrafficPolicy.Config c = civilian;
        if (c.minimumMarketSize < 3 || !positive(c.marketsPerAdditionalFleet) || !positive(c.maximumTarget)
                || !nonnegative(c.localWeight) || !nonnegative(c.linerWeight) || !nonnegative(c.charterWeight)
                || !probability(c.homeFactionPreference) || !probability(c.localReturnChance)
                || !probability(c.linerReturnChance) || !probability(c.charterReturnChance)) {
            throw new IllegalArgumentException("Invalid civilian traffic settings");
        }
        new TrafficBudget(c.baseTarget, c.targetVariation, c.targetRerollDays, c.dailySpawnChance, c.hardLimit, c.originCooldownDays);
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
    private static boolean nonnegative(double value) { return Double.isFinite(value) && value >= 0; }
    private static boolean probability(double value) { return nonnegative(value) && value <= 1; }
}
