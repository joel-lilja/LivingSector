package livingsector.campaign;

import com.fs.starfarer.api.Global;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

/** Named scenarios, isolated campaign globals, and a JUnit XML report for local/CI builds. */
public final class IntegrationSuite {
    interface Scenario { void run() throws Exception; }
    private static final class Case {
        final String name;
        final Scenario scenario;
        Throwable failure;
        double seconds;
        Case(String name, Scenario scenario) { this.name = name; this.scenario = scenario; }
    }
    private final List<Case> cases = new ArrayList<Case>();

    void add(String name, Scenario scenario) { cases.add(new Case(name, scenario)); }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        IntegrationSuite suite = new IntegrationSuite();
        if (args.length > 1 && "--luna".equals(args[1])) LunaIntegrationTests.register(suite);
        else {
        NativeTrafficTests.register(suite);
        EntryPointIntegrationTests.register(suite);
        SaveDataIntegrationTests.register(suite);
        OptionalDependencyTests.register(suite);
        RotatingLogTests.register(suite);
        RecorderIntegrationTests.register(suite);
        BattleHistoryTests.register(suite);
        PhaseAIntegrationTests.register(suite);
        AttritionIntegrationTests.register(suite);
        }
        Global.getLogger(NativeTraffic.class).setLevel(org.apache.log4j.Level.OFF);
        Global.getLogger(livingsector.LivingSectorPlugin.class).setLevel(org.apache.log4j.Level.OFF);
        Global.getLogger(org.lazywizard.console.Console.class).setLevel(org.apache.log4j.Level.OFF);
        int failures = 0;
        for (Case test : suite.cases) {
            long start = System.nanoTime();
            try {
                CampaignFixture.defaults();
                test.scenario.run();
                System.out.println("PASS: " + test.name);
            } catch (Exception | AssertionError | LinkageError error) {
                test.failure = error;
                failures++;
                System.err.println("FAIL: " + test.name);
                error.printStackTrace();
            } finally {
                test.seconds = (System.nanoTime() - start) / 1e9;
                CampaignFixture.defaults();
            }
        }
        Path report = Paths.get(args.length == 0 ? "build/reports/integration.xml" : args[0]);
        suite.report(report, failures);
        System.out.println("Integration: " + (suite.cases.size() - failures) + "/" + suite.cases.size()
                + " scenarios passed; report=" + report);
        if (failures > 0) throw new AssertionError(failures + " integration scenarios failed");
    }

    private void report(Path path, int failures) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (java.io.Writer out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("testsuite");
            xml.writeAttribute("name", "LivingSector integration");
            xml.writeAttribute("tests", Integer.toString(cases.size()));
            xml.writeAttribute("failures", Integer.toString(failures));
            for (Case test : cases) {
                xml.writeStartElement("testcase");
                xml.writeAttribute("classname", "livingsector.integration");
                xml.writeAttribute("name", test.name);
                xml.writeAttribute("time", Double.toString(test.seconds));
                if (test.failure != null) {
                    xml.writeStartElement("failure");
                    xml.writeAttribute("type", test.failure.getClass().getName());
                    StringWriter trace = new StringWriter();
                    test.failure.printStackTrace(new PrintWriter(trace));
                    xml.writeCharacters(trace.toString());
                    xml.writeEndElement();
                }
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        }
    }
}
