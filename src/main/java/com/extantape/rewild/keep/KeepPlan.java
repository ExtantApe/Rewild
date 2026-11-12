package com.extantape.rewild.keep;

import com.extantape.rewild.config.RegenConfig;
import com.extantape.rewild.region.KeepMask;
import com.extantape.rewild.region.Merge;
import com.extantape.rewild.region.Nbt;
import com.extantape.rewild.store.ChunkRecord;

import java.io.IOException;
import java.util.List;

/**
 * Works out exactly which blocks of a chunk stay when it rewilds: everything a player placed or filled, every block of
 * a type admins listed in keep-blocks, every block inside an area kept by an admin or a respected plugin, and what holds
 * each of those up. Pure logic over saved data, so it runs with the server stopped and in tests.
 */
public final class KeepPlan {
    private KeepPlan() {}

    /** A box in world coordinates, kept by an admin or a respected plugin. */
    public record Area(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String reason) {
        public Area {
            int ax = Math.min(minX, maxX), bx = Math.max(minX, maxX);
            int ay = Math.min(minY, maxY), by = Math.max(minY, maxY);
            int az = Math.min(minZ, maxZ), bz = Math.max(minZ, maxZ);
            minX = ax; maxX = bx; minY = ay; maxY = by; minZ = az; maxZ = bz;
        }

        public boolean touchesChunk(int cx, int cz) {
            return maxX >= cx * 16 && minX <= cx * 16 + 15 && maxZ >= cz * 16 && minZ <= cz * 16 + 15;
        }

        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    /**
     * The mask for one chunk. {@code cutY} is Integer.MIN_VALUE for a whole-chunk rewild, or the height from which a
     * below-ground repair leaves everything alone.
     */
    public static KeepMask build(Nbt.Compound real, ChunkRecord record, List<Area> areas, List<String> keepBlocks, int cutY)
            throws IOException {
        int[] range = blockRange(real);
        KeepMask mask = new KeepMask(range[0], range[1] - range[0]);
        int cx = real.getInt("xPos", 0), cz = real.getInt("zPos", 0);
        if (cutY != Integer.MIN_VALUE) mask.keepFrom(cutY);
        if (record != null) {
            for (long p : record.placed.keySet()) mask.keep(ChunkRecord.posX(p), ChunkRecord.posY(p), ChunkRecord.posZ(p));
            for (long p : record.filledContainers) mask.keep(ChunkRecord.posX(p), ChunkRecord.posY(p), ChunkRecord.posZ(p));
        }
        for (Area a : areas) {
            if (a.touchesChunk(cx, cz)) mask.keepBox(cx, cz, a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ());
        }
        KeepMask.Blocks blocks = Merge.blocks(real);
        if (!keepBlocks.isEmpty()) {
            mask.keepMatching(blocks, name -> RegenConfig.matchesAny(keepBlocks, materialName(name)));
        }
        mask.addSupports(blocks);
        return mask;
    }

    /** "minecraft:dirt_path" as the material name config.yml uses, "DIRT_PATH". */
    static String materialName(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        return n.toUpperCase(java.util.Locale.ROOT);
    }

    /** The lowest block y and one past the highest, from the sections that hold blocks. */
    static int[] blockRange(Nbt.Compound real) throws IOException {
        Nbt.NbtList sections = real.getList("sections");
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        if (sections != null) for (Nbt.Compound s : sections.compounds()) {
            if (s.getCompound("block_states") == null) continue;
            int y = s.getInt("Y", 0);
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        if (lo == Integer.MAX_VALUE) throw new IOException("chunk has no block sections");
        return new int[]{lo * 16, (hi + 1) * 16};
    }
}
