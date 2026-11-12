package com.extantape.rewild.region;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewilds a saved chunk: every block comes from a freshly generated copy of the same chunk, except the positions in a
 * {@link KeepMask}, which stay exactly as they were, with their block entities and scheduled ticks. Works on NBT with
 * the server stopped, like every other change Rewild makes to a world.
 */
public final class Merge {
    private Merge() {}

    /** One section's block states, decoded to a palette and a per-block index. */
    static final class Section {
        final List<Object> palette;
        final int[] index = new int[4096];

        Section(Nbt.Compound section) throws IOException {
            Nbt.Compound states = section == null ? null : section.getCompound("block_states");
            Nbt.NbtList p = states == null ? null : states.getList("palette");
            if (p == null || p.items().isEmpty()) throw new IOException("a section has no block palette");
            palette = p.items();
            long[] data = states.get("data") instanceof long[] d ? d : null;
            if (palette.size() > 1) {
                if (data == null) throw new IOException("a section has a palette but no data");
                int bits = bits(palette.size());
                for (int i = 0; i < 4096; i++) {
                    int v = ChunkFacts.paletteIndex(data, bits, i);
                    if (v >= palette.size()) throw new IOException("a section's data points outside its palette");
                    index[i] = v;
                }
            }
        }

        Object at(int i) {
            return palette.get(index[i]);
        }
    }

    static int bits(int paletteSize) {
        return Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    /** Packs 4096 palette entries the way the game saves them: entries never span two longs. */
    static Nbt.Compound encode(Object[] blocks) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<Object> palette = new ArrayList<>();
        int[] idx = new int[4096];
        for (int i = 0; i < 4096; i++) {
            String key = keyOf(blocks[i]);
            Integer at = seen.get(key);
            if (at == null) {
                at = palette.size();
                seen.put(key, at);
                palette.add(Underground.deepCopyAnyPublic(blocks[i]));
            }
            idx[i] = at;
        }
        Nbt.Compound out = new Nbt.Compound();
        out.put("palette", paletteList(palette));
        if (palette.size() > 1) {
            int bits = bits(palette.size()), perLong = 64 / bits;
            long[] data = new long[(4096 + perLong - 1) / perLong];
            for (int i = 0; i < 4096; i++) data[i / perLong] |= ((long) idx[i]) << ((i % perLong) * bits);
            out.put("data", data);
        }
        return out;
    }

    /**
     * Writes a palette the way the game does. Since 26.x a state with no properties is saved as a bare name, so a
     * palette of only those is a list of strings, and a palette mixing them with {id, properties} entries wraps each
     * bare name as {"": name}. A merged section can draw from both kinds of palette, so it's normalised here.
     */
    static Nbt.NbtList paletteList(List<Object> palette) {
        boolean allStrings = true, anyString = false;
        for (Object e : palette) {
            boolean str = e instanceof String;
            allStrings &= str;
            anyString |= str;
        }
        if (allStrings) return new Nbt.NbtList(Nbt.STRING, palette);
        if (!anyString) return new Nbt.NbtList(Nbt.COMPOUND, palette);
        List<Object> wrapped = new ArrayList<>();
        for (Object e : palette) {
            if (e instanceof String str) {
                Nbt.Compound w = new Nbt.Compound();
                w.put("", str);
                wrapped.add(w);
            } else {
                wrapped.add(e);
            }
        }
        return new Nbt.NbtList(Nbt.COMPOUND, wrapped);
    }

    /**
     * A stable text form of a palette entry, so equal states share one entry. Saves have used three shapes: {Name,
     * Properties} before 26.x, and since 26.x {id, properties} or, with no properties, the name alone wrapped as {"":
     * name} in a mixed list. All three name the same state the same way here.
     */
    static String keyOf(Object entry) {
        StringBuilder sb = new StringBuilder(ChunkFacts.blockName(entry));
        Map<String, String> props = properties(entry);
        new java.util.TreeMap<>(props).forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        return sb.toString();
    }

    /** A palette entry's properties, whichever shape it was saved in. Empty if it has none. */
    static Map<String, String> properties(Object entry) {
        Map<String, String> out = new HashMap<>();
        if (entry instanceof Nbt.Compound c) {
            Nbt.Compound p = c.getCompound("Properties") != null ? c.getCompound("Properties") : c.getCompound("properties");
            if (p == null && c.get("") instanceof Nbt.Compound inner) return properties(inner);
            if (p != null) p.forEach((k, v) -> out.put(k, String.valueOf(v)));
        }
        return out;
    }

    /** Reads block states of a saved chunk by position, for working out what's kept. */
    public static KeepMask.Blocks blocks(Nbt.Compound chunk) throws IOException {
        Map<Integer, Section> sections = new HashMap<>();
        Nbt.NbtList list = chunk.getList("sections");
        if (list != null) for (Nbt.Compound s : list.compounds()) {
            if (s.getCompound("block_states") != null) sections.put(s.getInt("Y", 0), new Section(s));
        }
        return (x, y, z) -> {
            Section s = sections.get(y >> 4);
            if (s == null) return null;
            Object e = s.at(((y & 15) << 8) | (z << 4) | x);
            return new KeepMask.State(ChunkFacts.blockName(e), properties(e));
        };
    }

    /** Returns a new chunk: fresh terrain from {@code fresh}, with the kept positions taken from {@code real}. */
    public static Nbt.Compound chunk(Nbt.Compound real, Nbt.Compound fresh, KeepMask keep) throws IOException {
        int minY = keep.minY();
        Underground.checkSameChunk(real, fresh);
        Nbt.NbtList realSections = real.getList("sections"), freshSections = fresh.getList("sections");
        if (realSections == null || freshSections == null) throw new IOException("chunk has no sections");
        Map<Integer, Nbt.Compound> realBy = byY(realSections), freshBy = byY(freshSections);
        Underground.checkBedrock(realBy, freshBy, minY);

        Nbt.Compound out = Underground.deepCopy(real);
        List<Object> sections = new ArrayList<>();
        int lowest = minY >> 4, highest = (minY + keep.height() - 1) >> 4;
        java.util.TreeSet<Integer> ys = new java.util.TreeSet<>(realBy.keySet());
        for (int sy = lowest; sy <= highest; sy++) if (freshBy.containsKey(sy)) ys.add(sy);
        for (int sy : ys) {
            Nbt.Compound s = realBy.get(sy);
            // Sections outside the world's height hold only light data; keep them as they are.
            if (sy < lowest || sy > highest) {
                sections.add(Underground.deepCopy(s));
                continue;
            }
            Nbt.Compound f = freshBy.get(sy);
            if (f == null) throw new IOException("fresh terrain is missing section " + sy);
            if (s == null || s.getCompound("block_states") == null) {
                // Never saved with blocks, so there's nothing of the real chunk to keep here.
                sections.add(Underground.deepCopy(f));
                continue;
            }
            boolean any = false, all = true;
            for (int i = 0; i < 4096 && (all || !any); i++) {
                boolean k = keep.kept(i & 15, sy * 16 + (i >> 8), (i >> 4) & 15);
                any |= k;
                all &= k;
            }
            if (all) {
                sections.add(Underground.deepCopy(s));
                continue;
            }
            Nbt.Compound merged = Underground.deepCopy(f);
            if (any) {
                Section rs = new Section(s), fs = new Section(f);
                Object[] blocks = new Object[4096];
                for (int i = 0; i < 4096; i++) {
                    blocks[i] = keep.kept(i & 15, sy * 16 + (i >> 8), (i >> 4) & 15) ? rs.at(i) : fs.at(i);
                }
                merged.put("block_states", encode(blocks));
            }
            sections.add(merged);
        }
        out.put("sections", new Nbt.NbtList(Nbt.COMPOUND, sections));

        int cx = real.getInt("xPos", 0) * 16, cz = real.getInt("zPos", 0) * 16;
        for (String list : new String[]{"block_entities", "block_ticks", "fluid_ticks"}) {
            out.put(list, byPosition(real.getList(list), fresh.getList(list), keep, cx, cz));
        }
        out.put("PostProcessing", postProcessing(fresh.getList("PostProcessing"), keep, minY));
        // The server works out heightmaps and light again when it next loads the chunk.
        out.remove("Heightmaps");
        out.remove("starlight.light_version");
        out.put("isLightOn", (byte) 0);
        return out;
    }

    /**
     * Saved entities after a rewild: the real chunk's entities standing on kept positions, plus the fresh chunk's
     * entities (such as a village's villagers) everywhere else. {@code fresh} may be null.
     */
    public static Nbt.Compound entities(Nbt.Compound real, Nbt.Compound fresh, KeepMask keep, int cx, int cz) {
        Nbt.Compound out = Underground.deepCopy(real);
        List<Object> list = new ArrayList<>();
        Nbt.NbtList r = real.getList("Entities"), f = fresh == null ? null : fresh.getList("Entities");
        if (r != null) for (Nbt.Compound e : r.compounds()) if (entityKept(e, keep, cx, cz)) list.add(Underground.deepCopy(e));
        if (f != null) for (Nbt.Compound e : f.compounds()) if (!entityKept(e, keep, cx, cz)) list.add(Underground.deepCopy(e));
        out.put("Entities", new Nbt.NbtList(Nbt.COMPOUND, list));
        return out;
    }

    static boolean entityKept(Nbt.Compound e, KeepMask keep, int cx, int cz) {
        if (!(e.getList("Pos") instanceof Nbt.NbtList pos) || pos.items().size() != 3) return false;
        // Saved with this chunk, so it's in this chunk; floorMod keeps a mob right on the border inside it.
        int x = Math.floorMod((int) Math.floor(((Number) pos.items().get(0)).doubleValue()), 16);
        int y = (int) Math.floor(((Number) pos.items().get(1)).doubleValue());
        int z = Math.floorMod((int) Math.floor(((Number) pos.items().get(2)).doubleValue()), 16);
        return keep.kept(x, y, z);
    }

    /**
     * Points of interest (beds, workstations, bells) after a rewild: only sections kept whole stay; the server finds the
     * rest again from the blocks when it loads the chunk.
     */
    public static Nbt.Compound poi(Nbt.Compound poi, KeepMask keep) {
        Nbt.Compound out = Underground.deepCopy(poi);
        Nbt.Compound sections = poi.getCompound("Sections");
        if (sections == null) return out;
        Nbt.Compound kept = new Nbt.Compound();
        for (var e : sections.entrySet()) {
            int y;
            try {
                y = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            if (wholeSectionKept(keep, y)) kept.put(e.getKey(), Underground.deepCopyAnyPublic(e.getValue()));
        }
        out.put("Sections", kept);
        return out;
    }

    static boolean wholeSectionKept(KeepMask keep, int sy) {
        for (int i = 0; i < 4096; i++) if (!keep.kept(i & 15, sy * 16 + (i >> 8), (i >> 4) & 15)) return false;
        return true;
    }

    /** Entries from {@code real} at kept positions, and from {@code fresh} everywhere else. */
    static Nbt.NbtList byPosition(Nbt.NbtList real, Nbt.NbtList fresh, KeepMask keep, int cx, int cz) {
        List<Object> out = new ArrayList<>();
        if (real != null) for (Nbt.Compound c : real.compounds()) if (keptAt(c, keep, cx, cz)) out.add(Underground.deepCopy(c));
        if (fresh != null) for (Nbt.Compound c : fresh.compounds()) if (!keptAt(c, keep, cx, cz)) out.add(Underground.deepCopy(c));
        return new Nbt.NbtList(Nbt.COMPOUND, out);
    }

    private static boolean keptAt(Nbt.Compound c, KeepMask keep, int cx, int cz) {
        return keep.kept(c.getInt("x", 0) - cx, c.getInt("y", Integer.MIN_VALUE), c.getInt("z", 0) - cz);
    }

    /** The fresh chunk's pending block updates, minus any at kept positions. */
    static Nbt.NbtList postProcessing(Nbt.NbtList fresh, KeepMask keep, int minY) {
        List<Object> out = new ArrayList<>();
        if (fresh == null) return new Nbt.NbtList(Nbt.LIST, out);
        int minSection = minY >> 4;
        for (int i = 0; i < fresh.items().size(); i++) {
            List<Object> kept = new ArrayList<>();
            if (fresh.items().get(i) instanceof Nbt.NbtList shorts) {
                for (Object o : shorts.items()) {
                    int p = ((Number) o).intValue();
                    int x = p & 15, y = (minSection + i) * 16 + ((p >> 8) & 15), z = (p >> 4) & 15;
                    if (!keep.kept(x, y, z)) kept.add(o);
                }
            }
            out.add(new Nbt.NbtList(Nbt.SHORT, kept));
        }
        return new Nbt.NbtList(Nbt.LIST, out);
    }

    private static Map<Integer, Nbt.Compound> byY(Nbt.NbtList sections) {
        Map<Integer, Nbt.Compound> m = new HashMap<>();
        for (Nbt.Compound s : sections.compounds()) m.put(s.getInt("Y", 0), s);
        return m;
    }
}
