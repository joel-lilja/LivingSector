package livingsector.debug;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import livingsector.model.RecorderState;
import org.json.JSONObject;

/** On-demand analysis of retained JSONL files. Never invoked by campaign advancement. */
public final class RecorderReport {
    private RecorderReport() { }
    private static final class Run {
        String campaign, parent;
        long parentSequence, first = Long.MAX_VALUE, last;
        double firstDay = Double.POSITIVE_INFINITY, lastDay;
        boolean start;
    }
    private static final class Trip {
        Double created;
        boolean terminal;
    }
    public static String report(RotatingLog log, RecorderState cursor, double now, String action, String window, String id) throws IOException {
        Map<String, Run> runs = new LinkedHashMap<String, Run>();
        final int[] malformed = {0};
        log.readLines(line -> {
            JSONObject event = parse(line, malformed);
            if (event == null || !event.has("run")) return;
            String name = event.optString("run");
            Run run = runs.computeIfAbsent(name, ignored -> new Run());
            run.campaign = event.optString("campaign");
            long sequence = event.optLong("seq");
            run.first = Math.min(run.first, sequence); run.last = Math.max(run.last, sequence);
            run.firstDay = Math.min(run.firstDay, event.optDouble("day")); run.lastDay = Math.max(run.lastDay, event.optDouble("day"));
            if ("RUN_START".equals(event.optString("kind"))) {
                run.start = true;
                run.parent = event.isNull("parentRun") ? null : event.optString("parentRun", null);
                run.parentSequence = event.optLong("parentSequence");
            }
        });
        if ("runs".equals(action)) {
            StringBuilder out = new StringBuilder("Retained recording branches (latest 50):");
            int skip = Math.max(0, runs.size() - 50), i = 0;
            for (Map.Entry<String, Run> row : runs.entrySet()) {
                if (i++ < skip) continue;
                Run run = row.getValue();
                out.append("\n").append(row.getKey()).append("; campaign=").append(run.campaign)
                        .append("; retained days=").append(n(run.firstDay)).append("–").append(n(run.lastDay))
                        .append(run.start ? "" : "; beginning missing");
            }
            return out.append("\nDamaged lines skipped: ").append(malformed[0]).toString();
        }
        boolean explicitRun = "summary".equals(action) && id != null;
        if (!explicitRun && (cursor == null || cursor.runId == null)) return "No recorder timeline in this save. Use ls debug runs to inspect retained branches.";
        String selected = explicitRun ? id : cursor.runId;
        Run selectedRun = runs.get(selected);
        if (selectedRun == null) return "No retained events for run " + selected + "; files may have rotated away.";
        final double to = explicitRun ? selectedRun.lastDay : now;
        final double from = start(window, to);
        Map<String, Long> limits = new LinkedHashMap<String, Long>();
        boolean missingAncestor = false;
        String walk = selected;
        long cutoff = explicitRun ? Long.MAX_VALUE : cursor.sequence;
        while (walk != null && !limits.containsKey(walk)) {
            limits.put(walk, cutoff);
            Run info = runs.get(walk);
            if (info == null || !info.start) { missingAncestor = true; break; }
            if (!selectedRun.campaign.equals(info.campaign)) throw new IOException("Recorder branch crosses campaign IDs");
            cutoff = info.parentSequence;
            walk = info.parent;
        }
        Map<String, Long> lastSequences = new LinkedHashMap<String, Long>();
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>(), departures = new LinkedHashMap<String, Integer>();
        Map<String, Trip> trips = new LinkedHashMap<String, Trip>();
        Deque<String> latest = new ArrayDeque<String>();
        final long[] matched = {0}, gaps = {missingAncestor ? 1 : 0};
        final double[] oldest = {Double.POSITIVE_INFINITY}, newest = {Double.NEGATIVE_INFINITY}, duration = {0}, longest = {0};
        final String[] oldestDate = {"n/a"};
        final double[] unrecordedDays = {0};
        final int[] measured = {0}, observed = {0}, starts = {0};
        final String tripFilter = "trip".equals(action) ? id : null;
        malformed[0] = 0;
        log.readLines(line -> {
            JSONObject e = parse(line, malformed);
            if (e == null || !e.has("run")) return;
            String run = e.optString("run");
            Long limit = limits.get(run);
            long seq = e.optLong("seq");
            if (limit == null || seq > limit) return;
            if (!selectedRun.campaign.equals(e.optString("campaign"))) throw new IOException("Mixed campaign recorder data");
            long last = lastSequences.getOrDefault(run, 0L);
            if (seq != last + 1) gaps[0]++;
            lastSequences.put(run, seq);
            double day = e.optDouble("day");
            if (day > to) return;
            if (day < oldest[0]) { oldest[0] = day; oldestDate[0] = e.optString("date", "unavailable"); }
            newest[0] = Math.max(newest[0], day);
            String kind = e.optString("kind"), tripId = e.optString("trip", "");
            if ("RUN_START".equals(kind)) {
                starts[0]++;
                if (!e.isNull("parentRun")) unrecordedDays[0] += Math.max(0, day - e.optDouble("parentDay", day));
            }
            if (!tripId.isEmpty()) {
                Trip trip = trips.computeIfAbsent(tripId, ignored -> new Trip());
                if ("CREATED".equals(kind)) trip.created = day;
                if (terminal(kind)) {
                    trip.terminal = true;
                    if (day >= from && trip.created != null && (tripFilter == null || tripFilter.equals(tripId))) {
                        double length = Math.max(0, day - trip.created);
                        measured[0]++; duration[0] += length; longest[0] = Math.max(longest[0], length);
                    }
                }
            }
            if (day < from || (tripFilter != null && !tripFilter.equals(tripId))) return;
            matched[0]++;
            counts.put(kind, counts.getOrDefault(kind, 0) + 1);
            if ("OBSERVED_EXISTING".equals(kind)) observed[0]++;
            if ("CREATED".equals(kind)) {
                String key = e.optString("type") + "/" + e.optString("faction") + (e.optBoolean("test") ? " (test)" : " (automatic)");
                departures.put(key, departures.getOrDefault(key, 0) + 1);
            }
            latest.addLast("day " + n(day) + " [" + e.optString("date") + "] " + tripId + " " + kind
                    + "; fleet=" + e.optString("fleet", "none") + "; " + e.optString("detail"));
            if (latest.size() > 30) latest.removeFirst();
        });
        for (Map.Entry<String, Long> limit : limits.entrySet()) {
            if (limit.getValue() != Long.MAX_VALUE && lastSequences.getOrDefault(limit.getKey(), 0L) < limit.getValue()) gaps[0]++;
        }
        int unresolved = 0;
        for (Map.Entry<String, Trip> row : trips.entrySet()) {
            if (!row.getValue().terminal && (tripFilter == null || tripFilter.equals(row.getKey()))) unresolved++;
        }
        StringBuilder out = new StringBuilder("Living Sector debug run ").append(selected)
                .append("\nWindow: days ").append(n(from)).append("–").append(n(to)).append("; includes ancestors only up to saved cursors.")
                .append("\nRetained timeline observations: ").append(n(oldest[0])).append("–").append(n(newest[0]))
                .append("; oldest date=").append(oldestDate[0])
                .append("; missing sequence/ancestry sections=").append(gaps[0]).append("; damaged lines=").append(malformed[0])
                .append("\nRecording segments=").append(starts[0]).append("; recording may have been off between segments. Earlier history is not reconstructed.")
                .append("\nKnown unrecorded days between segments=").append(n(unrecordedDays[0])).append(" (missing segments can hide additional gaps).")
                .append("\nMatching events=").append(matched[0]).append("; counts=").append(counts)
                .append("\nCreated trips by type/faction: ").append(departures)
                .append("\nExisting-trip observations=").append(observed[0]).append("; trips without a recorded terminal outcome=").append(unresolved)
                .append("\nDurations with retained creation and outcome: n=").append(measured[0])
                .append("; mean days=").append(measured[0] == 0 ? "n/a" : n(duration[0] / measured[0]))
                .append("; longest days=").append(measured[0] == 0 ? "n/a" : n(longest[0]))
                .append("\nLatest matching events (up to 30):");
        for (String line : latest) out.append("\n  ").append(line);
        return out.toString();
    }
    private static JSONObject parse(String line, int[] malformed) {
        try { return new JSONObject(line); } catch (Exception ex) { malformed[0]++; return null; }
    }
    public static boolean terminal(String kind) {
        return "COMPLETED".equals(kind) || "DESTROYED".equals(kind) || "CANCELLED".equals(kind)
                || "FAILED".equals(kind) || "UNKNOWN".equals(kind);
    }
    private static double start(String window, double to) {
        if ("all".equalsIgnoreCase(window)) return 0;
        double days;
        try { days = Double.parseDouble(window); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Use positive campaign days or all"); }
        if (!Double.isFinite(days) || days <= 0) throw new IllegalArgumentException("Use finite positive campaign days or all");
        return Math.max(0, to - days);
    }
    private static String n(double value) { return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.2f", value) : "n/a"; }
}
