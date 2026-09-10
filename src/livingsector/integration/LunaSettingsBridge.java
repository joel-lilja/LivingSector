package livingsector.integration;

import com.fs.starfarer.api.Global;
import livingsector.LivingSectorPlugin;
import livingsector.LivingSectorSettings;
import livingsector.traffic.VipTrafficPolicy;
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
            settings.useNativeRoutes = bool("useNativeRoutes", settings.useNativeRoutes);
            settings.globalFleetLimit = integer("globalFleetLimit", settings.globalFleetLimit);
            settings.planningIntervalDays = number("planningIntervalDays", settings.planningIntervalDays);
            settings.maintenanceIntervalDays = number("maintenanceIntervalDays", settings.maintenanceIntervalDays);
            VipTrafficPolicy.Config vip = settings.vip;
            vip.enabled = bool("vip_enabled", vip.enabled);
            vip.includeStations = bool("vip_includeStations", vip.includeStations);
            vip.minimumMarketSize = integer("vip_minimumMarketSize", vip.minimumMarketSize);
            vip.hardLimit = integer("vip_hardLimit", vip.hardLimit);
            vip.baseTarget = number("vip_baseTarget", vip.baseTarget);
            vip.marketsPerAdditionalFleet = number("vip_marketsPerAdditionalFleet", vip.marketsPerAdditionalFleet);
            vip.maximumTarget = number("vip_maximumTarget", vip.maximumTarget);
            vip.targetVariation = number("vip_targetVariation", vip.targetVariation);
            vip.targetRerollDays = number("vip_targetRerollDays", vip.targetRerollDays);
            vip.dailySpawnChance = number("vip_dailySpawnChance", vip.dailySpawnChance);
            vip.originCooldownDays = number("vip_originCooldownDays", vip.originCooldownDays);
            vip.maximumTripDays = (float) number("vip_maximumTripDays", vip.maximumTripDays);
            vip.boardingDays = (float) number("vip_boardingDays", vip.boardingDays);
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
