package com.extantape.wildernessregen.api;

import org.bukkit.World;

import java.util.Collection;

/**
 * Lets another plugin keep chunks from ever being reset, such as a graves plugin keeping the chunk a grave sits in.
 * Register an implementation with Bukkit's services manager:
 *
 * <pre>{@code
 * getServer().getServicesManager().register(KeepProvider.class, provider, this, ServicePriority.Normal);
 * }</pre>
 *
 * WildernessRegen asks every registered provider on the main thread when it scans, previews, and shuts down before
 * a restart that may apply a run. If a provider throws, nothing in that world resets.
 */
public interface KeepProvider {
    /** A chunk to keep, and the reason shown by /wr why, such as "an active grave". */
    record Keep(int chunkX, int chunkZ, String reason) {}

    /** Every chunk in this world that must be kept right now. */
    Collection<Keep> keeps(World world);
}
