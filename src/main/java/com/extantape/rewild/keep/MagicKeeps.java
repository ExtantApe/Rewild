package com.extantape.rewild.keep;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * Keeps the Magic plugin's blocks and spell changes (D224: "It should probably plug into the magic plugin so it doesn't
 * delete things from that realm, but it still should rewild"). Read through Magic's public API by reflection, so Rewild
 * runs without Magic installed. Kept, block by block, while the land around them rewilds or heals:
 * <ul>
 *   <li>every Magic block and automaton, with {@code magic.keep-radius} blocks around it for its structure;</li>
 *   <li>every area a spell changed that Magic will put back itself (its pending undo lists).</li>
 * </ul>
 * Fails closed: in a world listed in {@code magic.worlds}, nothing resets or heals unless Magic is running and answers.
 */
public final class MagicKeeps {
    private MagicKeeps() {}

    /** Why a world can't reset or heal because of Magic, or null. Pure, for tests. */
    static String problem(String world, Set<String> magicWorlds, boolean running, String readError) {
        boolean needed = magicWorlds.contains(world);
        if (!running) return needed ? "Magic isn't running, and " + world + " is a Magic world" : null;
        if (readError != null) return "Magic's blocks couldn't be read (" + readError + ")";
        return null;
    }

    /** A Magic block at x y z becomes a kept box radius blocks around it. Pure, for tests. */
    static KeepPlan.Area around(String world, int x, int y, int z, int radius, String what) {
        return new KeepPlan.Area(world, x - radius, y - radius, z - radius, x + radius, y + radius, z + radius, what);
    }

    private static Object controller() throws ReflectiveOperationException {
        Plugin magic = Bukkit.getPluginManager().getPlugin("Magic");
        if (magic == null || !magic.isEnabled()) return null;
        return magic.getClass().getMethod("getController").invoke(magic);
    }

    static void read(World w, Set<String> magicWorlds, int radius, Keepers.Result r) {
        Plugin magic = Bukkit.getPluginManager().getPlugin("Magic");
        boolean running = magic != null && magic.isEnabled();
        String error = null;
        List<KeepPlan.Area> areas = new ArrayList<>();
        if (running) {
            try {
                Object c = controller();
                if (c == null) throw new IllegalStateException("no controller");
                for (Object block : (Collection<?>) c.getClass().getMethod("getMagicBlocks").invoke(c)) {
                    Location l = (Location) block.getClass().getMethod("getLocation").invoke(block);
                    if (l == null || l.getWorld() == null || !l.getWorld().getUID().equals(w.getUID())) continue;
                    String key = String.valueOf(block.getClass().getMethod("getTemplateKey").invoke(block));
                    areas.add(around(w.getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), radius, "a Magic block (" + key + ")"));
                }
                for (Object undo : (Collection<?>) c.getClass().getMethod("getPendingUndo").invoke(c)) {
                    Method affects = undo.getClass().getMethod("affectsWorld", World.class);
                    if (!(Boolean) affects.invoke(undo, w)) continue;
                    Object box = undo.getClass().getMethod("getArea").invoke(undo);
                    if (box == null) continue;
                    org.bukkit.util.Vector min = (org.bukkit.util.Vector) box.getClass().getMethod("getMin").invoke(box);
                    org.bukkit.util.Vector max = (org.bukkit.util.Vector) box.getClass().getMethod("getMax").invoke(box);
                    areas.add(new KeepPlan.Area(w.getName(), min.getBlockX(), min.getBlockY(), min.getBlockZ(), max.getBlockX(), max.getBlockY(), max.getBlockZ(),
                        "a spell's change that Magic will undo"));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                error = e.toString();
            }
        }
        String p = problem(w.getName(), magicWorlds, running, error);
        if (p != null) r.externalProblem = p;
        else r.areas.addAll(areas);
    }

    /**
     * True when any of these blocks is part of a spell's change Magic will put back, or when Magic can't say. Checked
     * live as an explosion happens, so a spell's blast is left to Magic's own undo. False when Magic isn't running.
     */
    public static boolean spellChanged(List<Block> blocks) {
        try {
            Object c = controller();
            if (c == null) return false;
            for (Object undo : (Collection<?>) c.getClass().getMethod("getPendingUndo").invoke(c)) {
                Method contains = undo.getClass().getMethod("contains", Block.class);
                for (Block b : blocks) if ((Boolean) contains.invoke(undo, b)) return true;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return true;
        }
    }
}
