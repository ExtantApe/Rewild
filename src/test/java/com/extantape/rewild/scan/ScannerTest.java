package com.extantape.rewild.scan;

import com.extantape.rewild.config.RegenConfig;
import com.extantape.rewild.region.ChunkFacts;
import com.extantape.rewild.store.ChunkRecord;
import com.extantape.rewild.store.WorldStore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScannerTest {
    static final long NOW = 1_800_000_000_000L;
    Map<Long, ChunkRecord> records;
    Map<Long, ChunkFacts> facts;
    Scanner.Context ctx;

    static RegenConfig defaults() throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(ScannerTest.class.getResourceAsStream("/config.yml")));
        return new RegenConfig(y);
    }

    @BeforeEach
    void setUp() throws Exception {
        RegenConfig cfg = defaults();
        assertTrue(cfg.healthy(), String.valueOf(cfg.errors()));
        records = new HashMap<>();
        facts = new HashMap<>();
        ctx = new Scanner.Context();
        ctx.settings = cfg.world("anything");
        ctx.now = NOW;
        ctx.epoch = 1;
        ctx.spawnX = 1000;
        ctx.spawnZ = 1000;
        // A 20x20 patch of known, untouched chunks, all present on disk with ground at y=64.
        for (int x = -10; x < 10; x++) for (int z = -10; z < 10; z++) known(x, z);
    }

    ChunkRecord known(int x, int z) {
        ChunkRecord r = new ChunkRecord(x, z);
        r.known = true;
        r.epoch = 1;
        r.bornAt = NOW - 90 * Scanner.DAY;
        r.lastVisit = NOW - 60 * Scanner.DAY;
        records.put(r.key(), r);
        ChunkFacts f = new ChunkFacts();
        f.present = true;
        f.floorMinY = 64;
        facts.put(r.key(), f);
        return r;
    }

    ChunkRecord minedOut(int x, int z) {
        ChunkRecord r = records.get(ChunkRecord.key(x, z));
        r.removedBySection.put(3, 50);
        r.lastChange = NOW - 40 * Scanner.DAY;
        r.changeBySection.put(3, r.lastChange);
        return r;
    }

    Scanner.Verdict verdict(int x, int z) {
        return new Scanner(ctx, records, facts).evaluate(x, z);
    }

    void place(int x, int z, int lx, int y, int lz, String m) {
        records.get(ChunkRecord.key(x, z)).placed.put(ChunkRecord.pos(lx, y, lz), m);
    }

    @Test
    void minedOutUntouchedChunkIsQueued() {
        minedOut(0, 0);
        assertEquals(Scanner.Kind.QUEUED, verdict(0, 0).kind());
    }

    @Test
    void undamagedChunkIsNotQueued() {
        records.get(ChunkRecord.key(0, 0)).removedBySection.put(3, 39);
        assertEquals(Scanner.Kind.UNDAMAGED, verdict(0, 0).kind());
    }

    @Test
    void explosionCraterCountsOnItsOwn() {
        ChunkRecord r = records.get(ChunkRecord.key(0, 0));
        r.explosionBlocks = 30;
        r.lastChange = NOW - 40 * Scanner.DAY;
        assertEquals(Scanner.Kind.QUEUED, verdict(0, 0).kind());
    }

    @Test
    void recentChangeOrVisitOrResetWaits() {
        ChunkRecord r = minedOut(0, 0);
        r.lastChange = NOW - 29 * Scanner.DAY;
        assertEquals(Scanner.Kind.WAITING, verdict(0, 0).kind());
        r.lastChange = NOW - 31 * Scanner.DAY;
        r.lastVisit = NOW - 6 * Scanner.DAY;
        assertEquals(Scanner.Kind.WAITING, verdict(0, 0).kind());
        r.lastVisit = NOW - 8 * Scanner.DAY;
        r.lastReset = NOW - 10 * Scanner.DAY;
        Scanner.Verdict v = verdict(0, 0);
        assertEquals(Scanner.Kind.WAITING, v.kind());
        assertTrue(v.reasons().get(0).contains("reset"));
        r.lastReset = NOW - 31 * Scanner.DAY;
        assertEquals(Scanner.Kind.QUEUED, verdict(0, 0).kind());
    }

    @Test
    void untrackedChunkIsUnknownAndNeverQueued() {
        ChunkRecord r = minedOut(0, 0);
        r.known = false;
        assertEquals(Scanner.Kind.UNKNOWN, verdict(0, 0).kind());
        assertEquals(Scanner.Kind.UNKNOWN, verdict(50, 50).kind());
        assertFalse(Scanner.candidates(records, ctx).contains(r.key()));
    }

    @Test
    void unknownChunkStillNamesWhatKeepsIt() {
        // Found on the 26.2 clone: the station's own chunk predates tracking, and /rewild why only said "unknown".
        ChunkRecord r = minedOut(0, 0);
        r.known = false;
        ctx.external.put(ChunkRecord.key(0, 0), List.of("the station (Waystation)"));
        Scanner.Verdict v = verdict(0, 0);
        assertEquals(Scanner.Kind.UNKNOWN, v.kind());
        assertTrue(String.join(" ", v.reasons()).contains("also kept: the station (Waystation)"), v.reasons().toString());
    }

    @Test
    void chunkFromAnOlderGeneratorIsUnknown() {
        minedOut(0, 0).epoch = 0;
        assertEquals(Scanner.Kind.UNKNOWN, verdict(0, 0).kind());
    }

    @Test
    void neighbourGeneratedBeforeTrackingStopsTheReset() {
        minedOut(-10, 0);
        // Chunk -11,0 exists on disk but has no record: generated before tracking began.
        ChunkFacts f = new ChunkFacts();
        f.present = true;
        facts.put(ChunkRecord.key(-11, 0), f);
        Scanner.Verdict v = verdict(-10, 0);
        assertEquals(Scanner.Kind.KEPT, v.kind());
        assertTrue(v.reasons().get(0).contains("seam"), v.reasons().toString());
    }

    @Test
    void playerBlocksNoLongerKeepTheChunkButStayInPlace() {
        // 0.3.0: a chest, a bed or a small cabin doesn't hold the whole chunk back. It stays exactly where it is, and
        // the mined-out land around it rewilds.
        minedOut(0, 0);
        place(0, 0, 4, 70, 4, "CHEST");
        for (int i = 0; i < 6; i++) place(0, 0, 5 + i, 70, 5, "OAK_PLANKS");
        Scanner.Verdict v = verdict(0, 0);
        assertEquals(Scanner.Kind.QUEUED, v.kind(), v.reasons().toString());
        assertTrue(String.join(" ", v.reasons()).contains("stays in place: 7 player-placed blocks"), v.reasons().toString());
    }

    @Test
    void keptAreasAreNamedInWhy() {
        minedOut(0, 0);
        ctx.areas.add(new com.extantape.rewild.keep.KeepPlan.Area("w", 3, 60, 3, 5, 70, 5, "a gate"));
        ctx.areas.add(new com.extantape.rewild.keep.KeepPlan.Area("w", 100, 60, 100, 101, 70, 101, "far away"));
        Scanner.Verdict v = verdict(0, 0);
        assertEquals(Scanner.Kind.QUEUED, v.kind());
        assertTrue(String.join(" ", v.reasons()).contains("1 kept area"), v.reasons().toString());
    }

    @Test
    void chunkBornBeforeEveryBlockWasRecordedIsUnknown() {
        // A chunk tracked by 0.2.x only had some placed blocks recorded, so a build there could be lost.
        minedOut(0, 0);
        ctx.everyBlockSince = NOW - 30 * Scanner.DAY;
        Scanner.Verdict v = verdict(0, 0);
        assertEquals(Scanner.Kind.UNKNOWN, v.kind());
        assertTrue(v.reasons().get(0).contains("every player-placed block"), v.reasons().toString());
        records.get(ChunkRecord.key(0, 0)).bornAt = NOW - 20 * Scanner.DAY;
        assertEquals(Scanner.Kind.QUEUED, verdict(0, 0).kind());
    }

    void setUpQuietly() {
        try {
            setUp();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void villagePathsAreNeverRecordedSoTheyDontCount() {
        // Village paths are generated, not placed, so the tracker never records them. Nothing to keep here.
        minedOut(0, 0);
        assertEquals(Scanner.Kind.QUEUED, verdict(0, 0).kind());
    }

    @Test
    void claimSpawnBedPlayerProtectedAndPetKeep() {
        minedOut(0, 0);
        ctx.claims.add(new int[]{2, 0, 3, 1});
        assertTrue(verdict(0, 0).kind() == Scanner.Kind.QUEUED);
        ctx.claims.clear();
        ctx.claims.add(new int[]{1, 0, 3, 1});
        assertTrue(verdict(0, 0).reasons().get(0).contains("claim"));

        setUpQuietly();
        minedOut(0, 0);
        ctx.spawnX = 5;
        ctx.spawnZ = 5;
        assertTrue(verdict(0, 0).reasons().get(0).contains("spawn"));

        setUpQuietly();
        minedOut(0, 0);
        ctx.respawnChunks.add(ChunkRecord.key(1, 1));
        assertTrue(verdict(0, 0).reasons().get(0).contains("bed"));

        setUpQuietly();
        minedOut(0, 0);
        ctx.playerChunks.add(ChunkRecord.key(2, 0));
        assertTrue(verdict(0, 0).reasons().get(0).contains("player"));

        setUpQuietly();
        minedOut(0, 0);
        ctx.protectedAreas.add(new WorldStore.ProtectedArea(0, 0, 0, 0, "the meadow"));
        assertTrue(verdict(0, 0).reasons().get(0).contains("the meadow"));

        setUpQuietly();
        minedOut(0, 0);
        facts.get(ChunkRecord.key(0, 0)).keptEntities.add(new ChunkFacts.KeptEntity("named cat", 70));
        assertTrue(verdict(0, 0).reasons().contains("named cat"));

        setUpQuietly();
        minedOut(0, 0);
        ctx.external.put(ChunkRecord.key(0, 0), List.of("an active grave (LanternGraves)"));
        assertTrue(verdict(0, 0).reasons().get(0).contains("grave"));
    }

    @Test
    void bufferKeepsNeighboursOfKeptChunks() {
        minedOut(0, 0);
        // A chunk kept whole (here an admin's /rewild protect) keeps the chunks around it too.
        ctx.protectedAreas.add(new com.extantape.rewild.store.WorldStore.ProtectedArea(1, 1, 1, 1, "a farm"));
        Scanner.Verdict v = verdict(0, 0);
        assertEquals(Scanner.Kind.KEPT, v.kind());
        assertTrue(v.reasons().get(0).startsWith("next to a kept chunk"));
        minedOut(3, 3);
        assertEquals(Scanner.Kind.QUEUED, verdict(3, 3).kind());
    }

    @Test
    void strongholdAndEndIslandKeep() {
        minedOut(0, 0);
        facts.get(ChunkRecord.key(0, 0)).structures.add("minecraft:stronghold");
        assertTrue(verdict(0, 0).reasons().get(0).contains("stronghold"));
        setUpQuietly();
        ctx.environment = "THE_END";
        minedOut(0, 0);
        assertTrue(verdict(0, 0).reasons().get(0).contains("main island"));
    }

    @Test
    void unreadableOrMissingSavedDataKeeps() {
        minedOut(0, 0);
        facts.get(ChunkRecord.key(0, 0)).error = "IOException: boom";
        assertEquals(Scanner.Kind.KEPT, verdict(0, 0).kind());
        setUpQuietly();
        minedOut(0, 0);
        facts.remove(ChunkRecord.key(0, 0));
        assertEquals(Scanner.Kind.KEPT, verdict(0, 0).kind());
    }

    @Test
    void capTakesWholeGroupsFirst() {
        ctx.maxChunks = 3;
        ctx.minGroupSize = 3;
        minedOut(-5, -5); // a lone chunk
        minedOut(0, 0);
        minedOut(1, 0);
        minedOut(2, 0); // a group of three
        Map<Long, Scanner.Verdict> all = new Scanner(ctx, records, facts).evaluateAll();
        long queued = all.values().stream().filter(Scanner.Verdict::queued).count();
        assertEquals(3, queued);
        assertEquals(Scanner.Kind.WAITING, all.get(ChunkRecord.key(-5, -5)).kind());
        assertTrue(all.get(ChunkRecord.key(-5, -5)).reasons().get(0).contains("cap"));
    }

    @Test
    void minedOutGroundUnderAKeptHouseResetsBelowGroundOnly() {
        ChunkRecord r = minedOut(0, 0);
        r.removedBySection.clear();
        r.removedBySection.put(1, 60); // y 16..31
        r.changeBySection.clear();
        r.changeBySection.put(1, NOW - 40 * Scanner.DAY);
        r.changeBySection.put(4, NOW - Scanner.DAY); // the house is lived in
        r.lastChange = NOW - Scanner.DAY;
        r.visitBySection.put(4, NOW - Scanner.DAY);
        r.lastVisit = NOW - Scanner.DAY;
        place(0, 0, 4, 70, 4, "CHEST");
        Scanner.Verdict v = verdict(0, 0);
        assertEquals(Scanner.Kind.UNDERGROUND, v.kind(), v.reasons().toString());
        assertEquals(48, v.cutY()); // floor 64, margin 8 -> 56, rounded down to 48
    }

    @Test
    void belowGroundRefusesWhenSomethingKeptIsUnderTheCut() {
        ChunkRecord r = minedOut(0, 0);
        r.removedBySection.clear();
        r.removedBySection.put(1, 60);
        // A named pet down in the mine keeps the whole chunk, so no cut can go above it.
        facts.get(ChunkRecord.key(0, 0)).keptEntities.add(new ChunkFacts.KeptEntity("a named wolf", 20));
        Scanner.Verdict v = verdict(0, 0);
        assertEquals(Scanner.Kind.KEPT, v.kind());
        assertTrue(String.join(" ", v.reasons()).contains("under the cut"), v.reasons().toString());
    }

    @Test
    void belowGroundRespectsClaimsAndRecentVisitsDownThere() {
        ChunkRecord r = minedOut(0, 0);
        r.removedBySection.clear();
        r.removedBySection.put(1, 60);
        place(0, 0, 4, 70, 4, "CHEST");
        r.visitBySection.put(1, NOW - Scanner.DAY);
        r.lastVisit = NOW - Scanner.DAY;
        assertEquals(Scanner.Kind.WAITING, verdict(0, 0).kind(), "visited down there, so neither a rewild nor a repair");
        r.visitBySection.clear();
        ctx.claims.add(new int[]{0, 0, 0, 0});
        assertEquals(Scanner.Kind.KEPT, verdict(0, 0).kind());
    }

    @Test
    void belowGroundIsOverworldOnly() {
        ctx.environment = "NETHER";
        ChunkRecord r = minedOut(0, 0);
        r.removedBySection.clear();
        r.removedBySection.put(1, 60);
        r.lastChange = NOW - Scanner.DAY; // the surface is lived in
        place(0, 0, 4, 70, 4, "CHEST");
        assertEquals(Scanner.Kind.WAITING, verdict(0, 0).kind());
    }
}
