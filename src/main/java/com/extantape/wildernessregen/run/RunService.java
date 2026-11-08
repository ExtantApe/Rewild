package com.extantape.wildernessregen.run;

import com.extantape.wildernessregen.keep.Keepers;
import com.extantape.wildernessregen.region.Nbt;
import com.extantape.wildernessregen.region.RegionFile;
import com.extantape.wildernessregen.scan.ContextSnapshot;
import com.extantape.wildernessregen.scan.ScanService;
import com.extantape.wildernessregen.scan.Scanner;
import com.extantape.wildernessregen.store.ChunkRecord;
import com.extantape.wildernessregen.store.WorldState;
import com.extantape.wildernessregen.track.Tracking;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

/** Runtime side of runs: proposing them from scans, approving, cancelling and undoing, and the shutdown check. */
public final class RunService {
    private final Plugin plugin;
    private final Tracking tracking;
    private final Path runsDir;

    public RunService(Plugin plugin, Tracking tracking) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.runsDir = plugin.getDataFolder().toPath().resolve("runs");
    }

    public List<Run> all() {
        return Run.all(runsDir, new ArrayList<>());
    }

    public Run find(String id) {
        for (Run r : all()) if (r.id.equalsIgnoreCase(id)) return r;
        return null;
    }

    private String nextId() {
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        int n = 1;
        while (Files.exists(runsDir.resolve(day + "-" + n))) n++;
        return day + "-" + n;
    }

    /** Saves a scan's queued chunks as a proposed run. Returns null if nothing is queued. */
    public Run propose(World w, ScanService.Result scan) throws IOException {
        List<Run.Entry> entries = new ArrayList<>();
        int full = 0, under = 0;
        for (Scanner.Verdict v : scan.verdicts().values()) {
            if (v.kind() == Scanner.Kind.QUEUED) {
                entries.add(new Run.Entry(v.x(), v.z(), Integer.MIN_VALUE));
                full++;
            } else if (v.kind() == Scanner.Kind.UNDERGROUND) {
                entries.add(new Run.Entry(v.x(), v.z(), v.cutY()));
                under++;
            }
        }
        if (entries.isEmpty()) return null;
        entries.sort((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        Files.createDirectories(runsDir);
        Run run = Run.create(runsDir, nextId());
        run.set("world", w.getUID().toString());
        run.set("worldName", w.getName());
        run.set("createdAt", Long.toString(System.currentTimeMillis()));
        run.set("version", Bukkit.getMinecraftVersion());
        run.set("epoch", Integer.toString(scan.state().epoch));
        run.set("full", Integer.toString(full));
        run.set("underground", Integer.toString(under));
        run.writeChunks(entries);
        run.setState(Run.State.PROPOSED, full + " full resets and " + under + " below-ground repairs proposed");
        return run;
    }

    /** Problems that stop approval right now, or null. */
    public String approvalProblem(Run run) {
        if (run.state() != Run.State.PROPOSED) return "run " + run.id + " is " + run.state().name().toLowerCase().replace('_', ' ') + ", not proposed";
        if (!tracking.config().healthy()) return "config.yml has problems: " + String.join("; ", tracking.config().errors());
        World w = Bukkit.getWorld(java.util.UUID.fromString(run.get("world")));
        if (w == null) return "its world isn't loaded";
        WorldState s = tracking.state(w);
        if (s == null) return "its world isn't enabled";
        if (!s.healthy()) return s.problem;
        if (!run.get("epoch").equals(Integer.toString(s.epoch))) return "the world generator changed after the scan; scan again";
        if (System.currentTimeMillis() - run.getLong("createdAt") > tracking.config().approvalExpiresDays * Scanner.DAY) {
            return "the scan is older than " + tracking.config().approvalExpiresDays + " days; scan again";
        }
        Keepers.Result k = new Keepers(tracking.config().claimPlugin).read(w);
        if (k.claimsProblem != null) return "claims can't be read: " + k.claimsProblem;
        if (k.externalProblem != null) return k.externalProblem;
        for (Run other : all()) {
            if (other != run && other.get("world").equals(run.get("world"))
                    && (other.state() == Run.State.APPROVED || other.state() == Run.State.APPLYING || other.state() == Run.State.UNDO_REQUESTED)) {
                return "run " + other.id + " in the same world is already waiting for a restart";
            }
        }
        return null;
    }

    /**
     * Approves a run. Below-ground repairs first need a freshly generated copy of each chunk, made in a temporary world
     * with the same seed and generator, which is deleted afterwards.
     */
    public CompletableFuture<String> approve(Run run, String by) {
        CompletableFuture<String> out = new CompletableFuture<>();
        String problem = approvalProblem(run);
        if (problem != null) {
            out.completeExceptionally(new IllegalStateException(problem));
            return out;
        }
        World w = Bukkit.getWorld(java.util.UUID.fromString(run.get("world")));
        List<Run.Entry> under;
        try {
            under = run.chunks().stream().filter(e -> !e.full()).toList();
        } catch (IOException e) {
            out.completeExceptionally(e);
            return out;
        }
        CompletableFuture<List<String>> refs = under.isEmpty() ? CompletableFuture.completedFuture(List.of()) : buildReferences(run, w, under);
        refs.whenComplete((dropped, err) -> {
            if (err != null) {
                out.completeExceptionally(err);
                return;
            }
            try {
                String again = approvalProblem(run);
                if (again != null) throw new IllegalStateException(again);
                run.set("approvedAt", Long.toString(System.currentTimeMillis()));
                run.set("approvedBy", by);
                run.setState(Run.State.APPROVED, null);
                String msg = "Run " + run.id + " approved. " + (tracking.config().dryRun
                        ? "dry-run is on, so the next restart only checks it and reports in the log."
                        : "It applies at the next restart, after a final check.");
                if (!dropped.isEmpty()) msg += " Left out of the below-ground repairs: " + String.join("; ", dropped) + ".";
                out.complete(msg);
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    /** Generates each chunk in a temporary copy of the world and saves its data into the run. Returns chunks it dropped. */
    private CompletableFuture<List<String>> buildReferences(Run run, World w, List<Run.Entry> entries) {
        CompletableFuture<List<String>> out = new CompletableFuture<>();
        String name = "wr_reference_" + run.id.replace('-', '_');
        World ref;
        try {
            ref = new WorldCreator(name).copy(w).createWorld();
        } catch (RuntimeException | LinkageError e) {
            out.completeExceptionally(new IllegalStateException("couldn't create a temporary reference world: " + e.getMessage(), e));
            return out;
        }
        if (ref == null || ref.getSeed() != w.getSeed()) {
            out.completeExceptionally(new IllegalStateException("the temporary reference world didn't get the same seed"));
            if (ref != null) cleanupReference(ref, null);
            return out;
        }
        Path refDim = Tracking.dimensionFolder(ref);
        Path refFolder = ref.getWorldFolder().toPath();
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (Run.Entry e : entries) loads.add(ref.getChunkAtAsync(e.x(), e.z(), true));
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            boolean unloaded = Bukkit.unloadWorld(ref, true);
            if (err != null || !unloaded) {
                cleanupReference(null, refFolder);
                out.completeExceptionally(new IllegalStateException(err != null ? "reference generation failed: " + err.getMessage()
                        : "the temporary reference world wouldn't unload"));
                return;
            }
            // Its files are written as the world unloads; read them off the main thread.
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                List<String> dropped = new ArrayList<>();
                try {
                    Files.createDirectories(run.dir.resolve("reference"));
                    for (Run.Entry e : entries) {
                        RegionFile r = RegionFile.load(refDim.resolve("region").resolve(RegionFile.fileName(e.x(), e.z())));
                        Nbt.Compound nbt = r.readNbt(RegionFile.index(e.x(), e.z()));
                        if (nbt == null || !"minecraft:full".equals(nbt.getString("Status"))) {
                            dropped.add(e.x() + ", " + e.z() + " (reference didn't generate fully)");
                            continue;
                        }
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
                            gz.write(Nbt.write(nbt));
                        }
                        RegionFile.writeFileAtomically(run.referenceFile(e.x(), e.z()), bytes.toByteArray());
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        cleanupReference(null, refFolder);
                        out.complete(dropped);
                    });
                } catch (IOException | RuntimeException ex) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        cleanupReference(null, refFolder);
                        out.completeExceptionally(ex);
                    });
                }
            }, 20);
        }));
        return out;
    }

    /** Deletes the plugin's own temporary reference world. Never called on any other folder. */
    private void cleanupReference(World loaded, Path folder) {
        try {
            if (loaded != null) {
                folder = loaded.getWorldFolder().toPath();
                Bukkit.unloadWorld(loaded, false);
            }
            if (folder == null || !folder.getFileName().toString().startsWith("wr_reference_")) return;
            Applier.deleteTree(folder);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't delete the temporary reference world at " + folder, e);
        }
    }

    /** Cancels an approval, or asks for an applied run to be undone at the next restart. */
    public String undo(Run run) throws IOException {
        switch (run.state()) {
            case PROPOSED, APPROVED -> {
                run.setState(Run.State.CANCELLED, "cancelled before it was applied");
                return "Run " + run.id + " cancelled. Nothing was changed.";
            }
            case APPLIED -> {
                if (run.get("trashDate") == null) return "Run " + run.id + " changed nothing, so there's nothing to undo.";
                run.setState(Run.State.UNDO_REQUESTED, null);
                return "Run " + run.id + " will be undone at the next restart. Chunks it reset go back to how they were"
                        + " before it, which also discards anything built in them since.";
            }
            case UNDO_REQUESTED -> {
                run.setState(Run.State.APPLIED, "undo request withdrawn");
                return "Undo of run " + run.id + " withdrawn.";
            }
            default -> {
                return "Run " + run.id + " is " + run.state().name().toLowerCase().replace('_', ' ') + "; there's nothing to undo.";
            }
        }
    }

    /**
     * Written at shutdown: what claims, respawn points and other plugins say right now, so the startup step can repeat
     * the full check with the server stopped. Must be called before tracking stops.
     */
    public java.util.Map<WorldState, ContextSnapshot> shutdownSnapshots() {
        java.util.Map<WorldState, ContextSnapshot> out = new java.util.HashMap<>();
        for (WorldState s : tracking.states()) {
            World w = Bukkit.getWorld(s.uid);
            if (w == null) continue;
            ContextSnapshot snap = new ContextSnapshot();
            try {
                Keepers.Result k = new Keepers(tracking.config().claimPlugin).read(w);
                snap.claimsProblem = k.claimsProblem;
                snap.externalProblem = k.externalProblem;
                snap.claims.addAll(k.claims);
                snap.respawnChunks.addAll(k.respawnChunks);
                k.external.forEach((key, reasons) -> {
                    for (String r : reasons) snap.external.add(new String[]{Integer.toString(ChunkRecord.keyX(key)), Integer.toString(ChunkRecord.keyZ(key)), r});
                });
            } catch (RuntimeException e) {
                snap.externalProblem = "the shutdown check failed: " + e;
            }
            snap.trackerProblem = s.problem;
            snap.spawnX = w.getSpawnLocation().getBlockX() >> 4;
            snap.spawnZ = w.getSpawnLocation().getBlockZ() >> 4;
            out.put(s, snap);
        }
        return out;
    }

    public void writeSnapshots(java.util.Map<WorldState, ContextSnapshot> snaps) {
        for (var e : snaps.entrySet()) {
            try {
                e.getValue().takenAt = System.currentTimeMillis();
                e.getValue().write(e.getKey().store.dir());
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't write the shutdown check for " + e.getKey().name
                        + "; approved runs there won't apply at the next start", ex);
            }
        }
    }
}
