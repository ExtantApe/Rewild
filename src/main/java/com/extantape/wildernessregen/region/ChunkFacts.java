package com.extantape.wildernessregen.region;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a chunk's saved files say about it: structures it belongs to, entities that show a player cared about them,
 * and the lowest point of its surface. Read from region files, so it works offline and off the main thread.
 *
 * <p>While the server runs, a loaded chunk's files can be a little behind. The startup step reads them again with the
 * server stopped, and that reading is the one that decides.
 */
public final class ChunkFacts {
    /** An entity that keeps its chunk, with its height so a below-ground repair can tell whether it's in the way. */
    public record KeptEntity(String reason, double y) {}

    public boolean present;
    public String error;
    public int dataVersion;
    public String status;
    public final Set<String> structures = new LinkedHashSet<>();
    public final List<KeptEntity> keptEntities = new ArrayList<>();
    /** Lowest top-of-ground height over the chunk's 256 columns, or Integer.MIN_VALUE if unknown. */
    public int floorMinY = Integer.MIN_VALUE;
    public int minSectionY = Integer.MAX_VALUE, maxSectionY = Integer.MIN_VALUE;

    public boolean ok() {
        return error == null;
    }

    /** Reads facts for chunks in one dimension folder, caching region files between calls. */
    public static final class Reader {
        private final Path dimension;
        private final Map<Path, RegionFile> cache = new HashMap<>();

        public Reader(Path dimension) {
            this.dimension = dimension;
        }

        public RegionFile region(String kind, int cx, int cz) throws IOException {
            Path p = dimension.resolve(kind).resolve(RegionFile.fileName(cx, cz));
            RegionFile r = cache.get(p);
            if (r == null) {
                r = RegionFile.load(p);
                cache.put(p, r);
            }
            return r;
        }

        public ChunkFacts read(int cx, int cz) {
            ChunkFacts f = new ChunkFacts();
            try {
                Nbt.Compound chunk = region("region", cx, cz).readNbt(RegionFile.index(cx, cz));
                if (chunk == null) return f;
                f.present = true;
                readChunk(f, chunk);
                Nbt.Compound entities = region("entities", cx, cz).readNbt(RegionFile.index(cx, cz));
                if (entities != null) readEntities(f, entities.getList("Entities"));
            } catch (IOException | RuntimeException e) {
                f.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            return f;
        }
    }

    static void readChunk(ChunkFacts f, Nbt.Compound chunk) {
        f.dataVersion = chunk.getInt("DataVersion", 0);
        f.status = chunk.getString("Status");
        Nbt.Compound structures = chunk.getCompound("structures");
        if (structures != null) {
            Nbt.Compound refs = structures.getCompound("References");
            if (refs != null) refs.forEach((k, v) -> { if (v instanceof long[] a && a.length > 0) f.structures.add(k); });
            Nbt.Compound starts = structures.getCompound("starts");
            if (starts != null) {
                starts.forEach((k, v) -> {
                    if (v instanceof Nbt.Compound c && !"INVALID".equals(c.getString("id"))) f.structures.add(k);
                });
            }
        }
        Nbt.NbtList sections = chunk.getList("sections");
        if (sections == null) return;
        int[] top = new int[256];
        java.util.Arrays.fill(top, Integer.MIN_VALUE);
        List<Nbt.Compound> list = new ArrayList<>(sections.compounds());
        list.sort((a, b) -> Integer.compare(b.getInt("Y", 0), a.getInt("Y", 0)));
        int remaining = 256;
        for (Nbt.Compound s : list) {
            int sy = s.getInt("Y", 0);
            f.minSectionY = Math.min(f.minSectionY, sy);
            f.maxSectionY = Math.max(f.maxSectionY, sy);
            if (remaining == 0) continue;
            Nbt.Compound states = s.getCompound("block_states");
            if (states == null) continue;
            String[] palette = palette(states);
            boolean[] floor = new boolean[palette.length];
            boolean anyFloor = false;
            for (int i = 0; i < palette.length; i++) anyFloor |= floor[i] = isFloor(palette[i]);
            if (!anyFloor) continue;
            long[] data = states.get("data") instanceof long[] d ? d : null;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            for (int y = 15; y >= 0 && remaining > 0; y--) {
                for (int col = 0; col < 256; col++) {
                    if (top[col] != Integer.MIN_VALUE) continue;
                    int idx = palette.length == 1 || data == null ? 0 : paletteIndex(data, bits, y * 256 + col);
                    if (idx < floor.length && floor[idx]) {
                        top[col] = sy * 16 + y;
                        remaining--;
                    }
                }
            }
        }
        if (remaining == 0) {
            int min = Integer.MAX_VALUE;
            for (int t : top) min = Math.min(min, t);
            f.floorMinY = min;
        }
    }

    /**
     * Block names in a section's palette. 26.x writes a plain string when a list holds only names, and otherwise wraps a
     * bare name as {"": name} and a block with properties as {id, properties}. A world upgraded from 1.21 can still hold
     * chunks nobody has loaded since, in the older {Name, Properties} form, so that's read too.
     */
    public static String[] palette(Nbt.Compound states) {
        Nbt.NbtList p = states.getList("palette");
        if (p == null || p.items().isEmpty()) return new String[]{"minecraft:air"};
        String[] out = new String[p.items().size()];
        for (int i = 0; i < out.length; i++) out[i] = blockName(p.items().get(i));
        return out;
    }

    static String blockName(Object entry) {
        if (entry instanceof String s) return s;
        if (entry instanceof Nbt.Compound c) {
            if (c.get("Name") instanceof String s) return s;
            if (c.get("id") instanceof String s) return s;
            if (c.get("") instanceof String s) return s;
            if (c.get("") instanceof Nbt.Compound inner) return blockName(inner);
        }
        return "minecraft:unknown";
    }

    /** Since 1.16, entries never straddle two longs. */
    public static int paletteIndex(long[] data, int bits, int i) {
        int perLong = 64 / bits;
        int word = i / perLong;
        if (word >= data.length) return 0;
        int shift = (i % perLong) * bits;
        return (int) ((data[word] >>> shift) & ((1L << bits) - 1));
    }

    /** Whether a block counts as ground. Air, fluids, leaves and plants don't. */
    static boolean isFloor(String name) {
        if (name == null) return false;
        String n = name.startsWith("minecraft:") ? name.substring(10) : name;
        if (n.endsWith("air") || n.equals("water") || n.equals("lava") || n.equals("bubble_column")) return false;
        if (n.endsWith("_leaves") || n.endsWith("_sapling") || n.endsWith("_flower") || n.endsWith("_tulip")) return false;
        return switch (n) {
            case "short_grass", "tall_grass", "fern", "large_fern", "snow", "vine", "seagrass", "tall_seagrass", "kelp",
                 "kelp_plant", "dead_bush", "sugar_cane", "bamboo", "sweet_berry_bush", "dandelion", "poppy",
                 "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower",
                 "lilac", "rose_bush", "peony", "brown_mushroom", "red_mushroom", "glow_lichen", "moss_carpet",
                 "pink_petals", "cactus", "pumpkin", "melon", "cocoa", "bush", "firefly_bush", "leaf_litter",
                 "wildflowers", "short_dry_grass", "tall_dry_grass", "pale_hanging_moss", "cave_vines",
                 "cave_vines_plant", "big_dripleaf", "big_dripleaf_stem", "small_dripleaf", "hanging_roots",
                 "spore_blossom", "lily_pad", "mangrove_propagule", "mangrove_roots", "pointed_dripstone",
                 "powder_snow", "cobweb", "torchflower", "pitcher_plant", "closed_eyeblossom", "open_eyeblossom" -> false;
            default -> !n.endsWith("_log") && !n.endsWith("_wood") && !n.startsWith("potted_");
        };
    }

    static void readEntities(ChunkFacts f, Nbt.NbtList entities) {
        if (entities == null) return;
        for (Nbt.Compound e : entities.compounds()) {
            String reason = keepReason(e);
            if (reason == null) continue;
            double y = 0;
            if (e.getList("Pos") instanceof Nbt.NbtList pos && pos.items().size() == 3 && pos.items().get(1) instanceof Number n) {
                y = n.doubleValue();
            }
            f.keptEntities.add(new KeptEntity(reason, y));
        }
    }

    /** Why a saved entity keeps its chunk, or null if it doesn't. */
    static String keepReason(Nbt.Compound e) {
        String id = e.getString("id");
        String name = id == null ? "entity" : id.replace("minecraft:", "");
        if (e.containsKey("CustomName")) return "named " + name;
        if (e.containsKey("Owner") || e.containsKey("OwnerUUID")) return "tamed " + name;
        if (e.containsKey("leash") || e.containsKey("Leash")) return name + " on a lead";
        if ("minecraft:villager".equals(id)) {
            if (e.getInt("Xp", 0) > 0) return "villager that has traded";
            Nbt.Compound data = e.getCompound("VillagerData");
            if (data != null && data.getInt("level", 1) > 1) return "villager that has traded";
        }
        if (id != null) {
            switch (id) {
                case "minecraft:allay": return "allay";
                case "minecraft:armor_stand": return "armor stand";
                case "minecraft:item_frame", "minecraft:glow_item_frame": return "item frame";
                case "minecraft:painting": return "painting";
                default: break;
            }
        }
        if (e.getList("Passengers") instanceof Nbt.NbtList passengers) {
            for (Nbt.Compound p : passengers.compounds()) {
                String r = keepReason(p);
                if (r != null) return r;
            }
        }
        return null;
    }
}
