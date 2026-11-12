package com.extantape.rewild.keep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Block areas kept with /rewild keep, by an admin or on behalf of a plugin. Stored one per line in keeps.txt, so the
 * file stays readable without Rewild: id, owner, world, the two corners, then the note.
 */
public final class CommandKeeps {
    public static final String FILE = "keeps.txt";
    public static final String ADMIN = "admin";
    /** Largest single area, in blocks, so a typo can't keep a whole world by accident. */
    public static final long MAX_VOLUME = 16L * 16 * 400 * 64;

    public record Entry(int id, String owner, KeepPlan.Area area) {
        public boolean byAdmin() {
            return ADMIN.equalsIgnoreCase(owner);
        }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private String problem;

    public CommandKeeps(Path dataFolder) {
        this.file = dataFolder.resolve(FILE);
        load();
    }

    public void load() {
        entries.clear();
        problem = null;
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split(" ", 10);
                if (p.length < 9) throw new IOException("short line: " + line);
                entries.add(new Entry(Integer.parseInt(p[0]), p[1], new KeepPlan.Area(p[2], Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7]),
                        Integer.parseInt(p[8]), p.length > 9 ? p[9] : "")));
            }
        } catch (IOException | RuntimeException e) {
            entries.clear();
            problem = FILE + " can't be read (" + e.getMessage() + ")";
        }
    }

    /** Null if the file is fine; otherwise why nothing can reset. */
    public String problem() {
        return problem;
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }

    public Entry add(String owner, KeepPlan.Area a) throws IOException {
        if (problem != null) throw new IOException(problem);
        if (a.volume() > MAX_VOLUME) throw new IOException("that area is " + a.volume() + " blocks; one keep can be at most " + MAX_VOLUME
                + ". Split it, or use /rewild protect for whole chunks");
        for (Entry e : entries) if (e.owner().equalsIgnoreCase(owner) && sameBox(e.area(), a)) return e;
        int id = 1;
        for (Entry e : entries) id = Math.max(id, e.id() + 1);
        Entry e = new Entry(id, owner, a);
        entries.add(e);
        save();
        return e;
    }

    static boolean sameBox(KeepPlan.Area a, KeepPlan.Area b) {
        return a.world().equals(b.world()) && a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ()
                && a.maxX() == b.maxX() && a.maxY() == b.maxY() && a.maxZ() == b.maxZ();
    }

    /** Removes by id. Returns the removed entry, or null. */
    public Entry remove(int id) throws IOException {
        for (Entry e : entries) {
            if (e.id() == id) {
                entries.remove(e);
                save();
                return e;
            }
        }
        return null;
    }

    /** Removes an owner's entries with exactly these corners, so a plugin can undo its own keep without knowing ids. */
    public int removeExact(String owner, String world, int x1, int y1, int z1, int x2, int y2, int z2) throws IOException {
        KeepPlan.Area match = new KeepPlan.Area(world, x1, y1, z1, x2, y2, z2, "");
        int before = entries.size();
        entries.removeIf(e -> e.owner().equalsIgnoreCase(owner) && sameBox(e.area(), match));
        if (entries.size() != before) save();
        return before - entries.size();
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder("# Kept with /rewild keep: id owner world minX minY minZ maxX maxY maxZ note\n");
        for (Entry e : entries) {
            KeepPlan.Area a = e.area();
            sb.append(e.id()).append(' ').append(e.owner()).append(' ').append(a.world()).append(' ').append(a.minX()).append(' ')
                    .append(a.minY()).append(' ').append(a.minZ()).append(' ').append(a.maxX()).append(' ').append(a.maxY()).append(' ')
                    .append(a.maxZ()).append(' ').append(a.reason().replace('\n', ' ')).append('\n');
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(FILE + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
