package com.extantape.rewild.heal;

import static org.junit.jupiter.api.Assertions.*;

import com.extantape.rewild.store.ChunkRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealCheckAndJobTest {
    final Map<Long, ChunkRecord> chunks = new HashMap<>();
    final BiFunction<Integer, Integer, ChunkRecord> lookup = (x, z) -> chunks.get(ChunkRecord.key(x, z));

    ChunkRecord known(int cx, int cz) {
        ChunkRecord r = new ChunkRecord(cx, cz);
        r.known = true;
        chunks.put(r.key(), r);
        return r;
    }

    static List<HealPlan.Cell> hole() {
        return List.of(new HealPlan.Cell(5, 63, 5, "minecraft:grass_block"), new HealPlan.Cell(5, 62, 5, "minecraft:dirt"));
    }

    @Test
    void aHoleInKnownNaturalLandHeals() {
        known(0, 0);
        assertNull(HealCheck.why(hole(), 8, lookup));
    }

    @Test
    void unknownLandNeverHealsOnItsOwn() {
        assertNotNull(HealCheck.why(hole(), 8, lookup), "no record at all");
        known(0, 0).known = false;
        assertTrue(HealCheck.why(hole(), 8, lookup).contains("before Rewild was tracking"));
    }

    @Test
    void aBlastThroughPlayerBlocksWaitsForAnAdmin() {
        known(0, 0).placed.put(ChunkRecord.pos(5, 63, 5), "OAK_PLANKS");
        assertTrue(HealCheck.why(hole(), 8, lookup).contains("a player placed"));
    }

    @Test
    void aBuildNearbyStopsIt_evenInTheNextChunk() {
        known(0, 0);
        known(1, 0).placed.put(ChunkRecord.pos(0, 63, 5), "TORCH"); // x 16: 11 blocks away
        assertNull(HealCheck.why(hole(), 8, lookup), "11 blocks is outside an 8-block radius");
        assertTrue(HealCheck.why(hole(), 12, lookup).contains("build is within 12"));
    }

    @Test
    void aFilledContainerNearbyStopsIt() {
        known(0, 0).filledContainers.add(ChunkRecord.pos(8, 62, 5));
        assertTrue(HealCheck.why(hole(), 8, lookup).contains("container"));
    }

    @Test
    void nothingDestroyedIsNotAHole() {
        assertNotNull(HealCheck.why(List.of(), 8, lookup));
    }

    @Test
    void aJobSurvivesARestartExactly(@TempDir Path dir) throws IOException {
        UUID w = UUID.randomUUID();
        HealJob j = new HealJob("h1", w, 100, 200, 2, List.of(new HealPlan.Cell(-3, -60, 7, "minecraft:oak_log[axis=y]"),
            new HealPlan.Cell(1, 2, 3, "minecraft:snow[layers=2]")));
        Path f = dir.resolve(w.toString()).resolve("h1.txt");
        j.save(f);
        HealJob back = HealJob.load(f);
        assertEquals("h1", back.id);
        assertEquals(w, back.world);
        assertEquals(100, back.created);
        assertEquals(200, back.next);
        assertEquals(2, back.done);
        assertEquals(j.cells, back.cells);
        assertFalse(Files.exists(f.resolveSibling("h1.txt.tmp")), "no temporary file left behind");
    }

    @Test
    void aDamagedJobFileIsRefusedNotGuessed(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("bad.txt");
        Files.writeString(f, "rewild-heal 1\nworld " + UUID.randomUUID() + "\ncreated 1\nnext 2\ndone 0\ncell 1 two 3 minecraft:stone\n");
        assertThrows(IOException.class, () -> HealJob.load(f));
        Files.writeString(f, "something else\n");
        assertThrows(IOException.class, () -> HealJob.load(f));
        Files.writeString(f, "rewild-heal 1\ncell 1 2 3 minecraft:stone\n");
        assertThrows(IOException.class, () -> HealJob.load(f), "no header");
    }

    @Test
    void centerAndBoundsCoverTheHole() {
        HealJob j = new HealJob("h", UUID.randomUUID(), 0, 0, 0, List.of(new HealPlan.Cell(0, 60, 0, "minecraft:stone"), new HealPlan.Cell(4, 64, -2, "minecraft:stone")));
        assertArrayEquals(new int[] {2, 62, -1}, j.center());
        assertArrayEquals(new int[] {0, -2, 4, 0}, j.bounds());
    }
}
