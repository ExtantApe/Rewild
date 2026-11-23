package com.extantape.rewild.heal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One hole healing a step at a time: the blocks to put back and how far along it is. Saved as a plain text file:
 * <pre>
 * rewild-heal 1
 * world 6f1c...
 * created 1763800000000
 * next 1763886400000
 * done 2
 * cell 10 63 -4 minecraft:grass_block[snowy=false]
 * </pre>
 * Written to a temporary file and moved into place, so a crash leaves the old file or the new one.
 */
public final class HealJob {
    public final String id;
    public final UUID world;
    public final long created;
    public long next;
    public int done;
    public final List<HealPlan.Cell> cells;

    public HealJob(String id, UUID world, long created, long next, int done, List<HealPlan.Cell> cells) {
        this.id = id;
        this.world = world;
        this.created = created;
        this.next = next;
        this.done = done;
        this.cells = List.copyOf(cells);
    }

    /** The middle of the hole, for messages and the pause check: x, y, z. */
    public int[] center() {
        long x = 0, y = 0, z = 0;
        for (HealPlan.Cell c : cells) { x += c.x(); y += c.y(); z += c.z(); }
        int n = Math.max(1, cells.size());
        return new int[] {(int) (x / n), (int) (y / n), (int) (z / n)};
    }

    /** min x, min z, max x, max z. */
    public int[] bounds() {
        int a = Integer.MAX_VALUE, b = Integer.MAX_VALUE, c = Integer.MIN_VALUE, d = Integer.MIN_VALUE;
        for (HealPlan.Cell cell : cells) { a = Math.min(a, cell.x()); b = Math.min(b, cell.z()); c = Math.max(c, cell.x()); d = Math.max(d, cell.z()); }
        return new int[] {a, b, c, d};
    }

    public void save(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("rewild-heal 1\n");
        sb.append("world ").append(world).append('\n').append("created ").append(created).append('\n')
            .append("next ").append(next).append('\n').append("done ").append(done).append('\n');
        for (HealPlan.Cell c : cells) sb.append("cell ").append(c.x()).append(' ').append(c.y()).append(' ').append(c.z()).append(' ').append(c.data()).append('\n');
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Reads a job, or throws if the file isn't one; a bad cell line is an error too, never guessed. */
    public static HealJob load(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals("rewild-heal 1")) throw new IOException(file.getFileName() + " isn't a Rewild healing file");
        UUID world = null;
        long created = -1, next = -1;
        int done = -1;
        List<HealPlan.Cell> cells = new ArrayList<>();
        try {
            for (String l : lines.subList(1, lines.size())) {
                if (l.isBlank()) continue;
                String[] p = l.split(" ", 5);
                switch (p[0]) {
                    case "world" -> world = UUID.fromString(p[1]);
                    case "created" -> created = Long.parseLong(p[1]);
                    case "next" -> next = Long.parseLong(p[1]);
                    case "done" -> done = Integer.parseInt(p[1]);
                    case "cell" -> cells.add(new HealPlan.Cell(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[4]));
                    default -> throw new IOException("unknown line: " + l);
                }
            }
        } catch (RuntimeException e) {
            throw new IOException(file.getFileName() + " has a bad line: " + e.getMessage());
        }
        if (world == null || created < 0 || next < 0 || done < 0) throw new IOException(file.getFileName() + " is missing its header");
        String name = file.getFileName().toString();
        return new HealJob(name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name, world, created, next, done, cells);
    }
}
