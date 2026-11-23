package com.extantape.rewild.heal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * How a hole heals, step by step (D224: "if a creeper blows a hole in the ground, the next day one layer is filled, then
 * a couple days later another is ... so stuff doesn't just snap back but truly rewilds. Same with trees"). Pure: no
 * server, so every rule here is unit tested.
 *
 * <ol>
 *   <li>Ground fills from the bottom up, a few layers per step, sized so the ground takes {@code days - 3} steps.
 *       Grass-type surface blocks go in as plain dirt.</li>
 *   <li>The next step, the grass comes back on that dirt, and each felled tree gets a sapling where its trunk stood.</li>
 *   <li>The step after, each tree is a young tree: the lower half of its trunk and the leaves close to it.</li>
 *   <li>The last step puts the rest of the trees, loose leaves and the small plants (flowers, grass, snow) back.</li>
 * </ol>
 * With the default 7 days, a typical creeper crater (3 or 4 layers deep) takes about a week.
 */
public final class HealPlan {
    /** A block to put back, at world coordinates, as Minecraft block data ("minecraft:stone"). */
    public record Cell(int x, int y, int z, String data) {
        public String material() {
            String d = data.toLowerCase(Locale.ROOT);
            int b = d.indexOf('[');
            String m = b < 0 ? d : d.substring(0, b);
            return m.startsWith("minecraft:") ? m.substring(10) : m;
        }
    }

    /**
     * What one step places. {@code over} is a block this placement may replace besides air, water and the like: the
     * dirt an earlier step laid where grass now goes, or the sapling where the trunk now goes. Null for none.
     */
    public record Placement(int x, int y, int z, String data, String over) {}

    private static final Set<String> GRASSY = Set.of("grass_block", "podzol", "mycelium", "moss_block");
    private static final Map<String, String> SAPLINGS = Map.ofEntries(
        Map.entry("oak", "oak_sapling"), Map.entry("spruce", "spruce_sapling"), Map.entry("birch", "birch_sapling"),
        Map.entry("jungle", "jungle_sapling"), Map.entry("acacia", "acacia_sapling"), Map.entry("dark_oak", "dark_oak_sapling"),
        Map.entry("cherry", "cherry_sapling"), Map.entry("pale_oak", "pale_oak_sapling"), Map.entry("mangrove", "mangrove_propagule"),
        Map.entry("crimson", "crimson_fungus"), Map.entry("warped", "warped_fungus"));

    private HealPlan() {}

    static boolean isLog(String m) {
        return m.endsWith("_log") || m.endsWith("_wood") || m.endsWith("_stem") || m.endsWith("_hyphae");
    }

    static boolean isLeaves(String m) {
        return m.endsWith("_leaves") || m.equals("nether_wart_block") || m.equals("warped_wart_block") || m.equals("shroomlight");
    }

    /** The sapling for a log ("oak_log" to "minecraft:oak_sapling"), or null when there's none (stripped logs too). */
    static String saplingFor(String logMaterial) {
        if (logMaterial.startsWith("stripped_")) return null;
        String wood = logMaterial.replaceFirst("_(log|wood|stem|hyphae)$", "");
        String s = SAPLINGS.get(wood);
        return s == null ? null : "minecraft:" + s;
    }

    /**
     * The steps, in order. Step 0 runs a step after the damage, and each later one a step after that.
     *
     * @param plant true for small plants that come back last (flowers, grass, snow layers); the caller decides, from
     *              the same list that says what doesn't count as damage.
     */
    public static List<List<Placement>> steps(List<Cell> cells, int days, Predicate<String> plant) {
        List<Cell> ground = new ArrayList<>(), logs = new ArrayList<>(), leaves = new ArrayList<>(), plants = new ArrayList<>();
        for (Cell c : cells) {
            String m = c.material();
            if (m.equals("air") || m.equals("cave_air") || m.equals("void_air")) continue;
            if (isLog(m)) logs.add(c);
            else if (isLeaves(m)) leaves.add(c);
            else if (plant.test(m)) plants.add(c);
            else ground.add(c);
        }
        List<List<Placement>> steps = new ArrayList<>();

        // 1. Ground, bottom up.
        TreeSet<Integer> layers = new TreeSet<>();
        for (Cell c : ground) layers.add(c.y);
        int groundSteps = Math.max(1, days - 3);
        int perStep = Math.max(1, (int) Math.ceil(layers.size() / (double) groundSteps));
        List<Integer> ys = new ArrayList<>(layers);
        for (int i = 0; i < ys.size(); i += perStep) {
            Set<Integer> band = new HashSet<>(ys.subList(i, Math.min(ys.size(), i + perStep)));
            List<Placement> step = new ArrayList<>();
            for (Cell c : ground)
                if (band.contains(c.y)) step.add(new Placement(c.x, c.y, c.z, GRASSY.contains(c.material()) ? "minecraft:dirt" : c.data, null));
            sort(step);
            steps.add(step);
        }

        // 2. Grass on the dirt, saplings where trunks stood.
        List<List<Cell>> trees = trees(logs);
        List<Placement> grass = new ArrayList<>();
        for (Cell c : ground) if (GRASSY.contains(c.material())) grass.add(new Placement(c.x, c.y, c.z, c.data, "minecraft:dirt"));
        Map<List<Cell>, Cell> bases = new HashMap<>();
        for (List<Cell> t : trees) {
            Cell base = t.stream().min(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x).thenComparingInt(Cell::z)).orElseThrow();
            bases.put(t, base);
            String sapling = saplingFor(base.material());
            if (sapling != null) grass.add(new Placement(base.x, base.y, base.z, sapling, null));
        }
        if (!grass.isEmpty() || !trees.isEmpty()) steps.add(sorted(grass));

        // 3. Young trees: the lower half of each trunk and the leaves within 2 blocks of it.
        Set<Cell> placedLeaves = new HashSet<>(), placedLogs = new HashSet<>();
        if (!trees.isEmpty()) {
            List<Placement> young = new ArrayList<>();
            for (List<Cell> t : trees) {
                Cell base = bases.get(t);
                int top = t.stream().mapToInt(Cell::y).max().orElse(base.y);
                int half = base.y + (top - base.y + 1) / 2;
                List<Cell> lower = t.stream().filter(c -> c.y < Math.max(half, base.y + 1)).toList();
                String sapling = saplingFor(base.material());
                for (Cell c : lower) { young.add(new Placement(c.x, c.y, c.z, c.data, c == base ? sapling : null)); placedLogs.add(c); }
                for (Cell l : leaves)
                    for (Cell c : lower)
                        if (Math.abs(l.x - c.x) <= 2 && Math.abs(l.y - c.y) <= 2 && Math.abs(l.z - c.z) <= 2) {
                            if (placedLeaves.add(l)) young.add(new Placement(l.x, l.y, l.z, l.data, null));
                            break;
                        }
            }
            steps.add(sorted(young));
        }

        // 4. Everything else: the rest of the trees, loose leaves and small plants.
        List<Placement> last = new ArrayList<>();
        for (Cell c : logs) if (!placedLogs.contains(c)) last.add(new Placement(c.x, c.y, c.z, c.data, null));
        for (Cell c : leaves) if (!placedLeaves.contains(c)) last.add(new Placement(c.x, c.y, c.z, c.data, null));
        for (Cell c : plants) last.add(new Placement(c.x, c.y, c.z, c.data, null));
        if (!last.isEmpty()) steps.add(sorted(last));
        return steps;
    }

    /** Logs that touch (including diagonally) are one tree. */
    static List<List<Cell>> trees(List<Cell> logs) {
        Map<Long, Cell> at = new HashMap<>();
        for (Cell c : logs) at.put(key(c.x, c.y, c.z), c);
        Set<Cell> seen = new HashSet<>();
        List<List<Cell>> out = new ArrayList<>();
        for (Cell start : logs) {
            if (!seen.add(start)) continue;
            List<Cell> tree = new ArrayList<>();
            List<Cell> todo = new ArrayList<>(List.of(start));
            while (!todo.isEmpty()) {
                Cell c = todo.removeLast();
                tree.add(c);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    Cell n = at.get(key(c.x + dx, c.y + dy, c.z + dz));
                    if (n != null && seen.add(n)) todo.add(n);
                }
            }
            out.add(tree);
        }
        return out;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static List<Placement> sorted(List<Placement> l) {
        sort(l);
        return l;
    }

    /** Bottom up, so nothing is placed floating on a gap the same step fills. */
    private static void sort(List<Placement> l) {
        l.sort(Comparator.comparingInt(Placement::y).thenComparingInt(Placement::x).thenComparingInt(Placement::z));
    }
}
