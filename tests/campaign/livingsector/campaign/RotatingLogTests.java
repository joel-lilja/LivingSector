package livingsector.campaign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import livingsector.debug.RotatingLog;
import static livingsector.campaign.IntegrationSuite.check;

final class RotatingLogTests {
    static void register(IntegrationSuite suite) {
        suite.add("recorder.rotationAndSharedBudget", RotatingLogTests::rotationAndSharedBudget);
        suite.add("recorder.shrinkAndReadOnlyReports", RotatingLogTests::shrinkAndReadOnlyReports);
        suite.add("recorder.failedDeletionAndDamagedSlots", RotatingLogTests::failedDeletionAndDamagedSlots);
    }
    static final class MemoryStore implements RotatingLog.Store {
        final Map<String, String> files = new LinkedHashMap<String, String>();
        long allowance = 32 * 1024L * 1024;
        int writes, deletes;
        boolean failDelete;
        public boolean exists(String name) { return files.containsKey(name); }
        public String read(String name) throws IOException { if (!files.containsKey(name)) throw new IOException("missing"); return files.get(name); }
        public void write(String name, String value) {
            int size = bytes(value);
            check(size <= RotatingLog.FILE_BYTES, "Every physical write respects the file-size ceiling");
            check(used() - (files.containsKey(name) ? bytes(files.get(name)) : 0) + size <= allowance,
                    "Rotation frees space before writing, not afterward");
            files.put(name, value); writes++;
        }
        public void delete(String name) throws IOException { deletes++; if (failDelete) throw new IOException("locked"); files.remove(name); }
        long used() { long total = 0; for (Map.Entry<String, String> file : files.entrySet()) if (file.getKey().startsWith(RotatingLog.DIRECTORY)) total += bytes(file.getValue()); return total; }
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static String event(int i) { return "{\"event\":" + i + ",\"payload\":\"" + String.join("", java.util.Collections.nCopies(20000, "界")) + "\"}"; }
    private static void fill(RotatingLog log, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            log.append(event(i));
            check(log.bufferedBytes() <= RotatingLog.BUFFER_BYTES, "UTF-8 byte count bounds the buffer");
        }
        log.flush();
    }
    private static void rotationAndSharedBudget() throws Exception {
        MemoryStore store = new MemoryStore();
        store.allowance = 8 * 1024L * 1024;
        store.files.put("unrelated-game-log.txt", "do not touch");
        RotatingLog first = new RotatingLog(store, 8);
        first.setBudget(8);
        fill(first, 160);
        check(store.deletes > 0 && first.fileCount() <= 16 && store.used() <= store.allowance, "Oldest files rotate within an 8 MiB global allowance");
        RotatingLog second = new RotatingLog(store, 8); // Another campaign/session shares these same slots.
        second.setBudget(8);
        fill(second, 160);
        check(second.fileCount() <= 16 && store.used() <= store.allowance && "do not touch".equals(store.files.get("unrelated-game-log.txt")),
                "A new session gets no extra quota and unrelated files survive");
        final int[] lines = {0};
        second.readLines(line -> {
            try { new org.json.JSONObject(line); lines[0]++; }
            catch (org.json.JSONException ex) { throw new IOException(ex); }
        });
        check(lines[0] > 0, "Rotated files retain complete parseable UTF-8 JSON lines");
    }
    private static void shrinkAndReadOnlyReports() throws Exception {
        MemoryStore store = new MemoryStore();
        RotatingLog log = new RotatingLog(store, 32);
        fill(log, 180);
        check(store.used() > 8 * 1024L * 1024, "Fixture actually exceeds the reduced allowance");
        int writes = store.writes, deletes = store.deletes;
        RotatingLog reader = new RotatingLog(store, 8);
        reader.readLines(line -> { });
        check(store.writes == writes && store.deletes == deletes, "Queries while disabled perform no writes or rotation");
        store.allowance = 8 * 1024L * 1024;
        log.setBudget(8);
        check(store.used() <= store.allowance && log.fileCount() <= 16, "Lowering the enabled allowance trims existing archives");
        fill(log, 3);
    }
    private static void failedDeletionAndDamagedSlots() throws Exception {
        MemoryStore store = new MemoryStore();
        RotatingLog log = new RotatingLog(store, 32);
        fill(log, 180);
        store.failDelete = true;
        int writes = store.writes;
        try { log.setBudget(8); throw new AssertionError("Expected rotation failure"); } catch (IOException expected) { }
        try { log.append("{}"); throw new AssertionError("Expected stopped writer"); } catch (IOException expected) { }
        check(store.writes == writes, "Deletion failure prohibits subsequent writes");
        MemoryStore damaged = new MemoryStore();
        damaged.files.put(RotatingLog.name(0), "not a recorder header");
        try { new RotatingLog(damaged, 32); throw new AssertionError("Expected damaged-slot rejection"); } catch (IOException expected) { }
        check(damaged.writes == 0 && damaged.deletes == 0, "Unrecognized files are preserved and recording fails closed");
    }
}
