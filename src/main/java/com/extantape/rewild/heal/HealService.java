package com.extantape.rewild.heal;

import com.extantape.rewild.config.RegenConfig;
import com.extantape.rewild.keep.KeepPlan;
import com.extantape.rewild.keep.Keepers;
import com.extantape.rewild.store.ChunkRecord;
import com.extantape.rewild.store.WorldState;
import com.extantape.rewild.store.WorldStore;
import com.extantape.rewild.track.Tracking;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Gradual healing (0.4.0, D224). Records the natural blocks an explosion destroys, when the hole qualifies to heal on
 * its own ({@link HealCheck}, plus claims, admin-protected areas, kept areas and respawn points), and puts them back a
 * step at a time ({@link HealPlan}): about one step a day, bottom up, waiting while a player is near and while the
 * chunks aren't loaded. A step only fills air, water and other blocks that anything may replace, plus the dirt or
 * sapling an earlier step of the same hole laid, so it never overwrites what a player built since. Dry run logs each
 * step instead of placing it.
 */
public final class HealService implements org.bukkit.event.Listener {
    private static final long CHECK_TICKS = 20L * 60;
    private static final long KEEPERS_TTL_MS = 5 * 60 * 1000;

    private final Plugin plugin;
    private final Tracking tracking;
    private final Path dir;
    private final Map<String, HealJob> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, Keepers.Result> keepersCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> keepersAt = new ConcurrentHashMap<>();
    private BukkitTask task;

    public HealService(Plugin plugin, Tracking tracking) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.dir = plugin.getDataFolder().toPath().resolve("healing");
    }

    public void start() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, CHECK_TICKS, CHECK_TICKS);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public Collection<HealJob> all() {
        return List.copyOf(jobs.values());
    }

    private void load() {
        jobs.clear();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir, 2)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt")).toList()) {
                try {
                    HealJob j = HealJob.load(f);
                    jobs.put(j.id, j);
                } catch (IOException e) {
                    plugin.getLogger().warning("Healing file skipped, left in place for a look: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't read the healing folder", e);
        }
    }

    private Path file(HealJob j) {
        return dir.resolve(j.world.toString()).resolve(j.id + ".txt");
    }

    // ----- Recording.

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        explosion(e.getLocation().getWorld(), e.blockList());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        explosion(e.getBlock().getWorld(), e.blockList());
    }

    /** Called while an explosion's blocks are still in place (the event, at MONITOR). */
    public void explosion(World w, List<Block> blocks) {
        RegenConfig cfg = tracking.config();
        if (!cfg.healExplosions || blocks.isEmpty()) return;
        WorldState s = tracking.state(w);
        if (s == null || !s.healthy()) return; // only enabled, healthy worlds
        List<HealPlan.Cell> cells = new ArrayList<>();
        for (Block b : blocks) {
            if (b.getType().isAir()) continue;
            if (b.getState(false) instanceof Container) return; // a chest, barrel or furnace: not plain nature
            cells.add(new HealPlan.Cell(b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString()));
        }
        if (cells.isEmpty()) return;
        if (com.extantape.rewild.keep.MagicKeeps.spellChanged(blocks)) return; // Magic puts its own spells back
        WorldStore store = s.store;
        String why = HealCheck.why(cells, cfg.healBuildRadius, store::get);
        if (why == null) why = kept(w, s, cells);
        int[] c = center(cells);
        if (why != null) {
            plugin.getLogger().fine("Explosion hole at " + c[0] + " " + c[1] + " " + c[2] + " in " + w.getName() + " won't heal on its own: " + why + ".");
            return;
        }
        long now = System.currentTimeMillis();
        String id = "h" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(now)) + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000));
        HealJob j = new HealJob(id, w.getUID(), now, now + cfg.healStepHours * 3_600_000L, 0, cells);
        try {
            j.save(file(j));
            jobs.put(j.id, j);
            plugin.getLogger().info("Explosion hole at " + c[0] + " " + c[1] + " " + c[2] + " in " + w.getName() + " (" + cells.size()
                + " blocks) will heal over about " + cfg.healDays + " days, starting in " + cfg.healStepHours + " hours. Healing " + id + ".");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't save healing for the explosion at " + c[0] + " " + c[1] + " " + c[2], e);
        }
    }

    /** Claims, kept areas, admin-protected areas and respawn points keep a hole from healing on its own. */
    private String kept(World w, WorldState s, List<HealPlan.Cell> cells) {
        Keepers.Result k = keepers(w);
        if (k.claimsProblem != null) return "claims can't be checked (" + k.claimsProblem + ")";
        if (k.externalProblem != null) return "another plugin's keeps can't be checked (" + k.externalProblem + ")";
        for (HealPlan.Cell c : cells) {
            int cx = c.x() >> 4, cz = c.z() >> 4;
            for (int[] b : k.claims) if (cx >= b[0] - 1 && cx <= b[2] + 1 && cz >= b[1] - 1 && cz <= b[3] + 1) return "it's in or next to a claim";
            if (k.external.containsKey(ChunkRecord.key(cx, cz))) return "another plugin keeps that land";
            if (k.respawnChunks.contains(ChunkRecord.key(cx, cz))) return "it's next to a player's respawn point";
            for (WorldStore.ProtectedArea a : s.store.protectedAreas()) if (a.contains(cx, cz)) return "an admin protected that land";
            for (KeepPlan.Area a : k.areas)
                if (a.world().equals(w.getName()) && c.x() >= a.minX() && c.x() <= a.maxX() && c.y() >= a.minY() && c.y() <= a.maxY() && c.z() >= a.minZ() && c.z() <= a.maxZ())
                    return "it's in a kept area (" + a.reason() + ")";
        }
        return null;
    }

    private Keepers.Result keepers(World w) {
        long now = System.currentTimeMillis();
        Long at = keepersAt.get(w.getUID());
        if (at == null || now - at > KEEPERS_TTL_MS) {
            keepersCache.put(w.getUID(), tracking.keepers(w));
            keepersAt.put(w.getUID(), now);
        }
        return keepersCache.get(w.getUID());
    }

    // ----- Healing.

    void tick() {
        long now = System.currentTimeMillis();
        for (HealJob j : List.copyOf(jobs.values())) {
            if (now < j.next) continue;
            World w = Bukkit.getWorld(j.world);
            if (w == null || tracking.state(w) == null) continue; // world gone or no longer enabled: wait
            step(j, w, now, false);
        }
    }

    /** Runs the job's next step if it can. force skips the wait for the day (for tests and admins); never the player check. */
    public String step(HealJob j, World w, long now, boolean force) {
        RegenConfig cfg = tracking.config();
        List<List<HealPlan.Placement>> steps = HealPlan.steps(j.cells, cfg.healDays, m -> RegenConfig.matchesAny(cfg.world(w.getName()).damageIgnore(), m.toUpperCase(Locale.ROOT)));
        if (j.done >= steps.size()) return finish(j, w);
        if (!force && now < j.next) return "not due yet";
        int[] b = j.bounds();
        for (int cx = b[0] >> 4; cx <= b[2] >> 4; cx++)
            for (int cz = b[1] >> 4; cz <= b[3] >> 4; cz++)
                if (!w.isChunkLoaded(cx, cz)) return "waiting for its chunks to load";
        WorldState s = tracking.state(w);
        if (s == null || !s.healthy()) return "waiting: " + (s == null ? "the world isn't enabled" : s.problem);
        Keepers.Result k = keepers(w);
        if (k.claimsProblem != null || k.externalProblem != null) return "waiting: " + (k.claimsProblem != null ? k.claimsProblem : k.externalProblem);
        int r = cfg.healPauseRadius;
        for (Player p : w.getPlayers()) {
            var l = p.getLocation();
            if (l.getX() >= b[0] - r && l.getX() <= b[2] + r && l.getZ() >= b[1] - r && l.getZ() <= b[3] + r) return "paused: a player is nearby";
        }
        int placed = 0, blocked = 0;
        // Never fill a block a mob, pet or dropped item is in (players are already kept well away).
        var box = new org.bukkit.util.BoundingBox(b[0], w.getMinHeight(), b[1], b[2] + 1, w.getMaxHeight(), b[3] + 1);
        List<org.bukkit.util.BoundingBox> occupied = w.getNearbyEntities(box).stream().map(org.bukkit.entity.Entity::getBoundingBox).toList();
        for (HealPlan.Placement p : steps.get(j.done)) {
            Block at = w.getBlockAt(p.x(), p.y(), p.z());
            if (!replaceable(at, p.over())) { blocked++; continue; }
            var cell = org.bukkit.util.BoundingBox.of(at);
            if (occupied.stream().anyMatch(cell::overlaps)) { blocked++; continue; }
            BlockData data;
            try {
                data = Bukkit.createBlockData(p.data());
            } catch (IllegalArgumentException e) {
                blocked++;
                continue;
            }
            if (!cfg.dryRun) at.setBlockData(data, false);
            placed++;
        }
        j.done++;
        j.next = now + cfg.healStepHours * 3_600_000L;
        int[] c = j.center();
        String what = (cfg.dryRun ? "Dry run: would heal " : "Healed ") + "step " + j.done + " of " + steps.size() + " at " + c[0] + " " + c[1] + " " + c[2]
            + " in " + w.getName() + ": " + placed + " blocks" + (blocked > 0 ? ", " + blocked + " left alone because something is there now" : "") + ". Healing " + j.id + ".";
        plugin.getLogger().info(what);
        if (j.done >= steps.size()) return finish(j, w) + " " + what;
        try {
            j.save(file(j));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't save healing " + j.id, e);
        }
        return what;
    }

    /** Air, water and lava, and what anything may replace (short grass, snow layers, fire), or the block this step expects. */
    static boolean replaceable(Block at, String over) {
        Material m = at.getType();
        if (m.isAir() || at.isLiquid() || at.isReplaceable()) return true;
        if (over == null) return false;
        String here = at.getBlockData().getAsString();
        int bracket = here.indexOf('[');
        return (bracket < 0 ? here : here.substring(0, bracket)).equals(over);
    }

    private String finish(HealJob j, World w) {
        jobs.remove(j.id);
        try {
            Files.deleteIfExists(file(j));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't remove the finished healing file " + j.id, e);
        }
        return "Healing " + j.id + " in " + w.getName() + " is finished.";
    }

    public boolean cancel(String id) throws IOException {
        HealJob j = jobs.remove(id);
        if (j == null) return false;
        Files.deleteIfExists(file(j));
        return true;
    }

    private static int[] center(List<HealPlan.Cell> cells) {
        return new HealJob("x", new UUID(0, 0), 0, 0, 0, cells).center();
    }
}
