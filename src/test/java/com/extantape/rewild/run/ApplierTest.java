package com.extantape.rewild.run;

import com.extantape.rewild.TestChunks;
import com.extantape.rewild.config.RegenConfig;
import com.extantape.rewild.region.Nbt;
import com.extantape.rewild.region.RegionFile;
import com.extantape.rewild.scan.ContextSnapshot;
import com.extantape.rewild.scan.Scanner;
import com.extantape.rewild.store.ChunkRecord;
import com.extantape.rewild.store.Fingerprint;
import com.extantape.rewild.store.WorldStore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The startup step against made-up world files laid out like a 26.x server. */
class ApplierTest {
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String VERSION = "26.3";
    static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path root;
    Path dim, data;

    RegenConfig config(boolean dryRun) throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        y.set("dry-run", dryRun);
        y.set("worlds.testworld.enabled", true);
        return new RegenConfig(y);
    }

    @BeforeEach
    void world() throws Exception {
        dim = root.resolve("world/dimensions/minecraft/overworld");
        data = root.resolve("plugins/Rewild");
        long now = System.currentTimeMillis();
        WorldStore s = WorldStore.open(data.resolve("worlds").resolve(WORLD.toString()));
        for (int x = -2; x < 8; x++) {
            for (int z = -2; z < 8; z++) {
                TestChunks.writeChunk(dim, "region", x, z, TestChunks.chunk(x, z, "minecraft:stone", 64, x * 31L + z));
                int fx = x, fz = z;
                s.edit(x, z, r -> {
                    r.known = true;
                    r.epoch = 1;
                    r.lastVisit = now - 60 * Scanner.DAY;
                    r.lastChange = now - 60 * Scanner.DAY;
                    if (fx >= 2 && fx <= 4 && fz >= 2 && fz <= 4) r.removedBySection.put(3, 80);
                });
            }
        }
        TestChunks.writeChunk(dim, "entities", 3, 3, TestChunks.entities(3, 3, TestChunks.entity("minecraft:zombie", 40)));
        Nbt.Compound poi = new Nbt.Compound();
        poi.put("DataVersion", TestChunks.DATA_VERSION);
        poi.put("Sections", new Nbt.Compound());
        TestChunks.writeChunk(dim, "poi", 3, 3, poi);
        Fingerprint fp = new Fingerprint(VERSION, 42, "NORMAL", "vanilla", "vanilla", "none");
        s.setMeta(Map.of("dimension", dim.toString(), "serverRoot", root.toString(), "environment", "NORMAL", "minY", "-64",
                "epoch", "1", "fingerprint", fp.full(), "datapacks", "", "lastHeartbeat", Long.toString(now - 1000), "name", "testworld", "everyBlockSince", "0"));
        s.compact();
        s.close();
        writeSnapshot(now);
    }

    void writeSnapshot(long at) throws Exception {
        ContextSnapshot snap = new ContextSnapshot();
        snap.takenAt = at;
        snap.spawnX = 1000;
        snap.spawnZ = 1000;
        snap.write(data.resolve("worlds").resolve(WORLD.toString()));
    }

    Run approvedRun(List<Run.Entry> entries) throws Exception {
        Path runs = data.resolve("runs");
        Files.createDirectories(runs);
        Run run = Run.create(runs, "261107-1");
        run.set("world", WORLD.toString());
        run.set("worldName", "testworld");
        run.set("createdAt", Long.toString(System.currentTimeMillis() - 5000));
        run.set("approvedAt", Long.toString(System.currentTimeMillis() - 4000));
        run.set("version", VERSION);
        run.set("epoch", "1");
        run.writeChunks(entries);
        // Approval makes fresh terrain for every chunk; granite here, so it's easy to tell from the real stone.
        for (Run.Entry e : entries) writeReference(run, e.x(), e.z(), "minecraft:granite", e.x() * 31L + e.z());
        run.setState(Run.State.APPROVED, null);
        return run;
    }

    static List<Run.Entry> fullResets() {
        return List.of(new Run.Entry(3, 3, Integer.MIN_VALUE), new Run.Entry(3, 4, Integer.MIN_VALUE));
    }

    Applier applier(boolean dryRun) throws Exception {
        return new Applier(data, LOG, config(dryRun), VERSION);
    }

    Map<String, String> worldHashes() throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root.resolve("world"))) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return out;
    }

    RegionFile region(String kind, int x, int z) throws Exception {
        return RegionFile.load(dim.resolve(kind).resolve(RegionFile.fileName(x, z)));
    }

    Run reload(Run r) throws Exception {
        return Run.load(r.dir);
    }

    @Test
    void dryRunChangesNoWorldFile() throws Exception {
        Run run = approvedRun(fullResets());
        Map<String, String> before = worldHashes();
        applier(true).runStartup();
        assertEquals(before, worldHashes());
        run = reload(run);
        assertEquals(Run.State.APPROVED, run.state());
        assertTrue(run.get("dryRunResult").startsWith("would do 2 rewilds"), run.get("dryRunResult"));
    }

    @Test
    void appliesThenUndoRestoresExactly() throws Exception {
        RegionFile before = region("region", 3, 3);
        RegionFile entitiesBefore = region("entities", 3, 3);
        Run run = approvedRun(fullResets());
        applier(false).runStartup();
        run = reload(run);
        assertEquals(Run.State.APPLIED, run.state(), run.get("note"));
        RegionFile after = region("region", 3, 3);
        assertEquals("minecraft:granite", fill(after.readNbt(RegionFile.index(3, 3))));
        assertEquals("minecraft:granite", fill(after.readNbt(RegionFile.index(3, 4))));
        // The wild zombie wasn't kept; nothing of the old points of interest is left for the server to trust.
        assertEquals(0, region("entities", 3, 3).readNbt(RegionFile.index(3, 3)).getList("Entities").items().size());
        assertEquals(0, region("poi", 3, 3).readNbt(RegionFile.index(3, 3)).getCompound("Sections").size());
        // Neighbours are untouched, byte for byte.
        assertArrayEquals(before.get(RegionFile.index(2, 3)).data(), after.get(RegionFile.index(2, 3)).data());
        // The trash mirrors the world's layout.
        Path trash = data.resolve("trash").resolve(run.get("trashDate")).resolve(run.id).resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca");
        assertTrue(Files.exists(trash));
        WorldStore s = WorldStore.open(data.resolve("worlds").resolve(WORLD.toString()));
        // Rewild knows exactly what's in the chunk now, so it stays known, with its damage cleared.
        assertTrue(s.get(3, 3).known);
        assertEquals(0, s.get(3, 3).removed());
        assertTrue(s.get(3, 3).lastReset > 0);
        s.close();

        run.setState(Run.State.UNDO_REQUESTED, null);
        applier(false).runStartup();
        run = reload(run);
        assertEquals(Run.State.UNDONE, run.state(), run.get("note"));
        RegionFile undone = region("region", 3, 3);
        for (int idx : new int[]{RegionFile.index(3, 3), RegionFile.index(3, 4), RegionFile.index(2, 3)}) {
            assertArrayEquals(before.get(idx).data(), undone.get(idx).data());
        }
        assertArrayEquals(entitiesBefore.get(RegionFile.index(3, 3)).data(), region("entities", 3, 3).get(RegionFile.index(3, 3)).data());
        s = WorldStore.open(data.resolve("worlds").resolve(WORLD.toString()));
        assertTrue(s.get(3, 3).known);
        assertEquals(80, s.get(3, 3).removed());
        s.close();
    }

    @Test
    void crashAtEveryStepResumesToTheSameResult() throws Exception {
        approvedRun(fullResets());
        applier(false).runStartup();
        Map<String, String> expected = worldHashes();

        for (String marker : List.of("backup", "write world/dimensions/minecraft/overworld/region/r.0.0.mca",
                "write world/dimensions/minecraft/overworld/entities/r.0.0.mca", "write world/dimensions/minecraft/overworld/poi/r.0.0.mca", "store")) {
            Path fresh = Files.createTempDirectory("wr-crash");
            root = fresh;
            world();
            approvedRun(fullResets());
            Applier a = applier(false);
            a.crashBefore = m -> m.equals(marker);
            assertThrows(Applier.SimulatedCrash.class, a::runStartup, marker);
            assertEquals(Run.State.APPLYING, Run.load(data.resolve("runs/261107-1")).state());
            applier(false).runStartup();
            Run done = Run.load(data.resolve("runs/261107-1"));
            assertEquals(Run.State.APPLIED, done.state(), marker + ": " + done.get("note"));
            assertEquals(expected, worldHashes(), "after a crash before " + marker);
        }
    }

    @Test
    void crashDuringUndoResumes() throws Exception {
        RegionFile before = region("region", 3, 3);
        Run run = approvedRun(fullResets());
        applier(false).runStartup();
        run = reload(run);
        run.setState(Run.State.UNDO_REQUESTED, null);
        Applier a = applier(false);
        a.crashBefore = m -> m.startsWith("undo world/dimensions/minecraft/overworld/entities");
        assertThrows(Applier.SimulatedCrash.class, a::runStartup);
        applier(false).runStartup();
        run = reload(run);
        assertEquals(Run.State.UNDONE, run.state());
        assertArrayEquals(before.get(RegionFile.index(3, 3)).data(), region("region", 3, 3).get(RegionFile.index(3, 3)).data());
    }

    @Test
    void readOnlyWorldFolderFailsWithoutChangingAnything() throws Exception {
        Run run = approvedRun(fullResets());
        Map<String, String> before = worldHashes();
        Path regionDir = dim.resolve("region");
        regionDir.toFile().setWritable(false);
        try {
            applier(false).runStartup();
        } finally {
            regionDir.toFile().setWritable(true);
        }
        run = reload(run);
        assertEquals(Run.State.FAILED, run.state());
        assertEquals(before, worldHashes());
    }

    @Test
    void trashThatCantBeWrittenStopsBeforeAnyChange() throws Exception {
        Run run = approvedRun(fullResets());
        Map<String, String> before = worldHashes();
        // Stands in for a full disk: the trash folder can't be created.
        Files.createDirectories(data);
        Files.writeString(data.resolve("trash"), "not a folder");
        applier(false).runStartup();
        run = reload(run);
        assertEquals(Run.State.FAILED, run.state(), run.get("note"));
        assertTrue(run.get("note").contains("nothing was changed") || run.get("note").contains("rolled back"), run.get("note"));
        assertEquals(before, worldHashes());
    }

    @Test
    void uncleanShutdownBlocksTheRun() throws Exception {
        Run run = approvedRun(fullResets());
        writeSnapshot(System.currentTimeMillis() - 10_000); // older than the last heartbeat
        Map<String, String> before = worldHashes();
        applier(false).runStartup();
        run = reload(run);
        assertEquals(Run.State.APPROVED, run.state());
        assertTrue(run.get("lastCheck").contains("didn't shut down cleanly"));
        assertEquals(before, worldHashes());
    }

    @Test
    void gameVersionChangeBlocksTheRun() throws Exception {
        Run run = approvedRun(fullResets());
        Map<String, String> before = worldHashes();
        new Applier(data, LOG, config(false), "26.4").runStartup();
        assertTrue(reload(run).get("lastCheck").contains("game version"));
        assertEquals(before, worldHashes());
    }

    @Test
    void claimsUnreadableAtShutdownBlocksTheRun() throws Exception {
        Run run = approvedRun(fullResets());
        ContextSnapshot snap = new ContextSnapshot();
        snap.takenAt = System.currentTimeMillis();
        snap.claimsProblem = "GriefPrevention isn't running";
        snap.write(data.resolve("worlds").resolve(WORLD.toString()));
        applier(false).runStartup();
        assertTrue(reload(run).get("lastCheck").contains("GriefPrevention"));
    }

    @Test
    void brokenConfigBlocksTheRun() throws Exception {
        Run run = approvedRun(fullResets());
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        y.set("dry-run", false);
        y.set("worlds.testworld.enabled", true);
        y.set("run.max-chunks", "lots");
        new Applier(data, LOG, new RegenConfig(y), VERSION).runStartup();
        assertTrue(reload(run).get("lastCheck").contains("config.yml"));
    }

    @Test
    void aWorldAddedToNeverResetAfterApprovalIsNeverReset() throws Exception {
        // README: worlds in never-reset-worlds, such as kids' worlds, can never be turned on by mistake. That holds even
        // for a run approved before the world was added to the list: the startup step checks again and refuses.
        Run run = approvedRun(fullResets());
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml")));
        y.set("dry-run", false);
        y.set("worlds.testworld.enabled", true);
        y.set("never-reset-worlds", java.util.List.of("testworld"));
        Map<String, String> before = worldHashes();
        new Applier(data, LOG, new RegenConfig(y), VERSION).runStartup();
        assertTrue(reload(run).get("lastCheck").contains("isn't enabled"), reload(run).get("lastCheck"));
        assertEquals(before, worldHashes(), "no world file changed");
    }

    @Test
    void finalCheckDropsChunksThatChangedOrGainedAClaimAfterApproval() throws Exception {
        Run run = approvedRun(fullResets());
        WorldStore s = WorldStore.open(data.resolve("worlds").resolve(WORLD.toString()));
        s.edit(3, 4, r -> r.lastChange = System.currentTimeMillis());
        s.compact();
        s.close();
        ContextSnapshot snap = new ContextSnapshot();
        snap.takenAt = System.currentTimeMillis();
        snap.spawnX = 1000;
        snap.spawnZ = 1000;
        snap.claims.add(new int[]{0, 0, 1, 1}); // claim 2 chunks from 3,3: outside its 1-chunk buffer
        snap.write(data.resolve("worlds").resolve(WORLD.toString()));
        applier(false).runStartup();
        run = reload(run);
        assertEquals(Run.State.APPLIED, run.state());
        assertEquals("minecraft:granite", fill(region("region", 3, 3).readNbt(RegionFile.index(3, 3))));
        assertEquals("minecraft:stone", fill(region("region", 3, 4).readNbt(RegionFile.index(3, 4))));
        assertTrue(Files.readString(run.dir.resolve("check.txt")).contains("3, 4: skipped"));
    }

    @Test
    void belowGroundRepairReplacesOnlySectionsUnderTheCut() throws Exception {
        Run run = approvedRun(List.of(new Run.Entry(3, 3, 32)));
        // Mark the surface as kept, so the startup check still says "below ground".
        WorldStore s = WorldStore.open(data.resolve("worlds").resolve(WORLD.toString()));
        s.edit(3, 3, r -> {
            r.removedBySection.clear();
            r.removedBySection.put(1, 60);
            r.placed.put(ChunkRecord.pos(4, 66, 4), "CHEST");
        });
        s.compact();
        s.close();
        writeReference(run, 3, 3, "minecraft:deepslate", 3 * 31L + 3);
        Nbt.Compound real = region("region", 3, 3).readNbt(RegionFile.index(3, 3));
        applier(false).runStartup();
        run = reload(run);
        assertEquals(Run.State.APPLIED, run.state(), run.get("note") + " / " + Files.readString(run.dir.resolve("check.txt")));
        Nbt.Compound fixed = region("region", 3, 3).readNbt(RegionFile.index(3, 3));
        for (Nbt.Compound sec : fixed.getList("sections").compounds()) {
            int y = sec.getInt("Y", 0);
            if (y < -4) continue;
            String first = sec.getCompound("block_states").getList("palette").compounds().get(sec.getInt("Y", 0) == -4 ? 1 : 0).getString("Name");
            if (y < 2) assertEquals("minecraft:deepslate", first, "section " + y);
            else if (y * 16 + 15 <= 64) assertEquals("minecraft:stone", first, "section " + y);
        }
        assertFalse(fixed.containsKey("starlight.light_version"));
        assertEquals(real.getInt("DataVersion", 0), fixed.getInt("DataVersion", 1));
        // The zombie at y=40 is above the cut and stays.
        assertEquals(1, region("entities", 3, 3).readNbt(RegionFile.index(3, 3)).getList("Entities").items().size());
    }

    @Test
    void referenceWithDifferentBedrockIsRefused() throws Exception {
        Run run = approvedRun(List.of(new Run.Entry(3, 3, 32)));
        WorldStore s = WorldStore.open(data.resolve("worlds").resolve(WORLD.toString()));
        s.edit(3, 3, r -> {
            r.removedBySection.clear();
            r.removedBySection.put(1, 60);
            r.placed.put(ChunkRecord.pos(4, 66, 4), "CHEST");
        });
        s.compact();
        s.close();
        writeReference(run, 3, 3, "minecraft:deepslate", 999_999);
        Map<String, String> before = worldHashes();
        applier(false).runStartup();
        run = reload(run);
        assertEquals(Run.State.FAILED, run.state());
        assertTrue(run.get("note").contains("bedrock"), run.get("note"));
        assertEquals(before, worldHashes());
    }

    /** The block filling a test chunk's middle, at y=0. */
    static String fill(Nbt.Compound chunk) {
        for (Nbt.Compound sec : chunk.getList("sections").compounds()) {
            if (sec.getInt("Y", 99) == 0) return sec.getCompound("block_states").getList("palette").compounds().get(0).getString("Name");
        }
        return null;
    }

    void writeReference(Run run, int x, int z, String fill, long seed) throws Exception {
        Files.createDirectories(run.dir.resolve("reference"));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(Nbt.write(TestChunks.chunk(x, z, fill, 64, seed)));
        }
        Files.write(run.referenceFile(x, z), bytes.toByteArray());
    }

    @Test
    void oldTrashIsCleared() throws Exception {
        Path old = data.resolve("trash/2020-01-01/x");
        Files.createDirectories(old);
        Path recent = data.resolve("trash").resolve(java.time.LocalDate.now().toString());
        Files.createDirectories(recent);
        applier(true).runStartup();
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
    }
}
