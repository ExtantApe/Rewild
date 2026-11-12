package com.extantape.rewild.region;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that freshly generated terrain really is the same chunk of the same world before {@link Merge} uses it, and
 * copies NBT. Below-ground repairs are a {@link Merge} that keeps everything above the cut.
 */
public final class Underground {
    private Underground() {}

    /** Share of bedrock positions that must match the reference before it's trusted as the same terrain. */
    static final double BEDROCK_MATCH = 0.98;

    /** Throws unless the two are the same fully generated chunk, saved by the same game version. */
    static void checkSameChunk(Nbt.Compound real, Nbt.Compound reference) throws IOException {
        int rv = real.getInt("DataVersion", -1), fv = reference.getInt("DataVersion", -2);
        if (rv != fv) throw new IOException("reference data version " + fv + " doesn't match the world's " + rv);
        if (real.getInt("xPos", 0) != reference.getInt("xPos", 1) || real.getInt("zPos", 0) != reference.getInt("zPos", 1)) {
            throw new IOException("reference is a different chunk");
        }
        if (!isFull(real) || !isFull(reference)) throw new IOException("chunk isn't fully generated");
    }

    static Object deepCopyAnyPublic(Object o) {
        return deepCopyAny(o);
    }

    private static boolean isFull(Nbt.Compound c) {
        String s = c.getString("Status");
        return "minecraft:full".equals(s) || "full".equals(s);
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
