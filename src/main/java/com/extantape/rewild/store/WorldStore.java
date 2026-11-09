package com.extantape.rewild.store;

import com.extantape.rewild.region.RegionFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Tracking data for one world, kept in its own folder: a snapshot of every chunk record, a log of records changed
 * since that snapshot, and a small properties file of facts about the world. Changes reach the log within a second.
 * The snapshot and log carry a generation number, so a crash during compaction never replays stale records.
 *
 * <p>Uses no server API, so the startup step can read and update it before worlds load.
 */
public final class WorldStore {
    private static final int MAGIC = 0x57525331; // "WRS1"

    private final Path dir;
    private final Map<Long, ChunkRecord> records = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    private final Properties meta = new Properties();
    private final List<ProtectedArea> protectedAreas = new ArrayList<>();
    private long generation;
    private FileChannel log;

    public record ProtectedArea(int minX, int minZ, int maxX, int maxZ, String note) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private WorldStore(Path dir) {
        this.dir = dir;
    }

    /** Opens a world's store, creating the folder if needed. Throws if the files exist but can't be read. */
    public static WorldStore open(Path dir) throws IOException {
        Files.createDirectories(dir);
        WorldStore s = new WorldStore(dir);
        s.load();
        return s;
    }

    public Path dir() {
        return dir;
    }

    private void load() throws IOException {
        Path metaFile = dir.resolve("world.properties");
        if (Files.exists(metaFile)) {
            try (InputStream in = Files.newInputStream(metaFile)) {
                meta.load(in);
            }
        }
        Path protect = dir.resolve("protected.txt");
        if (Files.exists(protect)) {
            for (String line : Files.readAllLines(protect)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t", 5);
                if (p.length < 4) throw new IOException(protect + " has a bad line: " + line);
                protectedAreas.add(new ProtectedArea(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]), p.length > 4 ? p[4] : ""));
            }
        }
        generation = Long.parseLong(meta.getProperty("generation", "0"));
        Path snapshot = dir.resolve("chunks." + generation + ".bin");
        if (Files.exists(snapshot)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(snapshot)))) {
                if (in.readInt() != MAGIC) throw new IOException(snapshot + " isn't a Rewild store");
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    ChunkRecord r = ChunkRecord.read(in);
                    records.put(r.key(), r);
                }
            }
        } else if (generation != 0) {
            throw new IOException(snapshot + " is missing, so tracking history can't be trusted");
        }
        Path logFile = logPath(generation);
        if (Files.exists(logFile)) replay(logFile);
    }

    private Path logPath(long gen) {
        return dir.resolve("chunks." + gen + ".log");
    }

    /** Applies each complete frame in the log. A torn frame at the end, from a crash mid-write, is ignored. */
    private void replay(Path logFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(logFile)))) {
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    return;
                }
                if (length <= 0 || length > 64 * 1024 * 1024) return;
                byte[] body = new byte[length];
                long crc;
                try {
                    in.readFully(body);
                    crc = in.readLong();
                } catch (EOFException e) {
                    return;
                }
                CRC32 c = new CRC32();
                c.update(body);
                if (c.getValue() != crc) return;
                ChunkRecord r = ChunkRecord.read(new DataInputStream(new java.io.ByteArrayInputStream(body)));
                records.put(r.key(), r);
            }
        }
    }

    // ---- records ----

    public synchronized ChunkRecord get(int x, int z) {
        return records.get(ChunkRecord.key(x, z));
    }

    /** Runs an edit on a chunk's record, creating it if needed, and marks it for saving. */
    public synchronized ChunkRecord edit(int x, int z, Consumer<ChunkRecord> change) {
        long k = ChunkRecord.key(x, z);
        ChunkRecord r = records.computeIfAbsent(k, kk -> new ChunkRecord(x, z));
        change.accept(r);
        dirty.add(k);
        return r;
    }

    /** Runs an edit only if the chunk already has a record. */
    public synchronized void editIfPresent(int x, int z, Consumer<ChunkRecord> change) {
        long k = ChunkRecord.key(x, z);
        ChunkRecord r = records.get(k);
        if (r == null) return;
        change.accept(r);
        dirty.add(k);
    }

    /** Replaces a chunk's record with a saved copy, as an undo does. */
    public synchronized void put(ChunkRecord r) {
        records.put(r.key(), r.copy());
        dirty.add(r.key());
    }

    public synchronized void editAll(Consumer<ChunkRecord> change) {
        for (ChunkRecord r : records.values()) {
            change.accept(r);
            dirty.add(r.key());
        }
    }

    /** A deep copy of every record, for scanning off the main thread. */
    public synchronized Map<Long, ChunkRecord> snapshot() {
        Map<Long, ChunkRecord> out = new HashMap<>(records.size() * 2);
        for (var e : records.entrySet()) out.put(e.getKey(), e.getValue().copy());
        return out;
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized Collection<ChunkRecord> sample(int n, java.util.function.Predicate<ChunkRecord> filter) {
        List<ChunkRecord> out = new ArrayList<>();
        for (ChunkRecord r : records.values()) {
            if (out.size() >= n) break;
            if (filter.test(r)) out.add(r.copy());
        }
        return out;
    }

    // ---- facts about the world ----

    public synchronized String meta(String key) {
        return meta.getProperty(key);
    }

    public synchronized String meta(String key, String fallback) {
        return meta.getProperty(key, fallback);
    }

    public synchronized void setMeta(String key, String value) throws IOException {
        if (value == null) meta.remove(key);
        else meta.setProperty(key, value);
        writeMeta();
    }

    public synchronized void setMeta(Map<String, String> values) throws IOException {
        values.forEach((k, v) -> { if (v == null) meta.remove(k); else meta.setProperty(k, v); });
        writeMeta();
    }

    private void writeMeta() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        meta.store(out, "Rewild world facts. Don't edit while the server runs.");
        RegionFile.writeFileAtomically(dir.resolve("world.properties"), out.toByteArray());
    }

    public synchronized List<ProtectedArea> protectedAreas() {
        return List.copyOf(protectedAreas);
    }

    public synchronized void addProtected(ProtectedArea a) throws IOException {
        protectedAreas.add(a);
        writeProtected();
    }

    public synchronized int removeProtectedAt(int x, int z) throws IOException {
        int before = protectedAreas.size();
        protectedAreas.removeIf(a -> a.contains(x, z));
        writeProtected();
        return before - protectedAreas.size();
    }

    private void writeProtected() throws IOException {
        StringBuilder sb = new StringBuilder("# Admin-protected areas, in chunk coordinates: minX minZ maxX maxZ note\n");
        for (ProtectedArea a : protectedAreas) {
            sb.append(a.minX()).append('\t').append(a.minZ()).append('\t').append(a.maxX()).append('\t').append(a.maxZ())
                    .append('\t').append(a.note().replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        RegionFile.writeFileAtomically(dir.resolve("protected.txt"), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---- saving ----

    /** Appends every changed record to the log and forces it to disk. */
    public void flush() throws IOException {
        List<byte[]> frames = new ArrayList<>();
        synchronized (this) {
            if (dirty.isEmpty()) return;
            for (long k : dirty) {
                ChunkRecord r = records.get(k);
                if (r == null) continue;
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                r.write(new DataOutputStream(body));
                frames.add(body.toByteArray());
            }
            dirty.clear();
            if (log == null) {
                log = FileChannel.open(logPath(generation), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
            for (byte[] body : frames) {
                CRC32 c = new CRC32();
                c.update(body);
                ByteBuffer buf = ByteBuffer.allocate(4 + body.length + 8);
                buf.putInt(body.length).put(body).putLong(c.getValue()).flip();
                while (buf.hasRemaining()) log.write(buf);
            }
            log.force(false);
        }
    }

    /** Writes a fresh snapshot under the next generation, then drops the old snapshot and log. */
    public synchronized void compact() throws IOException {
        flush();
        long next = generation + 1;
        Path snapshot = dir.resolve("chunks." + next + ".bin");
        Path tmp = dir.resolve("chunks." + next + ".bin.wr-tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(ch), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(records.size());
            for (ChunkRecord r : records.values()) r.write(out);
            out.flush();
            ch.force(true);
        }
        Files.move(tmp, snapshot, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        RegionFile.syncDirectory(dir);
        long old = generation;
        generation = next;
        dirty.clear();
        meta.setProperty("generation", Long.toString(next));
        writeMeta();
        if (log != null) {
            log.close();
            log = null;
        }
        Files.deleteIfExists(logPath(old));
        Files.deleteIfExists(dir.resolve("chunks." + old + ".bin"));
    }

    public synchronized void close() throws IOException {
        if (log != null) {
            log.close();
            log = null;
        }
    }
}
