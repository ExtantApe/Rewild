package com.extantape.wildernessregen.region;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Below-ground repair on saved chunk data: every 16-block section under the cut is replaced with the same section from
 * a freshly generated copy of the chunk, and the surface above is left exactly as it was. Works on NBT with the server
 * stopped, so it is as crash-safe as a full reset.
 */
public final class Underground {
    private Underground() {}

    /** Share of bedrock positions that must match the reference before it's trusted as the same terrain. */
    static final double BEDROCK_MATCH = 0.98;

    /**
     * Returns a new chunk: {@code real} with sections below {@code cutY} taken from {@code reference}. Throws if the two
     * don't look like the same chunk of the same world.
     */
    public static Nbt.Compound repairChunk(Nbt.Compound real, Nbt.Compound reference, int cutY, int minY) throws IOException {
        if (cutY % 16 != 0) throw new IOException("cut " + cutY + " isn't on a section boundary");
        int rv = real.getInt("DataVersion", -1), fv = reference.getInt("DataVersion", -2);
        if (rv != fv) throw new IOException("reference data version " + fv + " doesn't match the world's " + rv);
        if (real.getInt("xPos", 0) != reference.getInt("xPos", 1) || real.getInt("zPos", 0) != reference.getInt("zPos", 1)) {
            throw new IOException("reference is a different chunk");
        }
        if (!isFull(real) || !isFull(reference)) throw new IOException("chunk isn't fully generated");
        int cutSection = cutY >> 4;
        Map<Integer, Nbt.Compound> refSections = byY(reference.getList("sections"));
        Nbt.NbtList realSections = real.getList("sections");
        if (realSections == null) throw new IOException("chunk has no sections");
        checkBedrock(byY(realSections), refSections, minY);

        Nbt.Compound out = deepCopy(real);
        List<Object> sections = new ArrayList<>();
        for (Nbt.Compound s : realSections.compounds()) {
            int y = s.getInt("Y", 0);
            // Sections below the world's floor hold only light data; keep them as they are.
            if (y < cutSection && y >= minY >> 4) {
                Nbt.Compound r = refSections.get(y);
                if (r == null) throw new IOException("reference is missing section " + y);
                sections.add(deepCopy(r));
            } else {
                sections.add(deepCopy(s));
            }
        }
        out.put("sections", new Nbt.NbtList(Nbt.COMPOUND, sections));

        out.put("block_entities", mergeByY(real.getList("block_entities"), reference.getList("block_entities"), cutY));
        out.put("block_ticks", mergeByY(real.getList("block_ticks"), reference.getList("block_ticks"), cutY));
        out.put("fluid_ticks", mergeByY(real.getList("fluid_ticks"), reference.getList("fluid_ticks"), cutY));

        Nbt.NbtList realPost = real.getList("PostProcessing"), refPost = reference.getList("PostProcessing");
        if (realPost != null) {
            int minSection = minY >> 4;
            List<Object> post = new ArrayList<>();
            for (int i = 0; i < realPost.items().size(); i++) {
                boolean below = minSection + i < cutSection;
                Object item = below && refPost != null && i < refPost.items().size() ? refPost.items().get(i)
                        : below ? new Nbt.NbtList(Nbt.SHORT, List.of()) : realPost.items().get(i);
                post.add(deepCopyAny(item));
            }
            out.put("PostProcessing", new Nbt.NbtList(Nbt.LIST, post));
        }
        // Make the server work out light again when it next loads the chunk.
        out.remove("starlight.light_version");
        out.put("isLightOn", (byte) 0);
        return out;
    }

    /** Drops saved entities below the cut. Anything that would have kept the chunk was already checked. */
    public static Nbt.Compound repairEntities(Nbt.Compound entities, int cutY) {
        Nbt.Compound out = deepCopy(entities);
        Nbt.NbtList list = entities.getList("Entities");
        if (list == null) return out;
        List<Object> kept = new ArrayList<>();
        for (Nbt.Compound e : list.compounds()) {
            if (entityY(e) >= cutY) kept.add(deepCopy(e));
        }
        out.put("Entities", new Nbt.NbtList(Nbt.COMPOUND, kept));
        return out;
    }

    /** Drops points of interest (beds, workstations, bells) recorded below the cut; the server finds new ones. */
    public static Nbt.Compound repairPoi(Nbt.Compound poi, int cutY) {
        Nbt.Compound out = deepCopy(poi);
        Nbt.Compound sections = poi.getCompound("Sections");
        if (sections == null) return out;
        Nbt.Compound kept = new Nbt.Compound();
        int cutSection = cutY >> 4;
        for (var e : sections.entrySet()) {
            int y;
            try {
                y = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                kept.put(e.getKey(), deepCopyAny(e.getValue()));
                continue;
            }
            if (y >= cutSection) kept.put(e.getKey(), deepCopyAny(e.getValue()));
        }
        out.put("Sections", kept);
        return out;
    }

    static double entityY(Nbt.Compound e) {
        if (e.getList("Pos") instanceof Nbt.NbtList pos && pos.items().size() == 3 && pos.items().get(1) instanceof Number n) {
            return n.doubleValue();
        }
        return Double.MAX_VALUE;
    }

    private static boolean isFull(Nbt.Compound c) {
        String s = c.getString("Status");
        return "minecraft:full".equals(s) || "full".equals(s);
    }

    private static Map<Integer, Nbt.Compound> byY(Nbt.NbtList sections) {
        Map<Integer, Nbt.Compound> m = new HashMap<>();
        if (sections != null) for (Nbt.Compound s : sections.compounds()) m.put(s.getInt("Y", 0), s);
        return m;
    }

    /** The world's lowest few layers are bedrock in a seed-random pattern. A different pattern means different terrain. */
    static void checkBedrock(Map<Integer, Nbt.Compound> real, Map<Integer, Nbt.Compound> ref, int minY) throws IOException {
        int section = minY >> 4;
        boolean[] a = bedrock(real.get(section)), b = bedrock(ref.get(section));
        if (a == null || b == null) throw new IOException("lowest section missing");
        int total = 0, same = 0;
        for (int i = 0; i < a.length; i++) {
            if (!a[i] && !b[i]) continue;
            total++;
            if (a[i] == b[i]) same++;
        }
        if (total == 0) return; // No bedrock (custom worlds); nothing to compare.
        if (same < total * BEDROCK_MATCH) {
            throw new IOException("bedrock pattern differs from the reference (" + same + " of " + total
                    + " match), so the reference isn't the same terrain");
        }
    }

    /** Bedrock positions in a section's lowest five layers, or null if the section is missing. */
    private static boolean[] bedrock(Nbt.Compound section) {
        if (section == null) return null;
        Nbt.Compound states = section.getCompound("block_states");
        if (states == null) return null;
        String[] palette = ChunkFacts.palette(states);
        long[] data = states.get("data") instanceof long[] d ? d : null;
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
        boolean[] out = new boolean[5 * 256];
        for (int i = 0; i < out.length; i++) {
            int idx = palette.length == 1 || data == null ? 0 : ChunkFacts.paletteIndex(data, bits, i);
            out[i] = idx < palette.length && "minecraft:bedrock".equals(palette[idx]);
        }
        return out;
    }

    /** The real list's entries at or above the cut, plus the reference's entries below it. */
    private static Nbt.NbtList mergeByY(Nbt.NbtList real, Nbt.NbtList ref, int cutY) {
        List<Object> out = new ArrayList<>();
        if (real != null) for (Nbt.Compound c : real.compounds()) if (c.getInt("y", Integer.MAX_VALUE) >= cutY) out.add(deepCopy(c));
        if (ref != null) for (Nbt.Compound c : ref.compounds()) if (c.getInt("y", Integer.MAX_VALUE) < cutY) out.add(deepCopy(c));
        return new Nbt.NbtList(Nbt.COMPOUND, out);
    }

    public static Nbt.Compound deepCopy(Nbt.Compound c) {
        return (Nbt.Compound) deepCopyAny(c);
    }

    private static Object deepCopyAny(Object o) {
        if (o instanceof Nbt.Compound c) {
            Nbt.Compound out = new Nbt.Compound();
            c.forEach((k, v) -> out.put(k, deepCopyAny(v)));
            return out;
        }
        if (o instanceof Nbt.NbtList l) {
            List<Object> items = new ArrayList<>();
            for (Object i : l.items()) items.add(deepCopyAny(i));
            return new Nbt.NbtList(l.elementType(), items);
        }
        if (o instanceof byte[] a) return a.clone();
        if (o instanceof int[] a) return a.clone();
        if (o instanceof long[] a) return a.clone();
        return o;
    }
}
