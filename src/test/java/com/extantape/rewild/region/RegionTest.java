package com.extantape.rewild.region;

import com.extantape.rewild.TestChunks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RegionTest {
    @TempDir
    Path dir;

    @Test
    void nbtRoundTripsByteForByte() throws Exception {
        Nbt.Compound c = TestChunks.chunk(3, -2, "minecraft:stone", 70, 1);
        byte[] a = Nbt.write(c);
        byte[] b = Nbt.write(Nbt.read(a));
        assertArrayEquals(a, b);
    }

    @Test
    void chunksSurviveWriteAndRemoveLeavesNeighbours() throws Exception {
        TestChunks.writeChunk(dir, "region", 0, 0, TestChunks.chunk(0, 0, "minecraft:stone", 70, 1));
        TestChunks.writeChunk(dir, "region", 1, 0, TestChunks.chunk(1, 0, "minecraft:dirt", 60, 2));
        Path p = dir.resolve("region/r.0.0.mca");
        RegionFile r = RegionFile.load(p);
        assertEquals(2, r.count());
        assertEquals(1, r.readNbt(RegionFile.index(1, 0)).getInt("xPos", -1));
        byte[] before = r.get(RegionFile.index(1, 0)).data();
        r.remove(RegionFile.index(0, 0));
        r.writeAtomically();
        RegionFile again = RegionFile.load(p);
        assertEquals(1, again.count());
        assertNull(again.get(0));
        assertArrayEquals(before, again.get(RegionFile.index(1, 0)).data());
        assertFalse(Files.exists(p.resolveSibling("r.0.0.mca.wr-tmp")));
    }

    @Test
    void negativeCoordinatesMapToTheRightFile() {
        assertEquals("r.-1.-1.mca", RegionFile.fileName(-1, -1));
        assertEquals("r.-1.0.mca", RegionFile.fileName(-32, 31));
        assertEquals(31 + 31 * 32, RegionFile.index(-1, -1));
    }

    @Test
    void oversizedChunksUseAnExternalFile() throws Exception {
        byte[] noise = new byte[1_200_000];
        new Random(7).nextBytes(noise);
        Nbt.Compound c = new Nbt.Compound();
        c.put("blob", noise);
        RegionFile.RawChunk raw = RegionFile.compress(Nbt.write(c), 5);
        assertTrue(raw.isExternal());
        Path p = dir.resolve("region/r.0.0.mca");
        Files.createDirectories(p.getParent());
        RegionFile r = RegionFile.empty(p);
        r.put(RegionFile.index(2, 3), raw);
        r.writeAtomically();
        assertTrue(Files.exists(dir.resolve("region/c.2.3.mcc")));
        RegionFile again = RegionFile.load(p);
        assertArrayEquals(noise, (byte[]) again.readNbt(RegionFile.index(2, 3)).get("blob"));
    }

    @Test
    void damagedHeaderIsAnErrorNotASkip() throws Exception {
        Path p = dir.resolve("r.0.0.mca");
        byte[] bad = new byte[8192];
        bad[0] = 0x7f; // points far outside the file
        bad[3] = 1;
        Files.write(p, bad);
        assertThrows(java.io.IOException.class, () -> RegionFile.load(p));
    }

    @Test
    void factsFindStructuresKeptEntitiesAndFloor() throws Exception {
        Nbt.Compound c = TestChunks.chunk(0, 0, "minecraft:stone", 70, 1);
        Nbt.Compound refs = new Nbt.Compound();
        refs.put("minecraft:stronghold", new long[]{1});
        refs.put("minecraft:mineshaft", new long[0]);
        Nbt.Compound structures = new Nbt.Compound();
        structures.put("References", refs);
        structures.put("starts", new Nbt.Compound());
        c.put("structures", structures);
        TestChunks.writeChunk(dir, "region", 0, 0, c);
        Nbt.Compound wolf = TestChunks.entity("minecraft:wolf", 71);
        wolf.put("Owner", new int[]{1, 2, 3, 4});
        TestChunks.writeChunk(dir, "entities", 0, 0, TestChunks.entities(0, 0, wolf, TestChunks.entity("minecraft:zombie", 20)));
        ChunkFacts f = new ChunkFacts.Reader(dir).read(0, 0);
        assertTrue(f.ok(), f.error);
        assertTrue(f.present);
        assertEquals(java.util.Set.of("minecraft:stronghold"), f.structures);
        assertEquals(70, f.floorMinY);
        assertEquals(1, f.keptEntities.size());
        assertEquals("tamed wolf", f.keptEntities.get(0).reason());
        assertFalse(new ChunkFacts.Reader(dir).read(5, 5).present);
    }

    @Test
    void readsEveryPaletteEncoding() {
        // 1.21.11: {Name, Properties}. 26.x: bare strings, {"": name} in mixed lists, and {id, properties}.
        Nbt.Compound old = new Nbt.Compound();
        old.put("Name", "minecraft:oak_log");
        Nbt.Compound wrapped = new Nbt.Compound();
        wrapped.put("", "minecraft:stone");
        Nbt.Compound modern = new Nbt.Compound();
        modern.put("id", "minecraft:sculk_vein");
        modern.put("properties", new Nbt.Compound());
        Nbt.Compound states = new Nbt.Compound();
        states.put("palette", new Nbt.NbtList(Nbt.COMPOUND, java.util.List.of(old, wrapped, modern)));
        assertArrayEquals(new String[]{"minecraft:oak_log", "minecraft:stone", "minecraft:sculk_vein"}, ChunkFacts.palette(states));
        Nbt.Compound strings = new Nbt.Compound();
        strings.put("palette", new Nbt.NbtList(Nbt.STRING, java.util.List.of("minecraft:air", "minecraft:dirt")));
        assertArrayEquals(new String[]{"minecraft:air", "minecraft:dirt"}, ChunkFacts.palette(strings));
    }

    @Test
    void savedEntityRules() {
        Nbt.Compound v = TestChunks.entity("minecraft:villager", 64);
        assertNull(ChunkFacts.keepReason(v));
        v.put("Xp", 3);
        assertNotNull(ChunkFacts.keepReason(v));
        Nbt.Compound named = TestChunks.entity("minecraft:cow", 64);
        named.put("CustomName", "\"Daisy\"");
        assertEquals("named cow", ChunkFacts.keepReason(named));
        Nbt.Compound leashed = TestChunks.entity("minecraft:horse", 64);
        leashed.put("leash", new int[]{0, 0, 0, 0});
        assertNotNull(ChunkFacts.keepReason(leashed));
        assertEquals("allay", ChunkFacts.keepReason(TestChunks.entity("minecraft:allay", 64)));
        assertEquals("item frame", ChunkFacts.keepReason(TestChunks.entity("minecraft:item_frame", 64)));
        assertNull(ChunkFacts.keepReason(TestChunks.entity("minecraft:skeleton", 64)));
    }
}
