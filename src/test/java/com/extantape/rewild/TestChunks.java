package com.extantape.rewild;

import com.extantape.rewild.region.Nbt;
import com.extantape.rewild.region.RegionFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Made-up chunk data shaped like the game's, for tests. */
public final class TestChunks {
    private TestChunks() {}

    public static final int DATA_VERSION = 5023;

    /**
     * A full overworld chunk: bedrock floor in a pattern from {@code bedrockSeed}, {@code fill} up to {@code surfaceY},
     * air above.
     */
    public static Nbt.Compound chunk(int cx, int cz, String fill, int surfaceY, long bedrockSeed) {
        Nbt.Compound c = new Nbt.Compound();
        c.put("DataVersion", DATA_VERSION);
        c.put("xPos", cx);
        c.put("zPos", cz);
        c.put("yPos", -4);
        c.put("Status", "minecraft:full");
        List<Object> sections = new ArrayList<>();
        Nbt.Compound light = new Nbt.Compound();
        light.put("Y", (byte) -5);
        sections.add(light);
        for (int sy = -4; sy < 20; sy++) {
            Nbt.Compound s = new Nbt.Compound();
            s.put("Y", (byte) sy);
            s.put("block_states", states(sy, fill, surfaceY, bedrockSeed));
            sections.add(s);
        }
        c.put("sections", new Nbt.NbtList(Nbt.COMPOUND, sections));
        c.put("block_entities", new Nbt.NbtList(Nbt.COMPOUND, List.of()));
        c.put("block_ticks", new Nbt.NbtList(Nbt.COMPOUND, List.of()));
        c.put("fluid_ticks", new Nbt.NbtList(Nbt.COMPOUND, List.of()));
        List<Object> post = new ArrayList<>();
        for (int i = 0; i < 24; i++) post.add(new Nbt.NbtList(Nbt.SHORT, List.of()));
        c.put("PostProcessing", new Nbt.NbtList(Nbt.LIST, post));
        c.put("starlight.light_version", 10);
        c.put("isLightOn", (byte) 1);
        return c;
    }

    static Nbt.Compound states(int sy, String fill, int surfaceY, long bedrockSeed) {
        Nbt.Compound st = new Nbt.Compound();
        int bottom = sy * 16, top = bottom + 15;
        if (top <= surfaceY && sy != -4) {
            st.put("palette", palette(fill));
            return st;
        }
        if (bottom > surfaceY) {
            st.put("palette", palette("minecraft:air"));
            return st;
        }
        // Mixed section: index 0 air, 1 fill, 2 bedrock.
        st.put("palette", palette("minecraft:air", fill, "minecraft:bedrock"));
        int bits = 4, perLong = 16;
        long[] data = new long[4096 / perLong];
        Random r = new Random(bedrockSeed);
        for (int i = 0; i < 4096; i++) {
            int y = bottom + i / 256;
            int v = y > surfaceY ? 0 : 1;
            if (sy == -4 && (y == -64 || (y <= -60 && r.nextInt(5) > y + 64 - 1))) v = 2;
            data[i / perLong] |= ((long) v) << ((i % perLong) * bits);
        }
        st.put("data", data);
        return st;
    }

    static Nbt.NbtList palette(String... names) {
        List<Object> out = new ArrayList<>();
        for (String n : names) {
            Nbt.Compound e = new Nbt.Compound();
            e.put("Name", n);
            out.add(e);
        }
        return new Nbt.NbtList(Nbt.COMPOUND, out);
    }

    public static Nbt.Compound entities(int cx, int cz, Nbt.Compound... list) {
        Nbt.Compound c = new Nbt.Compound();
        c.put("DataVersion", DATA_VERSION);
        c.put("Position", new int[]{cx, cz});
        c.put("Entities", new Nbt.NbtList(Nbt.COMPOUND, List.of((Object[]) list)));
        return c;
    }

    public static Nbt.Compound entity(String id, double y) {
        Nbt.Compound e = new Nbt.Compound();
        e.put("id", id);
        e.put("Pos", new Nbt.NbtList(Nbt.DOUBLE, List.of(0.5, y, 0.5)));
        return e;
    }

    /** Writes chunks into region files under a dimension folder, as the game would. */
    public static void writeChunk(Path dimension, String kind, int cx, int cz, Nbt.Compound nbt) throws IOException {
        Path p = dimension.resolve(kind).resolve(RegionFile.fileName(cx, cz));
        java.nio.file.Files.createDirectories(p.getParent());
        RegionFile r = RegionFile.load(p);
        r.put(RegionFile.index(cx, cz), RegionFile.compress(Nbt.write(nbt), 1234));
        r.writeAtomically();
    }
}
