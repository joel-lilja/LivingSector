package livingsector.debug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;

/** Fixed namespace; no directory enumeration or unbounded index. All bytes count toward the cap. */
public final class RotatingLog {
    public static final String DIRECTORY = "living-sector-debug/";
    public static final int FILE_BYTES = 512 * 1024, BUFFER_BYTES = 64 * 1024, SLOTS = 256;
    public interface Store {
        boolean exists(String name) throws IOException;
        String read(String name) throws IOException;
        void write(String name, String data) throws IOException;
        void delete(String name) throws IOException;
    }
    private static final class File {
        int slot, bytes;
        long order;
        File(int slot, int bytes, long order) { this.slot = slot; this.bytes = bytes; this.order = order; }
    }
    private final Store store;
    private final List<File> files = new ArrayList<File>();
    private final StringBuilder pending = new StringBuilder();
    private int pendingBytes;
    private long budget, used, nextOrder;
    private File current;
    private boolean failed;

    public RotatingLog(Store store, int mebibytes) throws IOException {
        this.store = store;
        budget = budget(mebibytes);
        // Runs only when explicitly enabled or queried. Recovers even after a lost/interrupted write.
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!store.exists(name(slot))) continue;
            String content = store.read(name(slot));
            int length = bytes(content);
            try {
                JSONObject header = new JSONObject(content.split("\n", 2)[0]);
                if (!"living-sector-recorder".equals(header.getString("owner"))) throw new IOException("Unrecognized recorder slot " + name(slot));
                long order = header.getLong("order");
                if (order < 0 || order == Long.MAX_VALUE) throw new IOException("Invalid recorder order");
                files.add(new File(slot, length, order));
                nextOrder = Math.max(nextOrder, order + 1);
                used += length;
            } catch (org.json.JSONException ex) {
                // Fail closed: never delete an unrecognized file or grow beside a damaged archive.
                throw new IOException("Damaged recorder slot " + name(slot) + "; move it aside before retrying", ex);
            }
        }
        files.sort(Comparator.comparingLong(f -> f.order));
    }

    public void setBudget(int mebibytes) throws IOException {
        ensureHealthy();
        budget = budget(mebibytes);
        makeRoom(0);
        while (files.size() > budget / FILE_BYTES) removeOldest();
    }
    public void append(String line) throws IOException {
        ensureHealthy();
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) throw new IOException("Recorder event must be one JSON line");
        int size = bytes(line) + 1;
        if (size > BUFFER_BYTES) throw new IOException("Recorder event exceeds buffer limit");
        if (pendingBytes + size > BUFFER_BYTES) flush();
        pending.append(line).append('\n');
        pendingBytes += size;
    }
    public void flush() throws IOException {
        ensureHealthy();
        if (pendingBytes == 0) return;
        try {
            if (current != null && current.bytes + pendingBytes > FILE_BYTES) current = null;
            String content;
            if (current == null) {
                // Reserve a slot as well as bytes, including the file header itself.
                if (files.size() >= budget / FILE_BYTES) removeOldest();
                int slot = 0;
                while (occupied(slot)) slot++;
                content = "{\"owner\":\"living-sector-recorder\",\"schema\":1,\"order\":" + nextOrder++ + "}\n";
                makeRoom(bytes(content) + pendingBytes);
                current = new File(slot, 0, nextOrder - 1);
                files.add(current);
            } else {
                makeRoom(pendingBytes);
                // Current is newest and cannot be evicted while appending <=512 KiB under an >=8 MiB cap.
                content = store.read(name(current.slot));
                if (bytes(content) != current.bytes) throw new IOException("Recorder file changed outside this session");
            }
            String data = content + pending;
            int size = bytes(data);
            if (size > FILE_BYTES || used - current.bytes + size > budget) throw new IOException("Recorder budget invariant failed");
            store.write(name(current.slot), data);
            used += size - current.bytes;
            current.bytes = size;
            pending.setLength(0);
            pendingBytes = 0;
        } catch (IOException | RuntimeException ex) {
            failed = true;
            throw new IOException("Recorder write/rotation stopped", ex);
        }
    }
    /** Streaming reads: one bounded file at a time, without retaining the whole archive in memory. */
    public void readLines(LineConsumer consumer) throws IOException {
        for (File file : files) {
            String data = store.read(name(file.slot));
            for (String line : data.split("\n")) {
                if (!line.isEmpty()) consumer.accept(line);
            }
        }
    }
    public interface LineConsumer { void accept(String line) throws IOException; }
    public long usedBytes() { return used; }
    public int bufferedBytes() { return pendingBytes; }
    public int fileCount() { return files.size(); }
    private void makeRoom(long extra) throws IOException {
        while (used + extra > budget) removeOldest();
    }
    private void removeOldest() throws IOException {
        if (files.isEmpty()) throw new IOException("No room for recorder data");
        File oldest = files.get(0);
        try {
            store.delete(name(oldest.slot));
            if (store.exists(name(oldest.slot))) throw new IOException("Could not delete " + name(oldest.slot));
        } catch (IOException | RuntimeException ex) { failed = true; throw new IOException("Recorder rotation failed", ex); }
        files.remove(0);
        used -= oldest.bytes;
        if (current == oldest) current = null;
    }
    private boolean occupied(int slot) { for (File file : files) if (file.slot == slot) return true; return false; }
    private void ensureHealthy() throws IOException { if (failed) throw new IOException("Recorder is stopped after an I/O failure"); }
    private static long budget(int mib) { if (mib < 8 || mib > 128) throw new IllegalArgumentException("Recorder allowance must be 8–128 MiB"); return mib * 1024L * 1024; }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    public static String name(int slot) { return DIRECTORY + String.format(java.util.Locale.ROOT, "slot-%03d.jsonl", slot); }
}
