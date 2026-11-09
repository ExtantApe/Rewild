package com.extantape.rewild.run;

import com.extantape.rewild.config.RegenConfig;
import com.extantape.rewild.region.ChunkFacts;
import com.extantape.rewild.region.Nbt;
import com.extantape.rewild.region.RegionFile;
import com.extantape.rewild.region.Underground;
import com.extantape.rewild.scan.ContextSnapshot;
import com.extantape.rewild.scan.IntentRules;
import com.extantape.rewild.scan.Scanner;
import com.extantape.rewild.store.ChunkRecord;
import com.extantape.rewild.store.Fingerprint;
import com.extantape.rewild.store.WorldStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * The startup step. Runs before any world loads, so no chunk is open while its files change. For each approved run it
 * repeats every check with the server stopped, copies the region files it will touch into the dated trash folder,
 * then rewrites each file atomically, noting progress in the run's journal. A crash at any point either resumes from
 * the journal or rolls back from the trash copies. Undo works the same way in reverse.
 */
public final class Applier {
    public static final String[] KINDS = {"region", "entities", "poi"};

    private final Path dataFolder;
    private final Path runsDir;
    private final Path trashRoot;
    private final Logger log;
    private final RegenConfig config;
    private final String gameVersion;
    private final Map<UUID, WorldStore> stores = new LinkedHashMap<>();
    /** Test hook: called with a marker before each step, so tests can simulate a crash there. */
    Function<String, Boolean> crashBefore = m -> false;

    public Applier(Path dataFolder, Logger log, RegenConfig config, String gameVersion) {
        this.dataFolder = dataFolder;
        this.runsDir = dataFolder.resolve("runs");
        this.trashRoot = dataFolder.resolve("trash");
        this.log = log;
        this.config = config;
        this.gameVersion = gameVersion;
    }

    public void setCrashHook(Function<String, Boolean> hook) {
        this.crashBefore = hook;
    }

    /** The stores this step opened, for the plugin to keep using once the server is up. */
    public Map<UUID, WorldStore> stores() {
        return stores;
    }

    public static class SimulatedCrash extends RuntimeException {
        public SimulatedCrash(String at) {
            super("simulated crash before " + at);
        }
    }

    private void step(String marker) {
        if (crashBefore.apply(marker)) throw new SimulatedCrash(marker);
    }

    public void runStartup() {
        expireTrash();
        List<String> problems = new ArrayList<>();
        for (Run run : Run.all(runsDir, problems)) {
            try {
                switch (run.state()) {
                    case APPROVED -> apply(run);
                    case APPLYING -> resume(run);
                    case UNDO_REQUESTED, UNDOING -> undo(run);
                    case PROPOSED -> expireIfStale(run);
                    default -> { }
                }
            } catch (SimulatedCrash c) {
                throw c;
            } catch (Exception e) {
                log.log(Level.SEVERE, "Run " + run.id + " couldn't be processed; its world files are as they were before this step", e);
            }
        }
        for (String p : problems) log.warning("Couldn't read run " + p);
    }

    private WorldStore store(Run run) throws IOException {
        UUID uid = UUID.fromString(run.get("world"));
        WorldStore s = stores.get(uid);
        if (s == null) {
            s = WorldStore.open(dataFolder.resolve("worlds").resolve(uid.toString()));
            stores.put(uid, s);
        }
        return s;
    }

    private void expireIfStale(Run run) throws IOException {
        long created = run.getLong("createdAt");
        if (System.currentTimeMillis() - created > config.approvalExpiresDays * Scanner.DAY) {
            run.setState(Run.State.EXPIRED, "never approved; scan again for a fresh list");
        }
    }

    // ---- apply ----

    /** Fresh apply of an approved run: check everything again, then act, or explain why not. */
    void apply(Run run) throws IOException {
        long now = System.currentTimeMillis();
        String name = run.get("worldName");
        if (now - run.getLong("approvedAt") > config.approvalExpiresDays * Scanner.DAY) {
            run.setState(Run.State.EXPIRED, "approved more than " + config.approvalExpiresDays + " days ago; scan again");
            log.info("Run " + run.id + " lapsed without being applied.");
            return;
        }
        String blocked = blockedReason(run);
        if (blocked != null) {
            run.set("lastCheck", now + " not applied: " + blocked);
            run.save();
            log.warning("Run " + run.id + " (" + name + ") wasn't applied: " + blocked);
            return;
        }
        WorldStore store = store(run);
        Path dimension = Path.of(store.meta("dimension"));
        ContextSnapshot snap = ContextSnapshot.read(store.dir());

        Scanner.Context ctx = new Scanner.Context();
        ctx.settings = config.world(name);
        ctx.environment = store.meta("environment", "NORMAL");
        ctx.now = now;
        ctx.epoch = Integer.parseInt(store.meta("epoch", "1"));
        ctx.minY = Integer.parseInt(store.meta("minY", "-64"));
        ctx.maxChunks = Integer.MAX_VALUE;
        ctx.protectedAreas.addAll(store.protectedAreas());
        ctx.intent = IntentRules.from(config);
        snap.fill(ctx);

        List<Run.Entry> proposed = run.chunks();
        Map<Long, ChunkRecord> records = store.snapshot();
        Set<Long> keys = new HashSet<>();
        for (Run.Entry e : proposed) keys.add(ChunkRecord.key(e.x(), e.z()));
        Map<Long, ChunkFacts> facts = new java.util.HashMap<>();
        ChunkFacts.Reader reader = new ChunkFacts.Reader(dimension);
        for (long k : Scanner.factsNeeded(keys, ctx)) facts.put(k, reader.read(ChunkRecord.keyX(k), ChunkRecord.keyZ(k)));
        Scanner scanner = new Scanner(ctx, records, facts);

        List<Run.Entry> plan = new ArrayList<>();
        StringBuilder report = new StringBuilder("# Startup check of run " + run.id + " in " + name + ", "
                + java.time.Instant.ofEpochMilli(now) + (config.dryRun ? " (dry run: nothing was changed)" : "")
                + "\n# Chunk coordinates, then what happened. /wr why <world> <x> <z> explains any chunk.\n");
        int full = 0, under = 0, skipped = 0;
        for (Run.Entry e : proposed) {
            Scanner.Verdict v = scanner.evaluate(e.x(), e.z());
            String decision;
            if (e.full() && v.kind() == Scanner.Kind.QUEUED) {
                plan.add(e);
                full++;
                decision = "reset";
            } else if (!e.full() && v.kind() == Scanner.Kind.UNDERGROUND && Files.exists(run.referenceFile(e.x(), e.z()))) {
                plan.add(new Run.Entry(e.x(), e.z(), Math.min(e.cutY(), v.cutY())));
                under++;
                decision = "reset below y=" + Math.min(e.cutY(), v.cutY());
            } else {
                skipped++;
                decision = "skipped: " + (v.kind() == Scanner.Kind.UNDERGROUND || v.kind() == Scanner.Kind.QUEUED
                        ? "approved as a different kind of reset, or its reference terrain is missing" : String.join("; ", v.reasons()));
            }
            report.append(e.x()).append(", ").append(e.z()).append(": ").append(decision)
                    .append("   (blocks ").append(e.x() * 16).append(", ").append(e.z() * 16).append(")\n");
        }
        RegionFile.writeFileAtomically(run.dir.resolve("check.txt"), report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String summary = full + " full resets and " + under + " below-ground repairs; " + skipped + " skipped (see check.txt)";

        if (config.dryRun) {
            run.set("dryRunAt", Long.toString(now));
            run.set("dryRunResult", "would do " + summary);
            run.save();
            log.info("Dry run for " + run.id + " (" + name + "): would do " + summary + ". No world file changed."
                    + " Each chunk's decision is in " + dataFolder.relativize(run.dir.resolve("check.txt"))
                    + ". Set dry-run: false in config.yml to apply it at a later restart.");
            return;
        }
        if (plan.isEmpty()) {
            run.set("appliedAt", Long.toString(now));
            run.setState(Run.State.APPLIED, "nothing left to reset after the startup check; " + summary);
            log.info("Run " + run.id + ": nothing left to reset after the startup check.");
            return;
        }
        run.writePlan(plan);
        run.set("trashDate", LocalDate.now(ZoneId.systemDefault()).toString());
        run.set("summary", summary);
        run.setState(Run.State.APPLYING, null);
        run.log("start");
        execute(run, store, plan);
        log.info("Run " + run.id + " (" + name + ") applied: " + summary + ". Undo with /wr undo " + run.id + ".");
    }

    /** Why a run can't be applied now, or null if nothing stands in the way. */
    String blockedReason(Run run) throws IOException {
        if (!config.healthy()) return "config.yml has problems: " + String.join("; ", config.errors());
        String name = run.get("worldName");
        if (!config.world(name).enabled()) return "world " + name + " isn't enabled in config.yml";
        WorldStore store = store(run);
        if (store.meta("dimension") == null) return "the world's folder was never recorded";
        if (store.meta("generatorChanged") != null) return "the world generator changed and hasn't been approved";
        Fingerprint fp = Fingerprint.parse(store.meta("fingerprint"));
        if (fp == null) return "no generator fingerprint was saved";
        if (!gameVersion.equals(fp.version())) return "the game version changed from " + fp.version() + " to " + gameVersion;
        if (!gameVersion.equals(run.get("version"))) return "the run was scanned on " + run.get("version") + ", and this is " + gameVersion;
        String packs = store.meta("datapacks", "");
        String hash = Fingerprint.hashFolder(packs.isEmpty() ? null : Path.of(packs));
        if (!hash.equals(fp.packFiles())) return "datapack files changed since the generator fingerprint was saved";
        if (!run.get("epoch").equals(store.meta("epoch", "1"))) return "the world generator changed after the scan";
        ContextSnapshot snap = ContextSnapshot.read(store.dir());
        long heartbeat = Long.parseLong(store.meta("lastHeartbeat", "0"));
        if (snap == null || snap.takenAt < heartbeat) {
            return "the server didn't shut down cleanly, so the final check of claims and other plugins couldn't run;"
                    + " it waits for a clean restart";
        }
        if (snap.takenAt < run.getLong("approvedAt")) return "no final check has run since approval";
        if (snap.claimsProblem != null) return "claims couldn't be read at shutdown: " + snap.claimsProblem;
        if (snap.externalProblem != null) return snap.externalProblem;
        if (snap.trackerProblem != null) return "tracking wasn't healthy: " + snap.trackerProblem;
        return null;
    }

    void resume(Run run) throws IOException {
        log.warning("Run " + run.id + " was interrupted while being applied. Finishing it from its journal.");
        execute(run, store(run), run.plan());
        log.info("Run " + run.id + " finished after the interruption.");
    }

    private Path trashDir(Run run, String suffix) {
        return trashRoot.resolve(run.get("trashDate")).resolve(run.id + suffix);
    }

    /** Where a world file's copy goes in the trash: the same path it has under the server folder. */
    static Path mirror(Path trash, Path serverRoot, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path root = serverRoot.toAbsolutePath().normalize();
        if (abs.startsWith(root)) return trash.resolve(root.relativize(abs).toString());
        return trash.resolve("outside-server-folder").resolve(abs.toString().replaceAll("[:\\\\/]+", "_"));
    }

    /** Groups a plan by the files it touches, in a stable order. */
    static Map<Path, List<Run.Entry>> filesFor(Path dimension, List<Run.Entry> plan) {
        Map<Path, List<Run.Entry>> out = new LinkedHashMap<>();
        for (String kind : KINDS) {
            for (Run.Entry e : plan) {
                out.computeIfAbsent(dimension.resolve(kind).resolve(RegionFile.fileName(e.x(), e.z())), k -> new ArrayList<>()).add(e);
            }
        }
        return out;
    }

    private void execute(Run run, WorldStore store, List<Run.Entry> plan) throws IOException {
        Path dimension = Path.of(store.meta("dimension"));
        Path serverRoot = Path.of(store.meta("serverRoot"));
        Path trash = trashDir(run, "");
        Map<Path, List<Run.Entry>> files = filesFor(dimension, plan);
        try {
            if (!run.journalHas("backup-done")) {
                step("backup");
                backup(files, serverRoot, trash);
                saveRecords(store, plan, run.dir.resolve("records.bin"));
                run.log("backup-done");
            }
            for (var f : files.entrySet()) {
                String rel = serverRoot.toAbsolutePath().normalize().relativize(f.getKey().toAbsolutePath().normalize()).toString();
                if (run.journalHas("done " + rel)) continue;
                step("write " + rel);
                Path original = mirror(trash, serverRoot, f.getKey());
                if (!Files.exists(original)) {
                    run.log("done " + rel);
                    continue;
                }
                RegionFile r = RegionFile.load(original).withPath(f.getKey());
                Set<Path> removedExternal = new HashSet<>();
                String kind = f.getKey().getParent().getFileName().toString();
                for (Run.Entry e : f.getValue()) {
                    int idx = RegionFile.index(e.x(), e.z());
                    RegionFile.RawChunk raw = r.get(idx);
                    if (raw == null) continue;
                    if (e.full()) {
                        if (raw.isExternal()) removedExternal.add(RegionFile.externalPath(f.getKey(), idx));
                        r.remove(idx);
                    } else {
                        Nbt.Compound nbt = r.readNbt(idx);
                        Nbt.Compound fixed = switch (kind) {
                            case "region" -> Underground.repairChunk(nbt, readReference(run, e), e.cutY(),
                                    Integer.parseInt(store.meta("minY", "-64")));
                            case "entities" -> Underground.repairEntities(nbt, e.cutY());
                            default -> Underground.repairPoi(nbt, e.cutY());
                        };
                        RegionFile.RawChunk packed = RegionFile.compress(Nbt.write(fixed), raw.timestamp());
                        if (raw.isExternal() && !packed.isExternal()) removedExternal.add(RegionFile.externalPath(f.getKey(), idx));
                        r.put(idx, packed);
                    }
                }
                r.writeAtomically();
                for (Path mcc : removedExternal) Files.deleteIfExists(mcc);
                run.log("done " + rel);
            }
            if (!run.journalHas("store-done")) {
                step("store");
                long now = System.currentTimeMillis();
                for (Run.Entry e : plan) {
                    if (e.full()) store.edit(e.x(), e.z(), rec -> rec.clearForReset(now));
                    else store.editIfPresent(e.x(), e.z(), rec -> rec.clearBelow(e.cutY(), now));
                }
                store.compact();
                run.log("store-done");
            }
            run.set("appliedAt", Long.toString(System.currentTimeMillis()));
            run.setState(Run.State.APPLIED, run.get("summary"));
        } catch (SimulatedCrash c) {
            throw c;
        } catch (IOException | RuntimeException e) {
            log.log(Level.SEVERE, "Run " + run.id + " failed while being applied; rolling back from the trash copies", e);
            String rolled = rollback(run, files, serverRoot, trash);
            run.setState(Run.State.FAILED, "failed: " + e.getMessage() + "; " + rolled);
            throw new IOException("run " + run.id + " failed: " + e.getMessage() + "; " + rolled, e);
        }
    }

    /** Copies each existing file, and any .mcc files for the run's chunks, into the trash, forced to disk. */
    private void backup(Map<Path, List<Run.Entry>> files, Path serverRoot, Path trash) throws IOException {
        try {
            for (var f : files.entrySet()) {
                if (!Files.exists(f.getKey())) continue;
                copyDurably(f.getKey(), mirror(trash, serverRoot, f.getKey()));
                for (Run.Entry e : f.getValue()) {
                    Path mcc = RegionFile.externalPath(f.getKey(), RegionFile.index(e.x(), e.z()));
                    if (Files.exists(mcc)) copyDurably(mcc, mirror(trash, serverRoot, mcc));
                }
            }
        } catch (IOException e) {
            // Nothing in the world has changed yet. Clear the partial copy so it isn't mistaken for a good one.
            deleteTree(trash);
            throw new IOException("couldn't copy region files to the trash, so nothing was changed: " + e.getMessage(), e);
        }
    }

    static void copyDurably(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Path tmp = to.resolveSibling(to.getFileName() + ".wr-tmp");
        Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
        try (var ch = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        RegionFile.syncDirectory(to.getParent());
    }

    /** Puts every backed-up file back. Files the run never touched are left alone. */
    private String rollback(Run run, Map<Path, List<Run.Entry>> files, Path serverRoot, Path trash) {
        int restored = 0;
        List<String> failed = new ArrayList<>();
        for (var f : files.entrySet()) {
            Path original = mirror(trash, serverRoot, f.getKey());
            try {
                if (Files.exists(original)) {
                    RegionFile.writeFileAtomically(f.getKey(), Files.readAllBytes(original));
                    restored++;
                }
                for (Run.Entry e : f.getValue()) {
                    Path mcc = RegionFile.externalPath(f.getKey(), RegionFile.index(e.x(), e.z()));
                    Path mccCopy = mirror(trash, serverRoot, mcc);
                    if (Files.exists(mccCopy)) RegionFile.writeFileAtomically(mcc, Files.readAllBytes(mccCopy));
                }
            } catch (IOException e) {
                failed.add(f.getKey() + " (" + e.getMessage() + ")");
            }
        }
        try {
            run.log(failed.isEmpty() ? "rolled-back" : "rollback-incomplete");
        } catch (IOException ignored) {
        }
        if (failed.isEmpty()) return "rolled back " + restored + " files; the world is as it was";
        log.severe("Rollback of run " + run.id + " couldn't restore: " + failed + ". Copies are in " + trash);
        return "rollback couldn't restore " + failed.size() + " files; originals are in " + trash;
    }

    private static Nbt.Compound readReference(Run run, Run.Entry e) throws IOException {
        Path f = run.referenceFile(e.x(), e.z());
        try (var in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            return Nbt.read(in.readAllBytes());
        }
    }

    private static void saveRecords(WorldStore store, List<Run.Entry> plan, Path file) throws IOException {
        if (Files.exists(file)) return;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(bytes))) {
            for (Run.Entry e : plan) {
                ChunkRecord r = store.get(e.x(), e.z());
                if (r != null) r.write(out);
            }
        }
        RegionFile.writeFileAtomically(file, bytes.toByteArray());
    }

    private static List<ChunkRecord> loadRecords(Path file) throws IOException {
        List<ChunkRecord> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                try {
                    out.add(ChunkRecord.read(in));
                } catch (EOFException eof) {
                    return out;
                }
            }
        }
    }

    // ---- undo ----

    void undo(Run run) throws IOException {
        WorldStore store = store(run);
        if (run.get("trashDate") == null || !Files.isDirectory(trashDir(run, ""))) {
            run.setState(Run.State.APPLIED, "can't undo: its trash copy is gone");
            log.warning("Run " + run.id + " can't be undone: its trash copy is gone.");
            return;
        }
        if (config.dryRun) {
            log.info("Undo of run " + run.id + " waits: dry-run is on, so no world file changes.");
            return;
        }
        if (store.meta("dimension") == null) {
            log.warning("Undo of run " + run.id + " waits: the world's folder isn't recorded.");
            return;
        }
        Path dimension = Path.of(store.meta("dimension"));
        Path serverRoot = Path.of(store.meta("serverRoot"));
        Path trash = trashDir(run, "");
        if (run.get("undoTrashDate") == null) {
            run.set("undoTrashDate", LocalDate.now(ZoneId.systemDefault()).toString());
            run.setState(Run.State.UNDOING, null);
        } else if (run.state() != Run.State.UNDOING) {
            run.setState(Run.State.UNDOING, null);
        }
        Path undoTrash = trashRoot.resolve(run.get("undoTrashDate")).resolve(run.id + "-undo");
        List<Run.Entry> plan = run.plan();
        Map<Path, List<Run.Entry>> files = filesFor(dimension, plan);
        try {
            if (!run.journalHas("undo-backup-done")) {
                step("undo-backup");
                for (Path f : files.keySet()) {
                    Path copy = mirror(undoTrash, serverRoot, f);
                    if (Files.exists(f) && !Files.exists(copy)) copyDurably(f, copy);
                }
                run.log("undo-backup-done");
            }
            for (var f : files.entrySet()) {
                String rel = serverRoot.toAbsolutePath().normalize().relativize(f.getKey().toAbsolutePath().normalize()).toString();
                if (run.journalHas("undone " + rel)) continue;
                step("undo " + rel);
                Path originalPath = mirror(trash, serverRoot, f.getKey());
                RegionFile original = RegionFile.load(originalPath);
                RegionFile current = RegionFile.load(f.getKey());
                for (Run.Entry e : f.getValue()) {
                    int idx = RegionFile.index(e.x(), e.z());
                    RegionFile.RawChunk was = original.get(idx);
                    if (was != null) current.put(idx, was);
                    else current.remove(idx);
                }
                if (current.count() > 0 || Files.exists(f.getKey())) {
                    Files.createDirectories(f.getKey().getParent());
                    current.writeAtomically();
                }
                run.log("undone " + rel);
            }
            if (!run.journalHas("undo-store-done")) {
                step("undo-store");
                for (ChunkRecord r : loadRecords(run.dir.resolve("records.bin"))) store.put(r);
                store.compact();
                run.log("undo-store-done");
            }
            run.set("undoneAt", Long.toString(System.currentTimeMillis()));
            run.setState(Run.State.UNDONE, "undone; the replaced terrain is in " + undoTrash);
            log.info("Run " + run.id + " undone. The chunks are back as they were before it.");
        } catch (SimulatedCrash c) {
            throw c;
        } catch (IOException | RuntimeException e) {
            log.log(Level.SEVERE, "Undo of run " + run.id + " failed; putting files back as they were before the undo", e);
            for (Path f : files.keySet()) {
                Path copy = mirror(undoTrash, serverRoot, f);
                try {
                    if (Files.exists(copy)) RegionFile.writeFileAtomically(f, Files.readAllBytes(copy));
                } catch (IOException ex) {
                    log.severe("Couldn't put back " + f + " from " + copy + ": " + ex.getMessage());
                }
            }
            run.setState(Run.State.APPLIED, "undo failed: " + e.getMessage() + "; files were put back as before the undo");
        }
    }

    // ---- trash ----

    /** Deletes trash folders older than the configured days, and marks runs that can no longer be undone. */
    void expireTrash() {
        if (!Files.isDirectory(trashRoot)) return;
        LocalDate cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(config.trashKeepDays);
        try (Stream<Path> s = Files.list(trashRoot)) {
            for (Path d : s.toList()) {
                LocalDate date;
                try {
                    date = LocalDate.parse(d.getFileName().toString());
                } catch (java.time.format.DateTimeParseException e) {
                    continue;
                }
                if (date.isBefore(cutoff)) {
                    deleteTree(d);
                    log.info("Cleared trash from " + date + " (older than " + config.trashKeepDays + " days).");
                }
            }
        } catch (IOException e) {
            log.warning("Couldn't clear old trash: " + e.getMessage());
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }
}
