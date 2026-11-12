package com.extantape.rewild.region;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The block positions in one chunk that stay exactly as they are when the chunk rewilds. Everything else comes from
 * freshly generated terrain. Positions are chunk-local x and z (0 to 15) and the absolute y.
 */
public final class KeepMask {
    private final int minY, height;
    private final BitSet bits;

    public KeepMask(int minY, int height) {
        this.minY = minY;
        this.height = height;
        this.bits = new BitSet(height * 256);
    }

    public int minY() {
        return minY;
    }

    public int height() {
        return height;
    }

    private int index(int x, int y, int z) {
        return ((y - minY) << 8) | (z << 4) | x;
    }

    public boolean inRange(int x, int y, int z) {
        return x >= 0 && x < 16 && z >= 0 && z < 16 && y >= minY && y < minY + height;
    }

    public void keep(int x, int y, int z) {
        if (inRange(x, y, z)) bits.set(index(x, y, z));
    }

    public boolean kept(int x, int y, int z) {
        return inRange(x, y, z) && bits.get(index(x, y, z));
    }

    /** Keeps every position at or above y: what a below-ground repair leaves alone. */
    public void keepFrom(int y) {
        int from = Math.max(y, minY) - minY;
        if (from < height) bits.set(from << 8, height << 8);
    }

    /** Keeps the part of a world-coordinate box that falls inside the chunk at chunkX, chunkZ. */
    public void keepBox(int chunkX, int chunkZ, int minX, int minBoxY, int minZ, int maxX, int maxBoxY, int maxZ) {
        int x0 = Math.max(minX, chunkX * 16), x1 = Math.min(maxX, chunkX * 16 + 15);
        int z0 = Math.max(minZ, chunkZ * 16), z1 = Math.min(maxZ, chunkZ * 16 + 15);
        int y0 = Math.max(minBoxY, minY), y1 = Math.min(maxBoxY, minY + height - 1);
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) keep(x - chunkX * 16, y, z - chunkZ * 16);
    }

    public int count() {
        return bits.cardinality();
    }

    public boolean isEmpty() {
        return bits.isEmpty();
    }

    /** Every kept position as {x, y, z}. */
    public List<int[]> positions() {
        List<int[]> out = new ArrayList<>();
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) out.add(new int[]{i & 15, (i >> 8) + minY, (i >> 4) & 15});
        return out;
    }

    /** The block states of a saved chunk, read by position. */
    public interface Blocks {
        /** The state at a chunk-local position: its name, such as "minecraft:lantern", and its properties (maybe empty). */
        State at(int x, int y, int z);
    }

    public record State(String name, Map<String, String> properties) {
        public String prop(String key) {
            return properties == null ? null : properties.get(key);
        }
    }

    /** Keeps every block whose name matches, for example dirt paths or an admin's listed lanterns. */
    public void keepMatching(Blocks blocks, Predicate<String> names) {
        for (int y = minY; y < minY + height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    State s = blocks.at(x, y, z);
                    if (s != null && names.test(s.name())) keep(x, y, z);
                }
            }
        }
    }

    /**
     * Adds what each kept block needs so it never floats or splits: the other half of a door, bed or tall plant, and
     * the block it hangs from, stands on or is fixed to. Air, water and lava need nothing. One level deep: the
     * support's own support comes from the fresh terrain or is itself kept.
     */
    public void addSupports(Blocks blocks) {
        List<int[]> kept = positions();
        List<int[]> partners = new ArrayList<>();
        for (int[] p : kept) {
            State s = blocks.at(p[0], p[1], p[2]);
            if (s == null) continue;
            int[] other = partner(s, p);
            if (other != null) partners.add(other);
        }
        for (int[] o : partners) keep(o[0], o[1], o[2]);
        List<int[]> supports = new ArrayList<>();
        for (int[] p : positions()) {
            State s = blocks.at(p[0], p[1], p[2]);
            if (s == null || needsNothing(s.name())) continue;
            int[] d = support(s);
            supports.add(new int[]{p[0] + d[0], p[1] + d[1], p[2] + d[2]});
        }
        for (int[] o : supports) keep(o[0], o[1], o[2]);
    }

    static boolean needsNothing(String name) {
        String n = bare(name);
        return n.equals("air") || n.equals("cave_air") || n.equals("void_air") || n.equals("water") || n.equals("lava")
                || n.equals("bubble_column") || n.equals("light") || n.equals("barrier") || n.equals("structure_void");
    }

    static String bare(String name) {
        return name.startsWith("minecraft:") ? name.substring(10) : name;
    }

    /** The other half of a two-block thing, or null. */
    static int[] partner(State s, int[] p) {
        String half = s.prop("half");
        if ("lower".equals(half)) return new int[]{p[0], p[1] + 1, p[2]};
        if ("upper".equals(half)) return new int[]{p[0], p[1] - 1, p[2]};
        String part = s.prop("part");
        int[] f = facing(s.prop("facing"));
        if (f != null && "foot".equals(part)) return new int[]{p[0] + f[0], p[1], p[2] + f[2]};
        if (f != null && "head".equals(part)) return new int[]{p[0] - f[0], p[1], p[2] - f[2]};
        return null;
    }

    /** The offset from a block to the block holding it up. */
    static int[] support(State s) {
        String n = bare(s.name());
        int[] f = facing(s.prop("facing"));
        if ("true".equals(s.prop("hanging"))) return new int[]{0, 1, 0};
        String face = s.prop("face");
        if ("ceiling".equals(face)) return new int[]{0, 1, 0};
        if ("wall".equals(face) && f != null) return new int[]{-f[0], 0, -f[2]};
        String attach = s.prop("attachment");
        if ("ceiling".equals(attach)) return new int[]{0, 1, 0};
        if (("single_wall".equals(attach) || "double_wall".equals(attach)) && f != null) return new int[]{-f[0], 0, -f[2]};
        if (n.equals("cocoa") && f != null) return new int[]{f[0], 0, f[2]};
        if (f != null && (n.contains("wall_") || n.equals("ladder") || n.equals("tripwire_hook") || n.endsWith("_wall_fan"))) {
            return new int[]{-f[0], 0, -f[2]};
        }
        if (n.equals("hanging_roots") || n.endsWith("_hanging_sign") && !n.contains("wall") || n.equals("spore_blossom")) return new int[]{0, 1, 0};
        return new int[]{0, -1, 0};
    }

    static int[] facing(String f) {
        if (f == null) return null;
        return switch (f) {
            case "north" -> new int[]{0, 0, -1};
            case "south" -> new int[]{0, 0, 1};
            case "east" -> new int[]{1, 0, 0};
            case "west" -> new int[]{-1, 0, 0};
            default -> null;
        };
    }
}
