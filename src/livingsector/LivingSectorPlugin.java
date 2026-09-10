package livingsector;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import livingsector.campaign.TrafficManager;
import livingsector.traffic.TrafficRegistry;
import livingsector.traffic.VipTrafficPolicy;

public final class LivingSectorPlugin extends BaseModPlugin {
    public static final String ID = "living_sector";
    private static LivingSectorSettings settings;
    private static VipTrafficPolicy vipPolicy;
    private static Runnable refreshOptionalSettings;

    @Override
    public void onApplicationLoad() throws Exception {
        settings = LivingSectorSettings.load();
        vipPolicy = new VipTrafficPolicy(settings.vip);
        TrafficRegistry.register(vipPolicy);
        refreshOptionalSettings = null;
        if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
            try {
                // A string-only boundary keeps Luna classes out of ordinary startup/loading.
                refreshOptionalSettings = (Runnable) Class.forName("livingsector.integration.LunaSettingsBridge")
                        .getDeclaredConstructor().newInstance();
                refreshOptionalSettings.run();
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
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
        if (vipPolicy != null) vipPolicy.updateConfig(replacement.vip);
        if (Global.getSector() != null) {
            for (com.fs.starfarer.api.EveryFrameScript script : Global.getSector().getScripts()) {
                if (script instanceof TrafficManager) ((TrafficManager) script).settingsChanged(previous, replacement);
            }
        }
    }
}
