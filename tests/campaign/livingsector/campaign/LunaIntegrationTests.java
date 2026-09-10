package livingsector.campaign;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import livingsector.LivingSectorPlugin;
import livingsector.LivingSectorSettings;
import livingsector.integration.LunaSettingsBridge;
import livingsector.traffic.TrafficPolicy;
import livingsector.traffic.TrafficRegistry;
import lunalib.backend.ui.settings.LunaSettingsData;
import lunalib.backend.ui.settings.LunaSettingsLoader;
import lunalib.lunaSettings.LunaSettings;
import org.json.JSONObject;
import org.lazywizard.lazylib.JSONUtils.CommonDataJSONObject;
import static livingsector.campaign.CampaignFixture.*;
import static livingsector.campaign.IntegrationSuite.check;

/** Actual Luna CSV parser, getters and change dispatcher; no renderer or user settings files. */
final class LunaIntegrationTests {
    static void register(IntegrationSuite suite) {
        suite.add("luna.menuDefaults", LunaIntegrationTests::menuDefaults);
        suite.add("luna.everyMenuField", LunaIntegrationTests::everyMenuField);
        suite.add("luna.liveChangesAndExistingMission", LunaIntegrationTests::liveChangesAndExistingMission);
        suite.add("luna.invalidAndMissingValues", LunaIntegrationTests::invalidAndMissingValues);
        suite.add("luna.installedButDisabled", LunaIntegrationTests::installedButDisabled);
        suite.add("luna.recorderLiveToggle", LunaIntegrationTests::recorderLiveToggle);
    }

    private static CommonDataJSONObject prepare(World world) throws Exception {
        world.lunaEnabled = true;
        // Quiet expected missing-field/invalid-value diagnostics in this controlled fixture.
        com.fs.starfarer.api.Global.getLogger(LunaSettingsLoader.class).setLevel(org.apache.log4j.Level.OFF);
        LunaSettings.hasSettingsListenerOfClass(LunaSettingsBridge.class);
        com.fs.starfarer.api.Global.getLogger(LunaSettings.class).setLevel(org.apache.log4j.Level.OFF);
        com.fs.starfarer.api.Global.getLogger(LunaSettingsBridge.class).setLevel(org.apache.log4j.Level.OFF);
        LunaSettingsLoader.getSettingsData().clear();
        LunaSettingsLoader.getSettings().clear();
        LunaSettingsLoader.INSTANCE.loadDefault(); // Read this repository's CSV through the installed parser.
        LunaSettingsLoader.INSTANCE.setHasLoaded(true); // Avoid the loader's separate filesystem persistence stage.
        CommonDataJSONObject data = new CommonDataJSONObject("unused-fixture-file.json");
        for (LunaSettingsData setting : LunaSettingsLoader.getSettingsData()) data.put(setting.getFieldID(), setting.getDefaultValue());
        LunaSettingsLoader.getSettings().put(LivingSectorPlugin.ID, data);
        return data;
    }

    private static void menuDefaults() throws Exception {
        World world = new World();
        CommonDataJSONObject data = prepare(world);
        JSONObject json = new JSONObject(new String(Files.readAllBytes(Paths.get("data/config/living_sector.json")), StandardCharsets.UTF_8));
        check(data.length() == 21, "Luna parser found every supported setting");
        for (LunaSettingsData row : LunaSettingsLoader.getSettingsData()) {
            String key = row.getFieldID().substring(3);
            Object fallback = key.startsWith("vip_") ? json.getJSONObject("vip").get(key.substring(4)) : json.get(key);
            Object value = row.getDefaultValue();
            check(value instanceof Number ? Double.compare(((Number) value).doubleValue(), ((Number) fallback).doubleValue()) == 0
                    : value.equals(fallback), "Menu default agrees with JSON for " + key);
            if (value instanceof Number) check(((Number) value).doubleValue() >= row.getMinValue()
                    && ((Number) value).doubleValue() <= row.getMaxValue(), "Default is inside the UI range for " + key);
        }
        new LivingSectorPlugin().onApplicationLoad();
        check(LunaSettings.hasSettingsListenerOfClass(LunaSettingsBridge.class), "Optional bridge registers a live listener");
        check(!LivingSectorPlugin.settings().debugLogging && !LivingSectorPlugin.settings().useNativeRoutes,
                "Debug logging and experimental routing remain off by default");
    }

    private static void liveChangesAndExistingMission() throws Exception {
        World world = new World();
        CommonDataJSONObject data = prepare(world);
        LivingSectorPlugin plugin = new LivingSectorPlugin();
        plugin.onApplicationLoad();
        plugin.onGameLoad(false);
        NativeMission entry = world.start(false);
        world.routes.spawnRoute(entry.route);
        TrafficPolicy policy = TrafficRegistry.policies().get(0);
        check(policy.budget(SectorReader.capture(Collections.emptySet())).target > 0, "Existing VIP policy starts enabled");
        LivingSectorSettings previous = LivingSectorPlugin.settings();
        data.put("ls_enabled", false).put("ls_debugLogging", true).put("ls_globalFleetLimit", 1)
                .put("ls_vip_enabled", false).put("ls_planningIntervalDays", 10d);
        LunaSettings.reportSettingsChanged("some_other_mod");
        check(LivingSectorPlugin.settings() == previous, "Unrelated menu changes do not reload our config");
        LunaSettings.reportSettingsChanged(LivingSectorPlugin.ID);
        check(!LivingSectorPlugin.settings().enabled && LivingSectorPlugin.settings().debugLogging
                && LivingSectorPlugin.settings().globalFleetLimit == 1, "Actual Luna callback applies menu values");
        check(policy.budget(SectorReader.capture(Collections.emptySet())).target == 0, "Already registered policy sees the new config");
        int reads = world.configReads;
        for (int i = 0; i < 1000; i++) world.advance(.000001f);
        check(world.configReads == reads, "Campaign frames do not poll JSON or Luna settings");
        plugin.onGameLoad(false);
        int before = world.configReads;
        LunaSettings.reportSettingsChanged(LivingSectorPlugin.ID);
        check(world.configReads == before + 1, "Save/load does not duplicate settings listeners");
        check(entry.mission.active() && entry.mission.generation() == 1 && world.manager.activeCount() == 1,
                "Disabling departures or lowering limits preserves an existing physical mission");
    }

    private static void everyMenuField() throws Exception {
        World world = new World();
        CommonDataJSONObject data = prepare(world);
        new LivingSectorPlugin().onApplicationLoad();
        for (LunaSettingsData row : LunaSettingsLoader.getSettingsData()) {
            Object value = row.getDefaultValue();
            if (value instanceof Boolean) value = !((Boolean) value);
            else if (value instanceof Integer) value = ((Integer) value) + 1;
            else value = Math.min(row.getMaxValue(), ((Number) value).doubleValue() + .1);
            data.put(row.getFieldID(), value);
        }
        LunaSettings.reportSettingsChanged(LivingSectorPlugin.ID);
        for (LunaSettingsData row : LunaSettingsLoader.getSettingsData()) {
            String key = row.getFieldID().substring(3);
            Object owner = LivingSectorPlugin.settings();
            if (key.startsWith("vip_")) { owner = LivingSectorPlugin.settings().vip; key = key.substring(4); }
            Object value = owner.getClass().getField(key).get(owner);
            Object expected = data.get(row.getFieldID());
            check(value instanceof Number ? Math.abs(((Number) value).doubleValue() - ((Number) expected).doubleValue()) < .0001
                    : value.equals(expected), "Menu change reaches its runtime setting: " + row.getFieldID());
        }
    }

    private static void invalidAndMissingValues() throws Exception {
        World world = new World();
        CommonDataJSONObject data = prepare(world);
        new LivingSectorPlugin().onApplicationLoad();
        LivingSectorSettings previous = LivingSectorPlugin.settings();
        data.put("ls_enabled", false).put("ls_globalFleetLimit", 0);
        LunaSettings.reportSettingsChanged(LivingSectorPlugin.ID);
        check(LivingSectorPlugin.settings() == previous && previous.enabled, "Invalid updates retain the entire previous valid configuration");
        data.remove("ls_globalFleetLimit");
        LunaSettings.reportSettingsChanged(LivingSectorPlugin.ID);
        check(!LivingSectorPlugin.settings().enabled && LivingSectorPlugin.settings().globalFleetLimit == 40,
                "Missing optional field falls back to JSON without discarding other menu values");
    }

    private static void installedButDisabled() throws Exception {
        World world = new World();
        CommonDataJSONObject data = prepare(world);
        data.put("ls_enabled", false);
        world.lunaEnabled = false;
        new LivingSectorPlugin().onApplicationLoad();
        new LivingSectorPlugin().onGameLoad(false);
        check(LivingSectorPlugin.settings().enabled, "Disabled Luna is ignored even when its jar is present");
    }

    private static void recorderLiveToggle() throws Exception {
        World world = new World();
        CommonDataJSONObject data = prepare(world);
        LivingSectorPlugin plugin = new LivingSectorPlugin();
        plugin.onApplicationLoad(); plugin.onGameLoad(false);
        check(world.recorderWrites == 0 && world.recorderReads == 0, "Menu defaults leave the recorder completely idle");
        data.put("ls_debugTrafficHistory", true).put("ls_debugHistoryMiB", 8);
        LunaSettings.reportSettingsChanged(LivingSectorPlugin.ID);
        NativeMission mission = world.start(false);
        check(world.manager.recorder() != null && mission.mission.hasEventSink(), "Luna toggle starts recording immediately in the loaded campaign");
        data.put("ls_debugTrafficHistory", false);
        LunaSettings.reportSettingsChanged(LivingSectorPlugin.ID);
        int writes = world.recorderWrites, reads = world.recorderReads;
        world.advance(1);
        plugin.beforeGameSave();
        check(world.manager.recorder() == null && !mission.mission.hasEventSink()
                && world.recorderWrites == writes && world.recorderReads == reads, "Luna toggle detaches recording and stops subsequent I/O");
    }
}
