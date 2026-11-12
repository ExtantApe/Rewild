package com.extantape.rewild.region;

import com.extantape.rewild.TestChunks;
import com.extantape.rewild.keep.KeepPlan;
import com.extantape.rewild.store.ChunkRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The per-block rewild: fresh terrain everywhere except what's kept, which comes through exactly as it was. */
class MergeTest {
    static final int CX = 2, CZ = -1;

    /** A real chunk: stone to y=64, a player's chest at 4,65,4 and a lantern hanging under a ledge. */
    static Nbt.Compound real() throws IOException {
        Nbt.Compound c = TestChunks.chunk(CX, CZ, "minecraft:stone", 64, 7);
        set(c, 4, 65, 4, state("minecraft:chest", Map.of("facing", "north")));
        set(c, 8, 70, 8, state("minecraft:lantern", Map.of("hanging", "true")));
        set(c, 8, 71, 8, state("minecraft:cobblestone", Map.of()));
        set(c, 1, 64, 1, state("minecraft:dirt_path", Map.of()));
        Nbt.Compound chest = new Nbt.Compound();
        chest.put("id", "minecraft:chest");
        chest.put("x", CX * 16 + 4);
        chest.put("y", 65);
        chest.put("z", CZ * 16 + 4);
        chest.put("Items", new Nbt.NbtList(Nbt.COMPOUND, List.of()));
        Nbt.Compound oldSpawner = new Nbt.Compound();
        oldSpawner.put("id", "minecraft:mob_spawner");
        oldSpawner.put("x", CX * 16 + 9);
        oldSpawner.put("y", 20);
        oldSpawner.put("z", CZ * 16 + 9);
        c.put("block_entities", new Nbt.NbtList(Nbt.COMPOUND, List.of(chest, oldSpawner)));
        c.put("Heightmaps", new Nbt.Compound());
        return c;
    }

    static Nbt.Compound fresh() {
        return TestChunks.chunk(CX, CZ, "minecraft:granite", 64, 7);
    }

    static Nbt.Compound state(String name, Map<String, String> props) {
        Nbt.Compound s = new Nbt.Compound();
        s.put("Name", name);
        if (!props.isEmpty()) {
            Nbt.Compound p = new Nbt.Compound();
            props.forEach(p::put);
            s.put("Properties", p);
        }
        return s;
    }

    /** Sets one block in a test chunk, re-encoding its section. */
    static void set(Nbt.Compound chunk, int x, int y, int z, Nbt.Compound st) throws IOException {
        for (Nbt.Compound sec : chunk.getList("sections").compounds()) {
            if (sec.getInt("Y", 99) != y >> 4 || sec.getCompound("block_states") == null) continue;
            Merge.Section s = new Merge.Section(sec);
            Object[] blocks = new Object[4096];
            for (int i = 0; i < 4096; i++) blocks[i] = s.at(i);
            blocks[((y & 15) << 8) | (z << 4) | x] = st;
            sec.put("block_states", Merge.encode(blocks));
        }
    }

    static String name(Nbt.Compound chunk, int x, int y, int z) throws IOException {
        return Merge.blocks(chunk).at(x, y, z).name();
    }

    @Test
    void encodeRoundTripsEveryPaletteSize() throws IOException {
        for (int size : new int[]{1, 2, 16, 17, 33, 300}) {
            Object[] blocks = new Object[4096];
            for (int i = 0; i < 4096; i++) blocks[i] = state("minecraft:b" + (i % size), Map.of());
            Nbt.Compound sec = new Nbt.Compound();
            sec.put("block_states", Merge.encode(blocks));
            Merge.Section back = new Merge.Section(sec);
            for (int i = 0; i < 4096; i++) assertEquals("minecraft:b" + (i % size), ((Nbt.Compound) back.at(i)).getString("Name"), "size " + size);
        }
    }

    /** Converts a test chunk's palettes to the 26.x shape: {"": name} for plain states, {id, properties} otherwise. */
    static Nbt.Compound as26x(Nbt.Compound chunk) {
        for (Nbt.Compound sec : chunk.getList("sections").compounds()) {
            Nbt.Compound st = sec.getCompound("block_states");
            if (st == null) continue;
            List<Object> pal = new ArrayList<>();
            for (Nbt.Compound e : st.getList("palette").compounds()) {
                Nbt.Compound n = new Nbt.Compound();
                if (e.getCompound("Properties") == null) {
                    n.put("", e.getString("Name"));
                } else {
                    n.put("id", e.getString("Name"));
                    n.put("properties", e.getCompound("Properties"));
                }
                pal.add(n);
            }
            st.put("palette", new Nbt.NbtList(Nbt.COMPOUND, pal));
        }
        return chunk;
    }

    @Test
    void the26xPaletteShapesKeepEveryStateApart() throws IOException {
        // 26.3 saves a state with no properties as {"": name}. Reading those as nameless once made every plain block in
        // a merged section the same block; this guards against that.
        Nbt.Compound real = as26x(real()), fresh = as26x(fresh());
        ChunkRecord rec = new ChunkRecord(CX, CZ);
        rec.placed.put(ChunkRecord.pos(4, 65, 4), "CHEST");
        rec.placed.put(ChunkRecord.pos(8, 70, 8), "LANTERN");
        KeepMask mask = KeepPlan.build(real, rec, List.of(), List.of("DIRT_PATH"), Integer.MIN_VALUE);
        assertTrue(mask.kept(8, 71, 8), "a hanging lantern's support, read from 26.x properties");
        assertTrue(mask.kept(1, 64, 1), "a dirt path, named in the 26.x shape");
        Nbt.Compound out = Merge.chunk(real, fresh, mask);
        assertEquals("minecraft:chest", name(out, 4, 65, 4));
        assertEquals("north", Merge.blocks(out).at(4, 65, 4).prop("facing"));
        assertEquals("minecraft:stone", name(out, 4, 64, 4));
        assertEquals("minecraft:air", name(out, 4, 66, 4), "air above the chest is fresh air, not the chest's neighbour");
        assertEquals("minecraft:granite", name(out, 5, 64, 5));
        assertEquals("minecraft:air", name(out, 5, 65, 5));
        assertEquals("minecraft:cobblestone", name(out, 8, 71, 8));
    }

    @Test
    void aSectionMixingBareNamesAndPropertiesSavesAndReadsBack() throws IOException {
        // 26.3 saves an all-plain palette as a list of strings. Merging one with a palette that has properties once
        // wrote a list mixing strings and compounds, which nothing could read back.
        Object[] blocks = new Object[4096];
        Nbt.Compound chest = new Nbt.Compound();
        chest.put("id", "minecraft:chest");
        Nbt.Compound p = new Nbt.Compound();
        p.put("facing", "north");
        chest.put("properties", p);
        for (int i = 0; i < 4096; i++) blocks[i] = i == 7 ? chest : i % 2 == 0 ? "minecraft:air" : "minecraft:stone";
        Nbt.Compound sec = new Nbt.Compound();
        sec.put("Y", (byte) 4);
        sec.put("block_states", Merge.encode(blocks));
        Nbt.Compound root = new Nbt.Compound();
        root.put("sections", new Nbt.NbtList(Nbt.COMPOUND, List.of(sec)));
        Nbt.Compound back = Nbt.read(Nbt.write(root));
        var b = Merge.blocks(back);
        assertEquals("minecraft:air", b.at(0, 64, 0).name());
        assertEquals("minecraft:stone", b.at(1, 64, 0).name());
        assertEquals("minecraft:chest", b.at(7, 64, 0).name());
        assertEquals("north", b.at(7, 64, 0).prop("facing"));
        Object[] plain = new Object[4096];
        for (int i = 0; i < 4096; i++) plain[i] = i % 2 == 0 ? "minecraft:air" : "minecraft:stone";
        assertEquals(Nbt.STRING, Merge.encode(plain).getList("palette").elementType(), "an all-plain palette stays a list of names");
    }

    @Test
    void theWriterRefusesAMixedList() {
        Nbt.Compound root = new Nbt.Compound();
        root.put("l", new Nbt.NbtList(Nbt.COMPOUND, List.of(new Nbt.Compound(), "x")));
        assertThrows(IOException.class, () -> Nbt.write(root));
    }

    @Test
    void keptBlocksAndTheirSupportsComeThroughEverythingElseIsFresh() throws IOException {
        Nbt.Compound real = real();
        ChunkRecord rec = new ChunkRecord(CX, CZ);
        rec.placed.put(ChunkRecord.pos(4, 65, 4), "CHEST");
        rec.placed.put(ChunkRecord.pos(8, 70, 8), "LANTERN");
        KeepMask mask = KeepPlan.build(real, rec, List.of(), List.of("DIRT_PATH"), Integer.MIN_VALUE);
        Nbt.Compound out = Merge.chunk(real, fresh(), mask);

        assertEquals("minecraft:chest", name(out, 4, 65, 4));
        assertEquals("minecraft:stone", name(out, 4, 64, 4), "the block under the chest holds it up");
        assertEquals("minecraft:lantern", name(out, 8, 70, 8));
        assertEquals("minecraft:cobblestone", name(out, 8, 71, 8), "a hanging lantern keeps the block above it");
        assertEquals("minecraft:dirt_path", name(out, 1, 64, 1), "dirt paths are in keep-blocks by default");
        assertEquals("minecraft:granite", name(out, 0, 30, 0), "everything else is fresh");
        assertEquals("minecraft:granite", name(out, 9, 20, 9));

        List<String> ids = new ArrayList<>();
        for (Nbt.Compound be : out.getList("block_entities").compounds()) ids.add(be.getString("id") + "@" + be.getInt("y", 0));
        assertEquals(List.of("minecraft:chest@65"), ids, "the chest's contents stay; the mined-out spawner's data goes with its block");
        assertNull(out.get("Heightmaps"), "the server works heightmaps out again");
        assertEquals((byte) 0, out.get("isLightOn"));
        assertEquals(real.get("DataVersion"), out.get("DataVersion"));
    }

    @Test
    void nothingKeptMeansFreshTerrainThroughout() throws IOException {
        Nbt.Compound real = real();
        KeepMask mask = KeepPlan.build(real, new ChunkRecord(CX, CZ), List.of(), List.of(), Integer.MIN_VALUE);
        assertTrue(mask.isEmpty());
        Nbt.Compound out = Merge.chunk(real, fresh(), mask);
        assertEquals("minecraft:granite", name(out, 4, 64, 4));
        assertEquals("minecraft:air", name(out, 4, 65, 4));
        assertEquals(0, out.getList("block_entities").items().size());
    }

    @Test
    void anAreaKeepsEveryBlockInsideItEvenAir() throws IOException {
        Nbt.Compound real = real();
        // A gate frame: a box straddling the chunk border. Only the part in this chunk applies.
        KeepPlan.Area gate = new KeepPlan.Area("w", CX * 16 - 3, 60, CZ * 16 + 2, CX * 16 + 1, 66, CZ * 16 + 3, "a gate");
        KeepMask mask = KeepPlan.build(real, null, List.of(gate), List.of(), Integer.MIN_VALUE);
        assertTrue(mask.kept(0, 60, 2) && mask.kept(1, 66, 3));
        assertFalse(mask.kept(2, 62, 2));
        Nbt.Compound out = Merge.chunk(real, fresh(), mask);
        assertEquals("minecraft:stone", name(out, 1, 62, 3));
        assertEquals("minecraft:air", name(out, 1, 66, 3));
        assertEquals("minecraft:granite", name(out, 2, 62, 3));
    }

    @Test
    void belowGroundRepairKeepsEverythingAboveTheCutAndPlacedBlocksBelowIt() throws IOException {
        Nbt.Compound real = real();
        ChunkRecord rec = new ChunkRecord(CX, CZ);
        set(real, 3, 10, 3, state("minecraft:torch", Map.of()));
        rec.placed.put(ChunkRecord.pos(3, 10, 3), "TORCH");
        KeepMask mask = KeepPlan.build(real, rec, List.of(), List.of(), 32);
        Nbt.Compound out = Merge.chunk(real, fresh(), mask);
        assertEquals("minecraft:stone", name(out, 0, 40, 0));
        assertEquals("minecraft:granite", name(out, 0, 20, 0));
        assertEquals("minecraft:torch", name(out, 3, 10, 3), "a player's torch in the mine stays");
        assertEquals("minecraft:stone", name(out, 3, 9, 3), "with the block it stands on");
        assertEquals("minecraft:granite", name(out, 3, 8, 3));
    }

    @Test
    void differentTerrainIsRefused() throws IOException {
        Nbt.Compound other = TestChunks.chunk(CX, CZ, "minecraft:granite", 64, 999_999);
        KeepMask mask = new KeepMask(-64, 384);
        IOException e = assertThrows(IOException.class, () -> Merge.chunk(real(), other, mask));
        assertTrue(e.getMessage().contains("bedrock"), e.getMessage());
        Nbt.Compound wrongChunk = TestChunks.chunk(CX + 1, CZ, "minecraft:granite", 64, 7);
        assertThrows(IOException.class, () -> Merge.chunk(real(), wrongChunk, mask));
    }

    @Test
    void entitiesOnKeptBlocksStayAndFreshOnesArrive() {
        Nbt.Compound frame = TestChunks.entity("minecraft:item_frame", 65.5);
        frame.put("Pos", new Nbt.NbtList(Nbt.DOUBLE, List.of(CX * 16 + 4.5, 65.5, CZ * 16 + 4.5)));
        Nbt.Compound zombie = TestChunks.entity("minecraft:zombie", 30);
        Nbt.Compound villager = TestChunks.entity("minecraft:villager", 70);
        Nbt.Compound real = TestChunks.entities(CX, CZ, frame, zombie);
        Nbt.Compound fresh = TestChunks.entities(CX, CZ, villager);
        KeepMask mask = new KeepMask(-64, 384);
        mask.keep(4, 65, 4);
        List<String> ids = new ArrayList<>();
        for (Nbt.Compound e : Merge.entities(real, fresh, mask, CX, CZ).getList("Entities").compounds()) ids.add(e.getString("id"));
        assertEquals(List.of("minecraft:item_frame", "minecraft:villager"), ids);
    }

    @Test
    void pointsOfInterestOnlySurviveInSectionsKeptWhole() {
        Nbt.Compound poi = new Nbt.Compound();
        Nbt.Compound sections = new Nbt.Compound();
        sections.put("1", new Nbt.Compound());
        sections.put("4", new Nbt.Compound());
        poi.put("Sections", sections);
        KeepMask mask = new KeepMask(-64, 384);
        mask.keepFrom(64);
        mask.keep(0, 20, 0);
        Nbt.Compound out = Merge.poi(poi, mask);
        assertEquals(List.of("4"), new ArrayList<>(out.getCompound("Sections").keySet()));
    }
}
