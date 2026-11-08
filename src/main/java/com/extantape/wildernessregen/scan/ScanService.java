package com.extantape.wildernessregen.scan;

import com.extantape.wildernessregen.config.RegenConfig;
import com.extantape.wildernessregen.keep.Keepers;
import com.extantape.wildernessregen.region.ChunkFacts;
import com.extantape.wildernessregen.store.ChunkRecord;
import com.extantape.wildernessregen.store.WorldState;
import com.extantape.wildernessregen.track.Tracking;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Runs scans: gathers what only the main thread can see, reads saved chunk data on a background thread, then decides.
 * Results come back on the main thread.
 */
public final class ScanService {
    public record Result(WorldState state, Scanner scanner, Map<Long, ChunkFacts> facts, Map<Long, Scanner.Verdict> verdicts,
                         String problem, long elapsedMs) {}

    private final Plugin plugin;
    private final Tracking tracking;

    public ScanService(Plugin plugin, Tracking tracking) {
        this.plugin = plugin;
        this.tracking = tracking;
    }

    /** Builds the main-thread half of a scan context. Sets {@code problem[0]} if claims or other plugins can't be read. */
    public Scanner.Context context(World w, WorldState s, String[] problem) {
        RegenConfig cfg = tracking.config();
        Scanner.Context ctx = new Scanner.Context();
        ctx.settings = cfg.world(w.getName());
        ctx.environment = w.getEnvironment().name();
        ctx.now = System.currentTimeMillis();
        ctx.epoch = s.epoch;
        ctx.minY = w.getMinHeight();
        ctx.maxChunks = cfg.maxChunks;
        ctx.minGroupSize = cfg.minGroupSize;
        ctx.intent = IntentRules.from(cfg);
        ctx.protectedAreas.addAll(s.store.protectedAreas());
        ctx.spawnX = w.getSpawnLocation().getBlockX() >> 4;
        ctx.spawnZ = w.getSpawnLocation().getBlockZ() >> 4;
        Keepers.Result k = new Keepers(cfg.claimPlugin).read(w);
        ctx.claims.addAll(k.claims);
        ctx.respawnChunks.addAll(k.respawnChunks);
        k.external.forEach((key, reasons) -> ctx.external.computeIfAbsent(key, kk -> new ArrayList<>()).addAll(reasons));
        for (Player p : w.getPlayers()) {
            ctx.playerChunks.add(ChunkRecord.key(p.getLocation().getBlockX() >> 4, p.getLocation().getBlockZ() >> 4));
        }
        if (k.claimsProblem != null) problem[0] = "claims: " + k.claimsProblem;
        else if (k.externalProblem != null) problem[0] = k.externalProblem;
        else if (!cfg.healthy()) problem[0] = "config.yml has problems: " + String.join("; ", cfg.errors());
        return ctx;
    }

    /** Scans the whole world, or just {@code only} if given, with the run cap applied to whole-world scans. */
    public CompletableFuture<Result> scan(World w, Set<Long> only) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        WorldState s = tracking.state(w);
        if (s == null) {
            out.complete(new Result(null, null, Map.of(), Map.of(), "world " + w.getName() + " isn't enabled in config.yml", 0));
            return out;
        }
        long start = System.currentTimeMillis();
        String[] problem = {null};
        Scanner.Context ctx = context(w, s, problem);
        Map<Long, ChunkRecord> records = s.store.snapshot();
        Set<Long> targets = only != null ? only : Scanner.candidates(records, ctx);
        Set<Long> needed = Scanner.factsNeeded(targets, ctx);
        // Loaded chunks: their entities in memory are newer than their saved files.
        Map<Long, List<ChunkFacts.KeptEntity>> live = new HashMap<>();
        for (long k : needed) {
            int x = ChunkRecord.keyX(k), z = ChunkRecord.keyZ(k);
            if (!w.isChunkLoaded(x, z)) continue;
            Chunk c = w.getChunkAt(x, z);
            for (Entity e : c.getEntities()) {
                String why = liveKeepReason(e);
                if (why != null) live.computeIfAbsent(k, kk -> new ArrayList<>()).add(new ChunkFacts.KeptEntity(why, e.getLocation().getY()));
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<Long, ChunkFacts> facts = new HashMap<>();
                ChunkFacts.Reader reader = new ChunkFacts.Reader(s.dimension);
                for (long k : needed) {
                    ChunkFacts f = reader.read(ChunkRecord.keyX(k), ChunkRecord.keyZ(k));
                    List<ChunkFacts.KeptEntity> l = live.get(k);
                    if (l != null) f.keptEntities.addAll(l);
                    facts.put(k, f);
                }
                Scanner scanner = new Scanner(ctx, records, facts);
                Map<Long, Scanner.Verdict> verdicts;
                if (only == null) {
                    verdicts = scanner.evaluateAll();
                } else {
                    verdicts = new HashMap<>();
                    for (long k : only) verdicts.put(k, scanner.evaluate(ChunkRecord.keyX(k), ChunkRecord.keyZ(k)));
                }
                Result r = new Result(s, scanner, facts, verdicts, problem[0] != null ? problem[0] : s.problem,
                        System.currentTimeMillis() - start);
                Bukkit.getScheduler().runTask(plugin, () -> out.complete(r));
            } catch (Throwable t) {
                Bukkit.getScheduler().runTask(plugin, () -> out.completeExceptionally(t));
            }
        });
        return out;
    }

    /** The live version of {@link ChunkFacts}' saved-entity rules. */
    static String liveKeepReason(Entity e) {
        if (e instanceof Player) return null;
        String name = e.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        if (e.customName() != null) return "named " + name;
        if (e instanceof Tameable t && t.isTamed()) return "tamed " + name;
        if (e instanceof LivingEntity l && l.isLeashed()) return name + " on a lead";
        if (e instanceof Villager v && (v.getVillagerExperience() > 0 || v.getVillagerLevel() > 1)) return "villager that has traded";
        if (e instanceof Allay) return "allay";
        if (e instanceof ArmorStand) return "armor stand";
        if (e instanceof Hanging) return name;
        for (Entity p : e.getPassengers()) {
            String r = liveKeepReason(p);
            if (r != null) return r;
        }
        return null;
    }
}
