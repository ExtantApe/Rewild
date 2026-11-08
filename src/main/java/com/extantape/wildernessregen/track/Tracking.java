package com.extantape.wildernessregen.track;

import com.extantape.wildernessregen.config.RegenConfig;
import com.extantape.wildernessregen.store.ChunkRecord;
import com.extantape.wildernessregen.store.Fingerprint;
import com.extantape.wildernessregen.store.WorldState;
import com.extantape.wildernessregen.store.WorldStore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Owns the tracking store of every enabled world: opens them, saves them every second, records visits, notices
 * tracking gaps and generator changes, and reports health.
 */
public final class Tracking {
    /** Game ticks the world may run beyond the last heartbeat before it counts as running untracked (5 minutes). */
    static final long GAP_TICKS = 20L * 60 * 5;
    static final long VISIT_REFRESH_MS = 5 * 60 * 1000;

    private final Plugin plugin;
    private final Path worldsDir;
    private final Map<UUID, WorldState> states = new ConcurrentHashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private RegenConfig config;

    public Tracking(Plugin plugin, RegenConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.worldsDir = plugin.getDataFolder().toPath().resolve("worlds");
    }

    public static Path storeDir(Path dataFolder, UUID uid) {
        return dataFolder.resolve("worlds").resolve(uid.toString());
    }

    public RegenConfig config() {
        return config;
    }

    public void setConfig(RegenConfig config) {
        this.config = config;
    }

    /** Opens stores for already-open states (from startup) or fresh ones, and starts the timers. */
    public void start(Map<UUID, WorldStore> preopened) {
        for (World w : Bukkit.getWorlds()) open(w, preopened.remove(w.getUID()));
        for (WorldStore leftover : preopened.values()) {
            try {
                leftover.close();
            } catch (IOException ignored) {
            }
        }
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, 20, 20));
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::compactAll, 20 * 600, 20 * 600));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::heartbeat, 20 * 60, 20 * 60));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::recordVisits, 40, 200));
    }

    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        heartbeat();
        for (WorldState s : states.values()) {
            try {
                s.store.compact();
                s.store.close();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't save tracking for " + s.name, e);
            }
        }
        states.clear();
    }

    public WorldState state(World w) {
        return w == null ? null : states.get(w.getUID());
    }

    public Collection<WorldState> states() {
        return states.values();
    }

    /** Starts tracking a world if it's enabled. Safe to call twice. */
    public void open(World w, WorldStore preopened) {
        if (states.containsKey(w.getUID())) return;
        if (!config.world(w.getName()).enabled()) return;
        Path dim = dimensionFolder(w);
        WorldStore store = preopened;
        try {
            if (store == null) store = WorldStore.open(storeDir(plugin.getDataFolder().toPath(), w.getUID()));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't open tracking data for world " + w.getName() + "; nothing there will reset", e);
            return;
        }
        WorldState s = new WorldState(w.getUID(), w.getName(), store, dim, w.getEnvironment().name(), w.getMinHeight());
        states.put(w.getUID(), s);
        try {
            Map<String, String> meta = new HashMap<>();
            meta.put("name", w.getName());
            meta.put("key", w.getKey().toString());
            meta.put("environment", s.environment);
            meta.put("dimension", dim.toAbsolutePath().normalize().toString());
            meta.put("serverRoot", Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize().toString());
            meta.put("minY", Integer.toString(w.getMinHeight()));
            Path packs = Fingerprint.findDatapacks(Bukkit.getWorlds().get(0).getWorldFolder().toPath(), Bukkit.getWorldContainer().toPath());
            meta.put("datapacks", packs == null ? "" : packs.toString());
            if (store.meta("trackingSince") == null) meta.put("trackingSince", Long.toString(System.currentTimeMillis()));
            store.setMeta(meta);
            checkGap(w, s);
            checkFingerprint(w, s, packs);
            store.setMeta("lastGameTime", Long.toString(w.getGameTime()));
        } catch (IOException | RuntimeException e) {
            s.problem = "tracking data couldn't be saved: " + e.getMessage();
            plugin.getLogger().log(Level.SEVERE, "Tracking for world " + w.getName() + " isn't healthy; nothing there will reset", e);
        }
    }

    /** If the world ran without this plugin watching, nothing tracked so far can be trusted. */
    private void checkGap(World w, WorldState s) throws IOException {
        String last = s.store.meta("lastGameTime");
        if (last == null) return;
        long ran = w.getGameTime() - Long.parseLong(last);
        if (ran > GAP_TICKS || ran < 0) {
            int[] n = {0};
            s.store.editAll(r -> {
                if (r.known) n[0]++;
                r.known = false;
            });
            s.store.compact();
            String note = "the world ran for about " + (Math.abs(ran) / 20 / 60) + " minutes without tracking";
            s.store.setMeta("lastGap", System.currentTimeMillis() + " " + note);
            plugin.getLogger().warning("World " + w.getName() + ": " + note + ". " + n[0]
                    + " tracked chunks are now unknown and won't reset automatically.");
        }
    }

    private void checkFingerprint(World w, WorldState s, Path packs) throws IOException {
        Fingerprint now = fingerprint(w, packs);
        Fingerprint saved = Fingerprint.parse(s.store.meta("fingerprint"));
        int epoch = Integer.parseInt(s.store.meta("epoch", "1"));
        if (saved == null) {
            s.store.setMeta(Map.of("fingerprint", now.full(), "epoch", Integer.toString(epoch)));
        } else if (!saved.full().equals(now.full())) {
            List<String> diff = now.differences(saved);
            epoch++;
            Collection<ChunkRecord> sample = s.store.sample(10, r -> r.known);
            StringBuilder where = new StringBuilder();
            for (ChunkRecord r : sample) where.append(where.length() == 0 ? "" : "; ").append(r.x).append(", ").append(r.z);
            s.store.setMeta(Map.of("fingerprint", now.full(), "epoch", Integer.toString(epoch),
                    "generatorChanged", System.currentTimeMillis() + " " + String.join(", ", diff)));
            plugin.getLogger().warning("World " + w.getName() + ": the world generator changed (" + String.join(", ", diff)
                    + "). Resets there are stopped. Chunks generated before now won't reset, because new terrain wouldn't"
                    + " blend with them. Sample of affected chunks: " + (where.length() == 0 ? "none tracked" : where)
                    + ". After checking, run /wr approve generator " + w.getName() + " to carry on.");
        }
        s.epoch = epoch;
        String changed = s.store.meta("generatorChanged");
        if (changed != null) s.problem = "the world generator changed (" + changed.substring(changed.indexOf(' ') + 1)
                + "); run /wr approve generator " + w.getName() + " after checking";
    }

    public static Fingerprint fingerprint(World w, Path packs) throws IOException {
        List<String> names = new ArrayList<>();
        try {
            for (var p : Bukkit.getDatapackManager().getEnabledPacks()) names.add(p.getName());
        } catch (RuntimeException | LinkageError e) {
            names.add("unknown");
        }
        java.util.Collections.sort(names);
        String gen = w.getGenerator() == null ? "vanilla" : w.getGenerator().getClass().getName();
        if (w.getBiomeProvider() != null) gen += "+" + w.getBiomeProvider().getClass().getName();
        return new Fingerprint(Bukkit.getMinecraftVersion(), w.getSeed(), w.getEnvironment().name(), gen,
                String.join(",", names).replace(";", ","), Fingerprint.hashFolder(packs));
    }

    /** Accepts a changed generator: resets may run again, and only chunks generated from now on can reset. */
    public void acceptGenerator(WorldState s) throws IOException {
        s.store.setMeta("generatorChanged", null);
        s.problem = null;
    }

    /**
     * The world's dimension folder: the one holding region/, entities/ and poi/. On 26.x every world is a dimension
     * under the level folder, and the server API returns that folder. Never built from the world's name.
     */
    public static Path dimensionFolder(World w) {
        return w.getWorldFolder().toPath().toAbsolutePath().normalize();
    }

    private void flushAll() {
        for (WorldState s : states.values()) {
            try {
                s.store.flush();
            } catch (IOException e) {
                if (s.problem == null) plugin.getLogger().log(Level.SEVERE, "Tracking for " + s.name + " couldn't be saved; resets there are stopped", e);
                s.problem = "tracking data couldn't be saved: " + e.getMessage();
            }
        }
    }

    private void compactAll() {
        for (WorldState s : states.values()) {
            try {
                s.store.compact();
            } catch (IOException e) {
                s.problem = "tracking data couldn't be saved: " + e.getMessage();
            }
        }
    }

    public void heartbeat() {
        for (WorldState s : states.values()) {
            World w = Bukkit.getWorld(s.uid);
            if (w == null) continue;
            try {
                s.store.setMeta(Map.of("lastGameTime", Long.toString(w.getGameTime()), "lastHeartbeat", Long.toString(System.currentTimeMillis())));
            } catch (IOException e) {
                s.problem = "tracking data couldn't be saved: " + e.getMessage();
            }
        }
    }

    /** Marks chunks near each player as visited, by section, at most every few minutes per chunk. */
    private void recordVisits() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            WorldState s = state(p.getWorld());
            if (s == null) continue;
            // An admin looking around in spectator or creative mode doesn't count as a visitor, so inspecting an area
            // before approving a run doesn't hold it back.
            if (p.hasPermission("wildernessregen.admin") && (p.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || p.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
            int r = config.world(s.name).visitRadiusChunks();
            int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
            int sec = p.getLocation().getBlockY() >> 4;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    ChunkRecord rec = s.store.get(cx + dx, cz + dz);
                    if (rec == null) continue;
                    Long secVisit = rec.visitBySection.get(sec);
                    if (now - rec.lastVisit < VISIT_REFRESH_MS && secVisit != null && now - secVisit < VISIT_REFRESH_MS) continue;
                    s.store.editIfPresent(cx + dx, cz + dz, c -> {
                        c.lastVisit = now;
                        for (int d = -1; d <= 1; d++) c.visitBySection.put(sec + d, now);
                    });
                }
            }
        }
    }
}
