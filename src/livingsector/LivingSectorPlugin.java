package livingsector;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import livingsector.campaign.TrafficManager;
import livingsector.traffic.TrafficRegistry;
import livingsector.traffic.CivilianTrafficPolicy;

public final class LivingSectorPlugin extends BaseModPlugin {
    public static final String ID = "living_sector";
    private static LivingSectorSettings settings;
    private static CivilianTrafficPolicy civilianPolicy;
    private static Runnable refreshOptionalSettings;

    @Override
    public void onApplicationLoad() throws Exception {
        settings = LivingSectorSettings.load();
        civilianPolicy = new CivilianTrafficPolicy(settings.civilian);
        TrafficRegistry.register(civilianPolicy);
        refreshOptionalSettings = null;
        if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
            try {
                // Resolve the adapter only in the enabled branch. Starsector forbids reflective constructors.
                // Its Luna dependencies stay inside the adapter; absent-library tests load this plugin without them.
                refreshOptionalSettings = new livingsector.integration.LunaSettingsBridge();
                refreshOptionalSettings.run();
            } catch (LinkageError | RuntimeException ex) {
                refreshOptionalSettings = null;
                Global.getLogger(LivingSectorPlugin.class).warn("Living Sector: optional LunaLib integration unavailable; using JSON settings", ex);
            }
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (refreshOptionalSettings != null) refreshOptionalSettings.run();
        if (!Global.getSector().hasScript(TrafficManager.class)) {
            Global.getSector().addScript(new TrafficManager());
        }
        TrafficManager.get().onLoad();
        Global.getLogger(LivingSectorPlugin.class).info("Living Sector loaded: " + TrafficManager.status());
    }

    public static LivingSectorSettings settings() { return settings; }
    @Override public void beforeGameSave() { TrafficManager.get().recordSave("SAVE_REQUEST"); }
    @Override public void afterGameSave() { TrafficManager.get().recordSave("SAVE_SUCCESS"); }
    @Override public void onGameSaveFailed() { TrafficManager.get().recordSave("SAVE_FAILED"); }

    /** Publish only a fully validated configuration; existing plans and cooldowns stay intact. */
    public static void applySettings(LivingSectorSettings replacement) {
        replacement.validate();
        LivingSectorSettings previous = settings;
        settings = replacement;
        if (civilianPolicy != null) civilianPolicy.updateConfig(replacement.civilian);
        if (Global.getSector() != null) {
            for (com.fs.starfarer.api.EveryFrameScript script : Global.getSector().getScripts()) {
                if (script instanceof TrafficManager) ((TrafficManager) script).settingsChanged(previous, replacement);
            }
        }
    }
}
