package com.extantape.rewild.heal;

import com.extantape.rewild.store.ChunkRecord;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Whether an explosion hole may heal on its own (D224: "Explosion holes (creepers, TNT) in natural land with no builds
 * nearby heal on their own without approval; everything else still needs an admin's approval"). Fails closed: any doubt
 * is a reason, and the hole waits for an admin like any other damage.
 */
public final class HealCheck {
    private HealCheck() {}

    /**
     * Null when the hole may heal on its own, otherwise why not.
     *
     * @param record the tracker's record of a chunk (null when it has none)
     */
    public static String why(List<HealPlan.Cell> cells, int buildRadius, BiFunction<Integer, Integer, ChunkRecord> record) {
        if (cells.isEmpty()) return "nothing natural was destroyed";
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (HealPlan.Cell c : cells) {
            ChunkRecord r = record.apply(c.x() >> 4, c.z() >> 4);
            if (r == null || !r.known) return "it reached land generated before Rewild was tracking it, so Rewild can't tell what's natural";
            if (r.placed.containsKey(ChunkRecord.pos(c.x() & 15, c.y(), c.z() & 15))) return "it destroyed blocks a player placed";
            minX = Math.min(minX, c.x()); minY = Math.min(minY, c.y()); minZ = Math.min(minZ, c.z());
            maxX = Math.max(maxX, c.x()); maxY = Math.max(maxY, c.y()); maxZ = Math.max(maxZ, c.z());
        }
        minX -= buildRadius; minY -= buildRadius; minZ -= buildRadius;
        maxX += buildRadius; maxY += buildRadius; maxZ += buildRadius;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++)
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                ChunkRecord r = record.apply(cx, cz);
                if (r == null) continue;
                for (long p : r.placed.keySet()) if (inside(r, p, minX, minY, minZ, maxX, maxY, maxZ)) return "a player's build is within " + buildRadius + " blocks";
                for (long p : r.filledContainers) if (inside(r, p, minX, minY, minZ, maxX, maxY, maxZ)) return "a container a player uses is within " + buildRadius + " blocks";
            }
        return null;
    }

    private static boolean inside(ChunkRecord r, long p, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int x = (r.x << 4) + ChunkRecord.posX(p), y = ChunkRecord.posY(p), z = (r.z << 4) + ChunkRecord.posZ(p);
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
