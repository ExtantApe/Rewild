package com.extantape.wildernessregen.scan;

import com.extantape.wildernessregen.config.RegenConfig.WorldSettings;
import com.extantape.wildernessregen.region.ChunkFacts;
import com.extantape.wildernessregen.store.ChunkRecord;
import com.extantape.wildernessregen.store.WorldStore.ProtectedArea;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, for each chunk, whether it's kept, waiting, unknown, or queued for a reset. Pure logic over plain data, so
 * it runs off the main thread and in tests. When in doubt it keeps the chunk.
 */
public final class Scanner {
    public static final long DAY = 24L * 60 * 60 * 1000;

    public enum Kind { QUEUED, UNDERGROUND, KEPT, WAITING, UNKNOWN, UNDAMAGED }

    /** Reasons a chunk is kept. Only the "surface" ones still allow a repair below ground. */
    public enum Category {
        CLAIM(false), PROTECTED(false), SPAWN(false), RESPAWN(false), PLAYER(false), END_ISLAND(false),
        EXTERNAL(false), STRUCTURE(false), UNREADABLE(false),
        INTENT(true), PATH(true), CONTAINER(true), ENTITY(true), BUFFER(true);

        public final boolean surface;

        Category(boolean surface) {
            this.surface = surface;
        }
    }

    /** A reason, with the lowest block height it applies to, or Integer.MIN_VALUE if it covers the whole column. */
    public record Reason(Category category, String text, int minY) {
        static Reason whole(Category c, String text) {
            return new Reason(c, text, Integer.MIN_VALUE);
        }
    }

    public record Verdict(int x, int z, Kind kind, List<String> reasons, int cutY) {
        public boolean queued() {
            return kind == Kind.QUEUED || kind == Kind.UNDERGROUND;
        }
    }

    /** Everything about the world beyond the tracker that a decision needs, gathered on the main thread. */
    public static final class Context {
        public WorldSettings settings;
        public String environment = "NORMAL";
        public long now;
        public int epoch;
        public int minY = -64;
        public int maxChunks = 200;
        public int minGroupSize = 1;
        public final List<int[]> claims = new ArrayList<>();
        public final List<ProtectedArea> protectedAreas = new ArrayList<>();
        public int spawnX, spawnZ;
        public final Set<Long> respawnChunks = new HashSet<>();
        public final Set<Long> playerChunks = new HashSet<>();
        /** Chunks kept by other plugins or live checks, such as graves, portals and loaded pets. */
        public final Map<Long, List<String>> external = new HashMap<>();
        public IntentRules intent;
    }

    private final Context ctx;
    private final Map<Long, ChunkRecord> records;
    private final Map<Long, ChunkFacts> facts;
    private Map<Long, Integer> pathComponentSize;

    public Scanner(Context ctx, Map<Long, ChunkRecord> records, Map<Long, ChunkFacts> facts) {
        this.ctx = ctx;
        this.records = records;
        this.facts = facts;
    }

    /** Chunks worth reading saved data for: known, damaged chunks. */
    public static Set<Long> candidates(Map<Long, ChunkRecord> records, Context ctx) {
        Set<Long> out = new HashSet<>();
        for (ChunkRecord r : records.values()) if (r.known && r.epoch == ctx.epoch && damaged(r, ctx.settings, ctx.environment)) out.add(r.key());
        return out;
    }

    /** Candidates plus their neighbours within the buffer, whose saved data can keep a candidate too. */
    public static Set<Long> factsNeeded(Set<Long> candidates, Context ctx) {
        int b = Math.max(1, ctx.settings.bufferChunks());
        Set<Long> out = new HashSet<>();
        for (long k : candidates) {
            int x = ChunkRecord.keyX(k), z = ChunkRecord.keyZ(k);
            for (int dx = -b; dx <= b; dx++) for (int dz = -b; dz <= b; dz++) out.add(ChunkRecord.key(x + dx, z + dz));
        }
        return out;
    }

    /**
     * A reset chunk only blends if its neighbours came from the same generator. A neighbour generated before tracking
     * began, or under an older generator, might not have. Returns why not, or null if every neighbour is fine.
     */
    private String seamProblem(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long k = ChunkRecord.key(x + dx, z + dz);
                ChunkRecord n = records.get(k);
                if (n != null && n.known && n.epoch == ctx.epoch) continue;
                ChunkFacts f = facts.get(k);
                if (n == null && f != null && f.ok() && !f.present) continue;
                return "next to chunk " + (x + dx) + ", " + (z + dz) + (n != null && n.known
                        ? ", generated before the world generator changed" : f == null || !f.ok()
                        ? ", whose saved data couldn't be checked" : ", generated before tracking began") + ", so the seam can't be trusted";
            }
        }
        return null;
    }

    static boolean damaged(ChunkRecord r, WorldSettings s, String env) {
        return r.removed() >= s.minRemovedBlocks() || r.explosionBlocks >= s.minExplosionBlocks()
                || (undergroundAllowed(s, env) && r.removed() >= s.undergroundMinRemovedBlocks());
    }

    static boolean undergroundAllowed(WorldSettings s, String env) {
        return s.undergroundEnabled() && "NORMAL".equals(env);
    }

    /** Verdicts for every candidate, with the run cap applied. */
    public Map<Long, Verdict> evaluateAll() {
        Map<Long, Verdict> out = new LinkedHashMap<>();
        for (long k : candidates(records, ctx)) out.put(k, evaluate(ChunkRecord.keyX(k), ChunkRecord.keyZ(k)));
        applyCap(out);
        return out;
    }

    /** The verdict for one chunk, without the run cap. Works for any chunk, tracked or not. */
    public Verdict evaluate(int x, int z) {
        long key = ChunkRecord.key(x, z);
        ChunkRecord r = records.get(key);
        WorldSettings s = ctx.settings;
        List<String> why = new ArrayList<>();
        if (r == null || !r.known) {
            why.add(r == null ? "no tracking history: it was generated before tracking began, or never changed"
                    : "generated before tracking began, so natural and player-placed blocks can't be told apart");
            return new Verdict(x, z, Kind.UNKNOWN, why, 0);
        }
        if (r.epoch != ctx.epoch) {
            why.add("generated before the world generator changed, so a reset wouldn't blend");
            return new Verdict(x, z, Kind.UNKNOWN, why, 0);
        }
        List<Reason> keep = keepReasons(x, z);
        boolean fullDamage = r.removed() >= s.minRemovedBlocks() || r.explosionBlocks >= s.minExplosionBlocks();
        String seam = keep.isEmpty() && fullDamage ? seamProblem(x, z) : null;
        if (seam != null) {
            why.add(seam);
            return new Verdict(x, z, Kind.KEPT, why, 0);
        }
        if (keep.isEmpty() && fullDamage) {
            ChunkFacts own = facts.get(key);
            if (own == null || !own.ok() || !own.present) {
                why.add(own == null || !own.ok() ? "its saved data couldn't be read" : "it hasn't been saved to the world's files yet; it's checked again after the next save");
                return new Verdict(x, z, Kind.KEPT, why, 0);
            }
            List<String> waiting = timing(r.lastChange, r.lastVisit, r.lastReset);
            if (waiting.isEmpty()) {
                why.add(damageText(r));
                return new Verdict(x, z, Kind.QUEUED, why, 0);
            }
            return new Verdict(x, z, Kind.WAITING, waiting, 0);
        }
        // Kept, or not damaged enough for a full reset. A repair below ground may still apply.
        Verdict under = underground(r, keep);
        if (under != null && under.kind() == Kind.UNDERGROUND) return under;
        if (!keep.isEmpty()) {
            for (Reason reason : keep) why.add(reason.text());
            if (under != null) why.addAll(under.reasons());
            return new Verdict(x, z, Kind.KEPT, why, 0);
        }
        why.add("not damaged enough: " + damageText(r) + "; needs " + s.minRemovedBlocks() + " removed or a crater of "
                + s.minExplosionBlocks());
        if (under != null) why.addAll(under.reasons());
        return new Verdict(x, z, Kind.UNDAMAGED, why, 0);
    }

    private String damageText(ChunkRecord r) {
        return r.removed() + " blocks removed by players" + (r.explosionBlocks > 0 ? ", " + r.explosionBlocks + " by explosions" : "");
    }

    private List<String> timing(long lastChange, long lastVisit, long lastReset) {
        WorldSettings s = ctx.settings;
        List<String> out = new ArrayList<>();
        long now = ctx.now;
        if (now - lastChange < s.daysWithoutChanges() * DAY) {
            out.add("changed " + days(now - lastChange) + " ago; waits for " + s.daysWithoutChanges() + " days without changes");
        }
        if (now - lastVisit < s.daysWithoutVisitors() * DAY) {
            out.add("visited " + days(now - lastVisit) + " ago; waits for " + s.daysWithoutVisitors() + " days without visitors");
        }
        if (lastReset > 0 && now - lastReset < s.daysSinceLastReset() * DAY) {
            out.add("reset " + days(now - lastReset) + " ago; waits " + s.daysSinceLastReset() + " days between resets");
        }
        return out;
    }

    static String days(long ms) {
        long d = ms / DAY;
        if (d >= 1) return d + (d == 1 ? " day" : " days");
        long h = ms / (60 * 60 * 1000);
        return h >= 1 ? h + (h == 1 ? " hour" : " hours") : "under an hour";
    }

    /** Null if a below-ground repair doesn't apply at all; otherwise UNDERGROUND, or a verdict holding the reason not. */
    private Verdict underground(ChunkRecord r, List<Reason> keep) {
        WorldSettings s = ctx.settings;
        if (!undergroundAllowed(s, ctx.environment)) return null;
        if (r.removed() < s.undergroundMinRemovedBlocks()) return null;
        List<String> no = new ArrayList<>();
        for (Reason k : keep) {
            if (!k.category().surface) return null;
        }
        ChunkFacts f = facts.get(r.key());
        if (f == null || !f.ok() || !f.present || f.floorMinY == Integer.MIN_VALUE) {
            return new Verdict(r.x, r.z, Kind.KEPT, List.of("below ground: its saved data couldn't be read, so no cut can be placed"), 0);
        }
        int cut = Math.floorDiv(f.floorMinY - s.undergroundMarginBlocks(), 16) * 16;
        int cutSection = cut >> 4;
        if (cut <= ctx.minY) {
            return new Verdict(r.x, r.z, Kind.KEPT, List.of("below ground: the surface dips too low to leave room under it"), 0);
        }
        int removedBelow = r.removedBelowSection(cutSection);
        if (removedBelow < s.undergroundMinRemovedBlocks()) {
            no.add("below ground: " + removedBelow + " blocks removed under y=" + cut + "; needs " + s.undergroundMinRemovedBlocks());
            return new Verdict(r.x, r.z, Kind.KEPT, no, cut);
        }
        for (Reason k : keep) {
            if (k.category() != Category.BUFFER && k.minY() < cut) {
                no.add("below ground: " + k.text() + " reaches under the cut at y=" + cut);
                return new Verdict(r.x, r.z, Kind.KEPT, no, cut);
            }
        }
        String seam = seamProblem(r.x, r.z);
        if (seam != null) {
            no.add("below ground: " + seam);
            return new Verdict(r.x, r.z, Kind.KEPT, no, cut);
        }
        int scoreBelow = 0;
        for (var e : r.placed.entrySet()) {
            if (ChunkRecord.posY(e.getKey()) < cut) scoreBelow += ctx.intent.score(e.getValue());
        }
        if (scoreBelow >= s.intentScore()) {
            no.add("below ground: player-placed blocks under y=" + cut + " score " + scoreBelow);
            return new Verdict(r.x, r.z, Kind.KEPT, no, cut);
        }
        List<String> waiting = timing(r.lastChangeBelowSection(cutSection), r.lastVisitBelowSection(cutSection), r.lastReset);
        if (!waiting.isEmpty()) {
            List<String> w = new ArrayList<>();
            for (String t : waiting) w.add("below ground: " + t);
            return new Verdict(r.x, r.z, Kind.KEPT, w, cut);
        }
        List<String> yes = new ArrayList<>();
        yes.add(removedBelow + " blocks removed under y=" + cut + "; only the ground below y=" + cut + " resets");
        for (Reason k : keep) yes.add("surface kept: " + k.text());
        return new Verdict(r.x, r.z, Kind.UNDERGROUND, yes, cut);
    }

    /** Every reason the chunk is kept, including the buffer around kept neighbours. */
    public List<Reason> keepReasons(int x, int z) {
        List<Reason> out = new ArrayList<>(directReasons(x, z, true));
        int b = ctx.settings.bufferChunks();
        if (out.isEmpty() && b > 0) {
            outer:
            for (int dx = -b; dx <= b; dx++) {
                for (int dz = -b; dz <= b; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    List<Reason> n = directReasons(x + dx, z + dz, false);
                    if (!n.isEmpty()) {
                        out.add(new Reason(Category.BUFFER, "next to a kept chunk at " + (x + dx) + ", " + (z + dz) + " ("
                                + n.get(0).text() + ")", Integer.MIN_VALUE));
                        break outer;
                    }
                }
            }
        }
        return out;
    }

    /** Reasons that apply to the chunk itself. Claims and spawn carry their own radius instead of the buffer. */
    private List<Reason> directReasons(int x, int z, boolean withAreas) {
        List<Reason> out = new ArrayList<>();
        WorldSettings s = ctx.settings;
        long key = ChunkRecord.key(x, z);
        if (withAreas) {
            int cb = s.claimBufferChunks();
            for (int[] c : ctx.claims) {
                if (x >= c[0] - cb && x <= c[2] + cb && z >= c[1] - cb && z <= c[3] + cb) {
                    out.add(Reason.whole(Category.CLAIM, x >= c[0] && x <= c[2] && z >= c[1] && z <= c[3] ? "inside a claim" : "within " + cb + " chunk of a claim"));
                    break;
                }
            }
            int sr = s.spawnRadiusChunks();
            if (Math.abs(x - ctx.spawnX) <= sr && Math.abs(z - ctx.spawnZ) <= sr) out.add(Reason.whole(Category.SPAWN, "within " + sr + " chunks of world spawn"));
            int rr = s.respawnRadiusChunks();
            for (long rc : ctx.respawnChunks) {
                if (Math.abs(x - ChunkRecord.keyX(rc)) <= rr && Math.abs(z - ChunkRecord.keyZ(rc)) <= rr) {
                    out.add(Reason.whole(Category.RESPAWN, "near a player's bed or respawn point"));
                    break;
                }
            }
            int vr = s.visitRadiusChunks();
            for (long pc : ctx.playerChunks) {
                if (Math.abs(x - ChunkRecord.keyX(pc)) <= vr && Math.abs(z - ChunkRecord.keyZ(pc)) <= vr) {
                    out.add(Reason.whole(Category.PLAYER, "a player is nearby right now"));
                    break;
                }
            }
            if ("THE_END".equals(ctx.environment)) {
                int rb = s.endMainIslandRadiusBlocks();
                long nx = Math.max(Math.abs(x * 16L), Math.abs(x * 16L + 15)), nz = Math.max(Math.abs(z * 16L), Math.abs(z * 16L + 15));
                long cx = Math.min(Math.abs(x * 16L), Math.abs(x * 16L + 15)), cz = Math.min(Math.abs(z * 16L), Math.abs(z * 16L + 15));
                if (cx * cx + cz * cz <= (long) rb * rb || nx * nx + nz * nz <= (long) rb * rb) {
                    out.add(Reason.whole(Category.END_ISLAND, "on or near the End's main island"));
                }
            }
        }
        for (ProtectedArea a : ctx.protectedAreas) {
            if (a.contains(x, z)) {
                out.add(Reason.whole(Category.PROTECTED, "protected by an admin" + (a.note().isBlank() ? "" : ": " + a.note())));
                break;
            }
        }
        List<String> ext = ctx.external.get(key);
        if (ext != null) for (String e : ext) out.add(Reason.whole(Category.EXTERNAL, e));

        ChunkFacts f = facts.get(key);
        if (f != null) {
            if (!f.ok()) out.add(Reason.whole(Category.UNREADABLE, "its saved data couldn't be read (" + f.error + ")"));
            for (String st : f.structures) {
                if (s.structures().contains(st)) {
                    out.add(Reason.whole(Category.STRUCTURE, "part of a " + st.replace("minecraft:", "")));
                    break;
                }
            }
            for (ChunkFacts.KeptEntity e : f.keptEntities) out.add(new Reason(Category.ENTITY, e.reason(), (int) Math.floor(e.y())));
        }

        ChunkRecord r = records.get(key);
        if (r != null) {
            int score = 0;
            int scoreMinY = Integer.MAX_VALUE;
            String keeper = null;
            int keeperY = 0;
            for (var e : r.placed.entrySet()) {
                String m = e.getValue();
                int y = ChunkRecord.posY(e.getKey());
                if (ctx.intent.keepsAlone(m)) {
                    if (keeper == null || y < keeperY) {
                        keeper = m;
                        keeperY = y;
                    }
                } else {
                    int sc = ctx.intent.score(m);
                    if (sc > 0) {
                        score += sc;
                        scoreMinY = Math.min(scoreMinY, y);
                    }
                }
            }
            if (keeper != null) out.add(new Reason(Category.INTENT, "player-placed " + keeper.toLowerCase(java.util.Locale.ROOT).replace('_', ' '), keeperY));
            if (score >= s.intentScore()) out.add(new Reason(Category.INTENT, "player-built blocks score " + score + " (keeps at " + s.intentScore() + ")", scoreMinY));
            if (!r.filledContainers.isEmpty()) {
                int minY = Integer.MAX_VALUE;
                for (long p : r.filledContainers) minY = Math.min(minY, ChunkRecord.posY(p));
                out.add(new Reason(Category.CONTAINER, "a container a player put items in", minY));
            }
            int pathY = pathKeepY(r);
            if (pathY != Integer.MAX_VALUE) out.add(new Reason(Category.PATH, "a player-made path", pathY));
        }
        return out;
    }

    /** Lowest y of a path block in this chunk that belongs to a long enough connected path, or MAX_VALUE. */
    private int pathKeepY(ChunkRecord r) {
        if (pathComponentSize == null) pathComponentSize = pathComponents();
        int min = Integer.MAX_VALUE;
        for (var e : r.placed.entrySet()) {
            if (!IntentRules.PATH.equals(e.getValue())) continue;
            long world = worldPos(r.x, r.z, e.getKey());
            Integer size = pathComponentSize.get(world);
            if (size != null && size >= ctx.settings.pathMinConnected()) min = Math.min(min, ChunkRecord.posY(e.getKey()));
        }
        return min;
    }

    private static long worldPos(int cx, int cz, long local) {
        return packWorld(cx * 16 + ChunkRecord.posX(local), ChunkRecord.posY(local), cz * 16 + ChunkRecord.posZ(local));
    }

    static long packWorld(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Groups every recorded player path block into connected paths, across chunk borders, and sizes each group. */
    private Map<Long, Integer> pathComponents() {
        Set<Long> all = new HashSet<>();
        Map<Long, int[]> coords = new HashMap<>();
        for (ChunkRecord r : records.values()) {
            for (var e : r.placed.entrySet()) {
                if (!IntentRules.PATH.equals(e.getValue())) continue;
                int wx = r.x * 16 + ChunkRecord.posX(e.getKey()), wy = ChunkRecord.posY(e.getKey()), wz = r.z * 16 + ChunkRecord.posZ(e.getKey());
                long p = packWorld(wx, wy, wz);
                all.add(p);
                coords.put(p, new int[]{wx, wy, wz});
            }
        }
        Map<Long, Integer> size = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        for (long start : all) {
            if (seen.contains(start)) continue;
            List<Long> group = new ArrayList<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                long p = queue.poll();
                group.add(p);
                int[] c = coords.get(p);
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    for (int dy = -1; dy <= 1; dy++) {
                        long n = packWorld(c[0] + d[0], c[1] + dy, c[2] + d[1]);
                        if (all.contains(n) && seen.add(n)) queue.add(n);
                    }
                }
            }
            for (long p : group) size.put(p, group.size());
        }
        return size;
    }

    /** Keeps the run under the cap, taking whole connected groups first, largest first. */
    private void applyCap(Map<Long, Verdict> verdicts) {
        Set<Long> queued = new HashSet<>();
        for (var e : verdicts.entrySet()) if (e.getValue().queued()) queued.add(e.getKey());
        List<List<Long>> groups = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<Long> ordered = new ArrayList<>(queued);
        ordered.sort(Comparator.naturalOrder());
        for (long start : ordered) {
            if (!seen.add(start)) continue;
            List<Long> group = new ArrayList<>();
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(start);
            while (!q.isEmpty()) {
                long k = q.poll();
                group.add(k);
                int x = ChunkRecord.keyX(k), z = ChunkRecord.keyZ(k);
                for (long n : new long[]{ChunkRecord.key(x + 1, z), ChunkRecord.key(x - 1, z), ChunkRecord.key(x, z + 1), ChunkRecord.key(x, z - 1)}) {
                    if (queued.contains(n) && seen.add(n)) q.add(n);
                }
            }
            groups.add(group);
        }
        int min = ctx.minGroupSize;
        groups.sort(Comparator.<List<Long>>comparingInt(g -> g.size() >= min ? 0 : 1).thenComparing(g -> -g.size()));
        int room = ctx.maxChunks;
        for (List<Long> g : groups) {
            for (long k : g) {
                if (room > 0) {
                    room--;
                    continue;
                }
                Verdict v = verdicts.get(k);
                List<String> why = new ArrayList<>(v.reasons());
                why.add(0, "ready, but this run is already at its cap of " + ctx.maxChunks + " chunks");
                verdicts.put(k, new Verdict(v.x(), v.z(), Kind.WAITING, why, v.cutY()));
            }
        }
    }
}
