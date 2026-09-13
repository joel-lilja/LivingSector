package livingsector.integration;

import com.fs.starfarer.api.Global;
import livingsector.LivingSectorPlugin;
import livingsector.LivingSectorSettings;
import livingsector.traffic.CivilianTrafficPolicy;
import lunalib.lunaSettings.LunaSettings;
import lunalib.lunaSettings.LunaSettingsListener;

/** Optional, application-scoped adapter. Never referenced by saved campaign objects. */
public final class LunaSettingsBridge implements Runnable, LunaSettingsListener {
    public LunaSettingsBridge() {
        if (!LunaSettings.hasSettingsListenerOfClass(getClass())) LunaSettings.addSettingsListener(this);
    }

    @Override public void settingsChanged(String modID) {
        if (LivingSectorPlugin.ID.equals(modID)) run();
    }

    @Override public void run() {
        try {
            LivingSectorSettings settings = LivingSectorSettings.load();
            settings.enabled = bool("enabled", settings.enabled);
            settings.debugLogging = bool("debugLogging", settings.debugLogging);
            settings.debugTrafficHistory = bool("debugTrafficHistory", settings.debugTrafficHistory);
            settings.debugHistoryMiB = integer("debugHistoryMiB", settings.debugHistoryMiB);
            settings.globalFleetLimit = integer("globalFleetLimit", settings.globalFleetLimit);
            settings.planningIntervalDays = number("planningIntervalDays", settings.planningIntervalDays);
            settings.maintenanceIntervalDays = number("maintenanceIntervalDays", settings.maintenanceIntervalDays);
            CivilianTrafficPolicy.Config c = settings.civilian;
            c.enabled = bool("civilian_enabled", c.enabled);
            c.includeStations = bool("civilian_includeStations", c.includeStations);
            c.localEnabled = bool("civilian_localEnabled", c.localEnabled);
            c.linerEnabled = bool("civilian_linerEnabled", c.linerEnabled);
            c.charterEnabled = bool("civilian_charterEnabled", c.charterEnabled);
            c.minimumMarketSize = integer("civilian_minimumMarketSize", c.minimumMarketSize);
            c.hardLimit = integer("civilian_hardLimit", c.hardLimit);
            c.baseTarget = number("civilian_baseTarget", c.baseTarget);
            c.marketsPerAdditionalFleet = number("civilian_marketsPerAdditionalFleet", c.marketsPerAdditionalFleet);
            c.maximumTarget = number("civilian_maximumTarget", c.maximumTarget);
            c.targetVariation = number("civilian_targetVariation", c.targetVariation);
            c.targetRerollDays = number("civilian_targetRerollDays", c.targetRerollDays);
            c.dailySpawnChance = number("civilian_dailySpawnChance", c.dailySpawnChance);
            c.originCooldownDays = number("civilian_originCooldownDays", c.originCooldownDays);
            c.localWeight = number("civilian_localWeight", c.localWeight);
            c.linerWeight = number("civilian_linerWeight", c.linerWeight);
            c.charterWeight = number("civilian_charterWeight", c.charterWeight);
            c.homeFactionPreference = number("civilian_homeFactionPreference", c.homeFactionPreference);
            c.localReturnChance = number("civilian_localReturnChance", c.localReturnChance);
            c.linerReturnChance = number("civilian_linerReturnChance", c.linerReturnChance);
            c.charterReturnChance = number("civilian_charterReturnChance", c.charterReturnChance);
            LivingSectorPlugin.applySettings(settings);
        } catch (Exception | LinkageError ex) {
            Global.getLogger(LunaSettingsBridge.class).warn("Living Sector: settings update rejected; retaining previous valid settings", ex);
        }
    }

    private static boolean bool(String key, boolean fallback) {
        Boolean value = LunaSettings.getBoolean(LivingSectorPlugin.ID, "ls_" + key);
        return value == null ? fallback : value;
    }
    private static int integer(String key, int fallback) {
        Integer value = LunaSettings.getInt(LivingSectorPlugin.ID, "ls_" + key);
        return value == null ? fallback : value;
    }
    private static double number(String key, double fallback) {
        Double value = LunaSettings.getDouble(LivingSectorPlugin.ID, "ls_" + key);
        return value == null ? fallback : value;
    }
}
