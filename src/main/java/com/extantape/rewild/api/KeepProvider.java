package com.extantape.rewild.api;

import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.Collection;
import java.util.List;

/**
 * Lets another plugin keep what it owns when the land around it rewilds, such as a graves plugin keeping its graves or
 * a homes plugin keeping homesteads. Register an implementation with Bukkit's services manager:
 *
 * <pre>{@code
 * getServer().getServicesManager().register(KeepProvider.class, provider, this, ServicePriority.Normal);
 * }</pre>
 *
 * Rewild only listens to plugins an admin chose to respect (/rewild plugins). It asks every registered provider on the
 * main thread when it scans, previews, and shuts down before a restart that may apply a run. If a respected provider
 * throws, nothing in that world resets.
 *
 * <p>Two ways to report, and a provider may use both:
 * <ul>
 *   <li>{@link #keeps(World)}: whole chunks, left exactly as they are. For anything that needs its surroundings too.</li>
 *   <li>{@link #areas(World)}: block boxes, kept in place while the rest of the chunk rewilds. A single block is a box
 *       of one. To keep an entity, report the blocks it stands in or hangs on.</li>
 * </ul>
 */
public interface KeepProvider {
    /** A chunk to keep whole, and the reason shown by /rewild why, such as "an active grave". */
    record Keep(int chunkX, int chunkZ, String reason) {
        /** The chunk an entity is in. */
        public static Keep of(Entity entity, String reason) {
            return new Keep(entity.getLocation().getBlockX() >> 4, entity.getLocation().getBlockZ() >> 4, reason);
        }
    }

    /** A box of blocks, corners included, in world coordinates. */
    record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String reason) {
        public static Area block(int x, int y, int z, String reason) {
            return new Area(x, y, z, x, y, z, reason);
        }
    }

    /** Every chunk in this world that must be kept whole right now. */
    Collection<Keep> keeps(World world);

    /** Every block area in this world to keep in place right now. None by default. */
    default Collection<Area> areas(World world) {
        return List.of();
    }
}
