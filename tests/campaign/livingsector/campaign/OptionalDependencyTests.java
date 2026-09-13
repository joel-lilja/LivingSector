package livingsector.campaign;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import livingsector.LivingSectorPlugin;
import static livingsector.campaign.CampaignFixture.*;
import static livingsector.campaign.IntegrationSuite.check;

/** This suite runs with every LunaLib jar omitted from the JVM classpath. */
final class OptionalDependencyTests {
    static void register(IntegrationSuite suite) {
        suite.add("optional.lunaAbsent", () -> startup(false));
        suite.add("optional.lunaMissingDespiteEnabledFlag", () -> startup(true));
        suite.add("optional.startupWithoutReflection", OptionalDependencyTests::restrictedStartup);
    }
    private static void restrictedStartup() throws Exception {
        for (boolean enabledFlag : new boolean[]{false, true}) {
            World world = new World(); world.lunaEnabled = enabledFlag;
            try (ScriptRestrictionLoader loader = new ScriptRestrictionLoader()) {
                Class<?> type = loader.loadClass("livingsector.LivingSectorPlugin");
                com.fs.starfarer.api.BaseModPlugin plugin = (com.fs.starfarer.api.BaseModPlugin) type.getConstructor().newInstance();
                plugin.onApplicationLoad();
                Object settings = type.getMethod("settings").invoke(null);
                check(settings.getClass().getField("globalFleetLimit").getInt(settings) == 40,
                        "Restricted plugin loads JSON defaults without any Luna jars, including a stale enabled flag");
            }
        }
    }
    private static void startup(boolean enabledFlag) throws Exception {
        try { Class.forName("lunalib.lunaSettings.LunaSettings"); throw new AssertionError("LunaLib leaked into absent-dependency test"); }
        catch (ClassNotFoundException expected) { }
        org.json.JSONArray dependencies = new org.json.JSONObject(new String(Files.readAllBytes(Paths.get("mod_info.json")),
                StandardCharsets.UTF_8)).getJSONArray("dependencies");
        for (int i = 0; i < dependencies.length(); i++) {
            check(!"lunalib".equals(dependencies.getJSONObject(i).getString("id")), "LunaLib must not be a required launcher dependency");
        }
        World world = new World();
        world.lunaEnabled = enabledFlag;
        LivingSectorPlugin plugin = new LivingSectorPlugin();
        plugin.onApplicationLoad();
        plugin.onGameLoad(false);
        NativeMission mission = world.start(false);
        world.routes.spawnRoute(mission.route);
        world.reloadHooks();
        check(LivingSectorPlugin.settings().enabled && !LivingSectorPlugin.settings().debugLogging
                && LivingSectorPlugin.settings().globalFleetLimit == 40, "JSON defaults work without Luna classes");
        check(mission.mission.active() && mission.mission.generation() == 1, "Campaign load and physical traffic work without Luna classes");
    }
}
