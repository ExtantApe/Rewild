package com.extantape.rewild.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class WorldStoreTest {
    @TempDir
    Path dir;

    @Test
    void flushedChangesSurviveAReopenWithoutCompaction() throws Exception {
        WorldStore s = WorldStore.open(dir);
        s.edit(3, -4, r -> {
            r.known = true;
            r.placed.put(ChunkRecord.pos(1, 70, 2), "CHEST");
            r.removedBySection.put(-2, 41);
            r.changeBySection.put(4, 99L);
        });
        s.flush();
        // No close or compaction: as if the process were killed.
        WorldStore again = WorldStore.open(dir);
        ChunkRecord r = again.get(3, -4);
        assertNotNull(r);
        assertTrue(r.known);
        assertEquals("CHEST", r.placed.get(ChunkRecord.pos(1, 70, 2)));
        assertEquals(41, r.removed());
        assertEquals(99L, r.changeBySection.get(4));
    }

    @Test
    void tornLogFrameIsIgnored() throws Exception {
        WorldStore s = WorldStore.open(dir);
        s.edit(1, 1, r -> r.known = true);
        s.flush();
        s.edit(2, 2, r -> r.known = true);
        s.flush();
        s.close();
        Path log = dir.resolve("chunks.0.log");
        byte[] all = Files.readAllBytes(log);
        Files.write(log, java.util.Arrays.copyOf(all, all.length - 5));
        WorldStore again = WorldStore.open(dir);
        assertNotNull(again.get(1, 1));
        assertNull(again.get(2, 2));
    }

    @Test
    void compactionMovesToTheNextGenerationAndOldLogIsNotReplayed() throws Exception {
        WorldStore s = WorldStore.open(dir);
        s.edit(1, 1, r -> r.lastChange = 5);
        s.flush();
        s.edit(1, 1, r -> r.lastChange = 9);
        s.compact();
        assertFalse(Files.exists(dir.resolve("chunks.0.log")));
        assertTrue(Files.exists(dir.resolve("chunks.1.bin")));
        s.edit(1, 1, r -> r.lastChange = 12);
        s.flush();
        assertEquals(12, WorldStore.open(dir).get(1, 1).lastChange);
    }

    @Test
    void crashBetweenSnapshotAndMetaKeepsTheOldGeneration() throws Exception {
        WorldStore s = WorldStore.open(dir);
        s.edit(1, 1, r -> r.lastChange = 5);
        s.flush();
        s.close();
        // A stray next-generation snapshot from an interrupted compaction is ignored.
        Files.write(dir.resolve("chunks.1.bin"), new byte[]{1, 2, 3}, StandardOpenOption.CREATE);
        assertEquals(5, WorldStore.open(dir).get(1, 1).lastChange);
    }

    @Test
    void protectedAreasPersist() throws Exception {
        WorldStore s = WorldStore.open(dir);
        s.addProtected(new WorldStore.ProtectedArea(-1, -1, 1, 1, "the old mill"));
        assertTrue(WorldStore.open(dir).protectedAreas().get(0).contains(0, 1));
        assertEquals(1, s.removeProtectedAt(0, 0));
        assertTrue(WorldStore.open(dir).protectedAreas().isEmpty());
    }
}
