package com.extantape.rewild.keep;

import com.extantape.rewild.api.KeepProvider;
import com.extantape.rewild.store.ChunkRecord;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads everything outside the tracker that can keep a chunk: claims, respawn points, other plugins' keeps and
 * Multiverse portals. Each read reports a problem instead of guessing, and a problem stops resets in that world.
 * Main thread only.
 */
public final class Keepers {
    /** Plugins that should report keeps through {@link KeepProvider}. If one is running without doing so, nothing resets. */
    static final List<String> EXPECTED_PROVIDERS = List.of("LanternGraves");

    public static final class Result {
        public final List<int[]> claims = new ArrayList<>();
        public String claimsProblem;
        public final Set<Long> respawnChunks = new HashSet<>();
        public final Map<Long, List<String>> external = new HashMap<>();
        public String externalProblem;
    }

    private final String claimPlugin;

    public Keepers(String claimPlugin) {
        this.claimPlugin = claimPlugin;
    }

    public Result read(World w) {
        Result r = new Result();
        readClaims(w, r);
        readRespawns(w, r);
        readProviders(w, r);
        readMultiversePortals(w, r);
        return r;
    }

    private void readClaims(World w, Result r) {
        if ("none".equalsIgnoreCase(claimPlugin)) return;
        if (!"GriefPrevention".equalsIgnoreCase(claimPlugin)) {
            r.claimsProblem = "claims.plugin is " + claimPlugin + ", and only GriefPrevention or none are supported";
            return;
        }
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (gp == null || !gp.isEnabled()) {
            r.claimsProblem = "GriefPrevention isn't running";
            return;
        }
        try {
            Object dataStore = gp.getClass().getField("dataStore").get(gp);
            Collection<?> claims = (Collection<?>) dataStore.getClass().getMethod("getClaims").invoke(dataStore);
            for (Object claim : claims) addClaim(w, claim, r);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            r.claimsProblem = "GriefPrevention's claims couldn't be read (" + e + ")";
        }
    }

    private static void addClaim(World w, Object claim, Result r) throws ReflectiveOperationException {
        Method lesser = claim.getClass().getMethod("getLesserBoundaryCorner");
        Method greater = claim.getClass().getMethod("getGreaterBoundaryCorner");
        Location a = (Location) lesser.invoke(claim), b = (Location) greater.invoke(claim);
        if (a == null || b == null || a.getWorld() == null || !a.getWorld().getUID().equals(w.getUID())) return;
        r.claims.add(new int[]{Math.min(a.getBlockX(), b.getBlockX()) >> 4, Math.min(a.getBlockZ(), b.getBlockZ()) >> 4,
                Math.max(a.getBlockX(), b.getBlockX()) >> 4, Math.max(a.getBlockZ(), b.getBlockZ()) >> 4});
    }

    private static void readRespawns(World w, Result r) {
        Set<java.util.UUID> seen = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            seen.add(p.getUniqueId());
            add(w, safeRespawn(p), r);
        }
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (seen.add(p.getUniqueId())) add(w, safeRespawn(p), r);
        }
    }

    private static Location safeRespawn(OfflinePlayer p) {
        try {
            // false: the saved point as is. The no-argument form drops a point whose bed is gone, and a point set by
            // /spawnpoint or a respawn anchor still marks a place the player means to come back to.
            return p.getRespawnLocation(false);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static void add(World w, Location l, Result r) {
        if (l == null || l.getWorld() == null || !l.getWorld().getUID().equals(w.getUID())) return;
        r.respawnChunks.add(ChunkRecord.key(l.getBlockX() >> 4, l.getBlockZ() >> 4));
    }

    private static void readProviders(World w, Result r) {
        Set<String> reporting = new HashSet<>();
        for (RegisteredServiceProvider<KeepProvider> reg : Bukkit.getServicesManager().getRegistrations(KeepProvider.class)) {
            String owner = reg.getPlugin().getName();
            reporting.add(owner);
            try {
                Collection<KeepProvider.Keep> keeps = reg.getProvider().keeps(w);
                if (keeps == null) throw new IllegalStateException("returned null");
                for (KeepProvider.Keep k : keeps) {
                    r.external.computeIfAbsent(ChunkRecord.key(k.chunkX(), k.chunkZ()), kk -> new ArrayList<>())
                            .add(k.reason() == null || k.reason().isBlank() ? "kept by " + owner : k.reason() + " (" + owner + ")");
                }
            } catch (RuntimeException | LinkageError e) {
                r.externalProblem = owner + " couldn't say which chunks to keep (" + e + ")";
            }
        }
        for (String expected : EXPECTED_PROVIDERS) {
            Plugin p = Bukkit.getPluginManager().getPlugin(expected);
            if (p != null && p.isEnabled() && !reporting.contains(p.getName())) {
                r.externalProblem = expected + " is running but doesn't report what it needs kept, so nothing resets";
            }
        }
    }

    /** Multiverse-Portals keeps portals in portals.yml as "x,y,z:x,y,z" with a world name. */
    private static void readMultiversePortals(World w, Result r) {
        Plugin mvp = Bukkit.getPluginManager().getPlugin("Multiverse-Portals");
        if (mvp == null || !mvp.isEnabled()) return;
        File f = new File(mvp.getDataFolder(), "portals.yml");
        if (!f.exists()) return;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection portals = y.getConfigurationSection("portals");
            if (portals == null) return;
            for (String name : portals.getKeys(false)) {
                ConfigurationSection p = portals.getConfigurationSection(name);
                if (p == null) continue;
                String loc = p.getString("location");
                String world = p.getString("world");
                if (loc == null) continue;
                if (world == null && loc.contains(":")) world = null;
                if (world != null && !world.equals(w.getName())) continue;
                String[] corners = loc.split(":");
                if (corners.length < 2) throw new IllegalArgumentException("portal " + name + " has location " + loc);
                String[] a = corners[corners.length - 2].split(","), b = corners[corners.length - 1].split(",");
                int x1 = (int) Math.floor(Double.parseDouble(a[0])) >> 4, z1 = (int) Math.floor(Double.parseDouble(a[2])) >> 4;
                int x2 = (int) Math.floor(Double.parseDouble(b[0])) >> 4, z2 = (int) Math.floor(Double.parseDouble(b[2])) >> 4;
                for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                        r.external.computeIfAbsent(ChunkRecord.key(x, z), k -> new ArrayList<>()).add("a Multiverse portal");
                    }
                }
            }
        } catch (RuntimeException e) {
            r.externalProblem = "Multiverse-Portals' portals.yml couldn't be read (" + e.getMessage() + ")";
        }
    }
}
