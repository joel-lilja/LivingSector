package livingsector;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import livingsector.campaign.TrafficManager;
import livingsector.traffic.TrafficRegistry;
import livingsector.traffic.VipTrafficPolicy;

public final class LivingSectorPlugin extends BaseModPlugin {
    public static final String ID = "living_sector";
    private static LivingSectorSettings settings;

    @Override
    public void onApplicationLoad() throws Exception {
        settings = LivingSectorSettings.load();
        TrafficRegistry.register(new VipTrafficPolicy(settings.vip));
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (!Global.getSector().hasScript(TrafficManager.class)) {
            Global.getSector().addScript(new TrafficManager());
        }
        Global.getLogger(LivingSectorPlugin.class).info("Living Sector loaded: " + TrafficManager.status());
    }

    public static LivingSectorSettings settings() { return settings; }
}
