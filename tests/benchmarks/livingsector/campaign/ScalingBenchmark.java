package livingsector.campaign;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import livingsector.traffic.TrafficPlan;
import org.json.JSONArray;
import org.json.JSONObject;

/** Repeatable headless route workload. Fake entities: not a live-game FPS or heap benchmark. */
public final class ScalingBenchmark {
    public static void main(String[] args) throws Exception {
        com.fs.starfarer.api.Global.getLogger(NativeTraffic.class).setLevel(org.apache.log4j.Level.OFF);
        JSONArray results = new JSONArray();
        for (int ships : new int[]{100, 1000, 5000}) for (int perFleet : new int[]{1, 2, 5}) {
            CampaignFixture.defaults();
            CampaignFixture.World world = new CampaignFixture.World();
            TrafficPlan plan = new TrafficPlan("vip", "origin", "station", "Benchmark",
                    Collections.nCopies(perFleet, "mudskipper_Standard"), 1, 100000);
            long started = System.nanoTime();
            for (int i = 0; i < ships / perFleet; i++) world.traffic.start(plan, 0, i, false, true);
            double admission = (System.nanoTime() - started) / 1e6;
            for (int i = 0; i < 5; i++) world.traffic.maintain(2);
            double[] times = new double[30];
            for (int i = 0; i < times.length; i++) {
                started = System.nanoTime();
                world.traffic.maintain(2);
                times[i] = (System.nanoTime() - started) / 1e6;
            }
            Arrays.sort(times);
            JSONObject row = new JSONObject().put("ships", ships).put("routes", ships / perFleet)
                    .put("shipsPerFleet", perFleet).put("admissionTotalMs", admission)
                    .put("maintenanceP50Ms", times[15]).put("maintenanceP95Ms", times[28])
                    .put("maintenanceMaxMs", times[29]);
            results.put(row);
            System.out.println(row);
        }
        JSONObject report = new JSONObject().put("scope", "headless abstract routes, fake entities, debug off; not live FPS or retained heap")
                .put("java", System.getProperty("java.version")).put("cases", results);
        Files.write(Paths.get(args[0]), report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
