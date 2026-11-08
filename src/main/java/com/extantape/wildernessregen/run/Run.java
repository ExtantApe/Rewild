package com.extantape.wildernessregen.run;

import com.extantape.wildernessregen.region.RegionFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * One proposed reset of one world, from scan to approval to apply, and possibly undo. Lives in its own folder:
 * run.properties for its state, chunks.txt for what the scan proposed, plan.txt for what the startup step decided to
 * do, journal.log for progress, and reference/ for terrain used by below-ground repairs.
 */
public final class Run {
    public enum State { PROPOSED, APPROVED, APPLYING, APPLIED, FAILED, EXPIRED, CANCELLED, UNDO_REQUESTED, UNDOING, UNDONE }

    /** A chunk in a run. cutY is Integer.MIN_VALUE for a full reset, or the height below which a repair applies. */
    public record Entry(int x, int z, int cutY) {
        public boolean full() {
            return cutY == Integer.MIN_VALUE;
        }

        String line() {
            return full() ? "full " + x + " " + z : "under " + x + " " + z + " " + cutY;
        }

        static Entry parse(String line) {
            String[] p = line.trim().split("\\s+");
            if (p[0].equals("full")) return new Entry(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.MIN_VALUE);
            if (p[0].equals("under")) return new Entry(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            throw new IllegalArgumentException("bad run line: " + line);
        }
    }

    public final String id;
    public final Path dir;
    private final Properties props = new Properties();

    private Run(String id, Path dir) {
        this.id = id;
        this.dir = dir;
    }

    public static Run create(Path runsDir, String id) throws IOException {
        Path dir = runsDir.resolve(id);
        if (Files.exists(dir)) throw new IOException("run " + id + " already exists");
        Files.createDirectories(dir);
        Run r = new Run(id, dir);
        r.props.setProperty("id", id);
        return r;
    }

    public static Run load(Path dir) throws IOException {
        Run r = new Run(dir.getFileName().toString(), dir);
        try (InputStream in = Files.newInputStream(dir.resolve("run.properties"))) {
            r.props.load(in);
        }
        return r;
    }

    /** Every run, oldest first. Folders that can't be read are skipped and reported in {@code problems}. */
    public static List<Run> all(Path runsDir, List<String> problems) {
        List<Run> out = new ArrayList<>();
        if (!Files.isDirectory(runsDir)) return out;
        try (Stream<Path> s = Files.list(runsDir)) {
            for (Path d : s.sorted().toList()) {
                if (!Files.isRegularFile(d.resolve("run.properties"))) continue;
                try {
                    out.add(load(d));
                } catch (IOException e) {
                    problems.add(d.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problems.add(runsDir + ": " + e.getMessage());
        }
        return out;
    }

    public String get(String key) {
        return props.getProperty(key);
    }

    public long getLong(String key) {
        String v = props.getProperty(key);
        return v == null ? 0 : Long.parseLong(v);
    }

    public void set(String key, String value) {
        if (value == null) props.remove(key);
        else props.setProperty(key, value);
    }

    public State state() {
        return State.valueOf(props.getProperty("state", "PROPOSED"));
    }

    public void setState(State s, String note) throws IOException {
        props.setProperty("state", s.name());
        props.setProperty("stateAt", Long.toString(System.currentTimeMillis()));
        if (note != null) props.setProperty("note", note);
        save();
    }

    public void save() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        props.store(out, "WildernessRegen run");
        RegionFile.writeFileAtomically(dir.resolve("run.properties"), out.toByteArray());
    }

    public List<Entry> chunks() throws IOException {
        return readEntries(dir.resolve("chunks.txt"));
    }

    public void writeChunks(List<Entry> entries) throws IOException {
        writeEntries(dir.resolve("chunks.txt"), entries);
    }

    public List<Entry> plan() throws IOException {
        return readEntries(dir.resolve("plan.txt"));
    }

    public void writePlan(List<Entry> entries) throws IOException {
        writeEntries(dir.resolve("plan.txt"), entries);
    }

    private static List<Entry> readEntries(Path p) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!Files.exists(p)) return out;
        for (String line : Files.readAllLines(p)) if (!line.isBlank() && !line.startsWith("#")) out.add(Entry.parse(line));
        return out;
    }

    private static void writeEntries(Path p, List<Entry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(e.line()).append('\n');
        RegionFile.writeFileAtomically(p, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Path referenceFile(int x, int z) {
        return dir.resolve("reference").resolve(x + "_" + z + ".nbt");
    }

    // ---- journal ----

    public List<String> journal() throws IOException {
        Path j = dir.resolve("journal.log");
        return Files.exists(j) ? Files.readAllLines(j) : List.of();
    }

    public void log(String line) throws IOException {
        Path j = dir.resolve("journal.log");
        try (var ch = java.nio.channels.FileChannel.open(j, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap((System.currentTimeMillis() + " " + line + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
    }

    public boolean journalHas(String marker) throws IOException {
        for (String l : journal()) {
            int sp = l.indexOf(' ');
            if (sp >= 0 && l.substring(sp + 1).equals(marker)) return true;
        }
        return false;
    }
}
