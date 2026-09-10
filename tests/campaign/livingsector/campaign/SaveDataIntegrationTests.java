package livingsector.campaign;

import com.thoughtworks.xstream.XStream;
import java.util.Arrays;
import livingsector.model.TrafficMission;
import livingsector.model.TrafficMission.State;
import livingsector.traffic.TrafficPlan;
import static livingsector.campaign.IntegrationSuite.check;

/** Installed XStream on our data graph only; this is not Starsector's full campaign serializer. */
final class SaveDataIntegrationTests {
    static void register(IntegrationSuite suite) {
        suite.add("saveData.activePhysicalMission", SaveDataIntegrationTests::activePhysicalMission);
        suite.add("saveData.abstractAndTerminalMission", SaveDataIntegrationTests::abstractAndTerminalMission);
    }

    private static TrafficMission mission() {
        TrafficPlan plan = new TrafficPlan("vip", "planet", "station", "Saved trip",
                Arrays.asList("mudskipper_Standard", "mudskipper_Standard"), 2f, 180f);
        return new TrafficMission("ls-save", plan, "independent", 123, 42, true, true);
    }

    private static TrafficMission roundTrip(TrafficMission original) {
        XStream serializer = new XStream(new com.thoughtworks.xstream.io.xml.DomDriver("UTF-8"));
        XStream.setupDefaultSecurity(serializer);
        serializer.allowTypesByWildcard(new String[]{"livingsector.**", "java.util.**"});
        String xml = serializer.toXML(original);
        TrafficMission restored = (TrafficMission) serializer.fromXML(xml);
        check(restored != original && restored.plan != original.plan, "Deserialization creates a separate data graph");
        check(restored.schemaVersion == 1 && restored.id.equals(original.id) && restored.seed == original.seed
                        && restored.createdAt == original.createdAt && restored.factionId.equals(original.factionId),
                "Persisted mission identity survives installed XStream");
        check(restored.events().equals(original.events()) && restored.stops.size() == 3
                        && restored.stops.get(1).marketId.equals("station"), "History and round-trip itinerary survive");
        return restored;
    }

    private static void activePhysicalMission() {
        TrafficMission original = mission();
        original.bindFleet("fleet-1");
        original.observeLeg(1);
        TrafficMission restored = roundTrip(original);
        check(restored.active() && restored.leg() == 1 && restored.generation() == 1
                && restored.fleetId().equals("fleet-1"), "Active physical binding survives serialization");
        check(!restored.bindFleet("fleet-1"), "Duplicate spawn after restore is still idempotent");
    }

    private static void abstractAndTerminalMission() {
        TrafficMission original = mission();
        original.bindFleet("old-fleet");
        original.releaseFleet("old-fleet");
        TrafficMission restored = roundTrip(original);
        check(restored.fleetId() == null && restored.lastFleetId().equals("old-fleet"), "Abstract restore retains only historical physical ID");
        restored.bindFleet("new-fleet");
        check(restored.generation() == 2, "Rematerialization after serialization continues generation sequence");
        restored.finish(State.DESTROYED, "battle");
        TrafficMission terminal = roundTrip(restored);
        check(terminal.state() == State.DESTROYED && terminal.outcome().equals("battle")
                && !terminal.bindFleet("late-fleet"), "Serialized destruction cannot be revived by a late spawn");
    }
}
