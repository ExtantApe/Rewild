package com.extantape.wildernessregen.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parsed config. Parsing collects problems instead of guessing: a config with any problem leaves {@link #errors()}
 * non-empty, and the plugin then resets nothing.
 */
public final class RegenConfig {
    public record WorldSettings(
            boolean enabled,
            int minRemovedBlocks, int minExplosionBlocks, List<String> damageIgnore,
            int daysWithoutChanges, int daysWithoutVisitors, int visitRadiusChunks, int daysSinceLastReset,
            int bufferChunks, int claimBufferChunks, int spawnRadiusChunks, int respawnRadiusChunks,
            int pathMinConnected, int intentScore, List<String> structures, int endMainIslandRadiusBlocks,
            boolean undergroundEnabled, int undergroundMarginBlocks, int undergroundMinRemovedBlocks) {}

    public final boolean dryRun;
    public final String claimPlugin;
    public final int maxChunks;
    public final int minGroupSize;
    public final int approvalExpiresDays;
    public final boolean warnPlayers;
    public final String warnMessage;
    public final int trashKeepDays;
    public final boolean blueMap;
    /** Worlds that never reset, overriding enabled. Kids' worlds belong here. */
    public final java.util.Set<String> neverReset = new java.util.HashSet<>();
    public final List<String> intentKeep;
    public final Map<String, Integer> intentScore;
    private final Map<String, WorldSettings> worlds = new HashMap<>();
    private final WorldSettings disabled;
    private final List<String> errors = new ArrayList<>();

    public RegenConfig(FileConfiguration c) {
        dryRun = bool(c, "dry-run", true);
        claimPlugin = string(c, "claims.plugin", "GriefPrevention");
        maxChunks = positive(c, "run.max-chunks", 200);
        minGroupSize = positive(c, "run.min-group-size", 1);
        approvalExpiresDays = positive(c, "run.approval-expires-days", 7);
        warnPlayers = bool(c, "run.warn-players", true);
        warnMessage = string(c, "run.warn-message", "Some untouched wild land in this world will refresh at the next restart.");
        trashKeepDays = positive(c, "trash.keep-days", 30);
        blueMap = bool(c, "bluemap.enabled", false);
        if (c.contains("never-reset-worlds") && !c.isList("never-reset-worlds")) errors.add("never-reset-worlds must be a list of world names");
        neverReset.addAll(c.getStringList("never-reset-worlds"));
        intentKeep = patterns(c, "intent.keep");
        intentScore = new LinkedHashMap<>();
        ConfigurationSection score = c.getConfigurationSection("intent.score");
        if (score != null) {
            for (String k : score.getKeys(false)) {
                if (!score.isInt(k) || score.getInt(k) < 0) errors.add("intent.score." + k + " must be a whole number, 0 or more");
                else intentScore.put(k.toUpperCase(Locale.ROOT), score.getInt(k));
            }
        }
        ConfigurationSection defaults = c.getConfigurationSection("defaults");
        disabled = settings(defaults, null, "defaults", false);
        ConfigurationSection ws = c.getConfigurationSection("worlds");
        if (ws != null) {
            for (String name : ws.getKeys(false)) {
                ConfigurationSection w = ws.getConfigurationSection(name);
                if (w == null) {
                    errors.add("worlds." + name + " should be a section with enabled: true or false");
                    continue;
                }
                worlds.put(name, settings(defaults, w, "worlds." + name, bool(w, "enabled", false)));
            }
        }
    }

    /** Each problem once, even when a bad default is inherited by several worlds. */
    public List<String> errors() {
        return List.copyOf(new java.util.LinkedHashSet<>(errors));
    }

    public boolean healthy() {
        return errors.isEmpty();
    }

    /** Settings for a world. A world not listed, or listed in never-reset-worlds, is disabled. */
    public WorldSettings world(String name) {
        if (neverReset.contains(name)) return disabled;
        return worlds.getOrDefault(name, disabled);
    }

    public List<String> enabledWorldNames() {
        List<String> out = new ArrayList<>();
        worlds.forEach((k, v) -> { if (v.enabled() && !neverReset.contains(k)) out.add(k); });
        return out;
    }

    private WorldSettings settings(ConfigurationSection defaults, ConfigurationSection world, String where, boolean enabled) {
        Layer l = new Layer(defaults, world, where);
        return new WorldSettings(enabled,
                l.nonNegative("damage.min-removed-blocks", 40), l.positive("damage.min-explosion-blocks", 12), l.patterns("damage.ignore"),
                l.nonNegative("idle.days-without-changes", 30), l.nonNegative("idle.days-without-visitors", 7),
                l.nonNegative("idle.visit-radius-chunks", 2), l.nonNegative("idle.days-since-last-reset", 30),
                l.nonNegative("keep.buffer-chunks", 1), l.nonNegative("keep.claim-buffer-chunks", 1),
                l.nonNegative("keep.spawn-radius-chunks", 8), l.nonNegative("keep.respawn-radius-chunks", 1),
                l.positive("keep.path-min-connected", 3), l.positive("keep.intent-score", 8), l.strings("keep.structures"),
                l.nonNegative("keep.end-main-island-radius-blocks", 1100),
                l.bool("underground.enabled", true), l.nonNegative("underground.margin-blocks", 8),
                l.positive("underground.min-removed-blocks", 40));
    }

    /** Reads a key from the world's section first, then from defaults. */
    private final class Layer {
        final ConfigurationSection defaults, world;
        final String where;

        Layer(ConfigurationSection defaults, ConfigurationSection world, String where) {
            this.defaults = defaults;
            this.world = world;
            this.where = where;
        }

        ConfigurationSection pick(String key) {
            if (world != null && world.contains(key)) return world;
            if (defaults != null && defaults.contains(key)) return defaults;
            return null;
        }

        int nonNegative(String key, int fallback) {
            return number(key, fallback, 0);
        }

        int positive(String key, int fallback) {
            return number(key, fallback, 1);
        }

        int number(String key, int fallback, int min) {
            ConfigurationSection s = pick(key);
            if (s == null) return fallback;
            if (!s.isInt(key) || s.getInt(key) < min) {
                errors.add(where(s) + key + " must be a whole number, " + min + " or more");
                return fallback;
            }
            return s.getInt(key);
        }

        boolean bool(String key, boolean fallback) {
            ConfigurationSection s = pick(key);
            if (s == null) return fallback;
            if (!s.isBoolean(key)) {
                errors.add(where(s) + key + " must be true or false");
                return fallback;
            }
            return s.getBoolean(key);
        }

        List<String> strings(String key) {
            ConfigurationSection s = pick(key);
            if (s == null) return List.of();
            if (!s.isList(key)) {
                errors.add(where(s) + key + " must be a list");
                return List.of();
            }
            return List.copyOf(s.getStringList(key));
        }

        List<String> patterns(String key) {
            List<String> out = new ArrayList<>();
            for (String p : strings(key)) out.add(p.toUpperCase(Locale.ROOT));
            return out;
        }

        String where(ConfigurationSection s) {
            return (s == world ? where : "defaults") + ".";
        }
    }

    private boolean bool(ConfigurationSection c, String key, boolean fallback) {
        if (!c.contains(key)) return fallback;
        if (!c.isBoolean(key)) {
            errors.add(key + " must be true or false");
            return fallback;
        }
        return c.getBoolean(key);
    }

    private String string(ConfigurationSection c, String key, String fallback) {
        if (!c.contains(key)) return fallback;
        if (!c.isString(key)) {
            errors.add(key + " must be text");
            return fallback;
        }
        return c.getString(key);
    }

    private int positive(ConfigurationSection c, String key, int fallback) {
        if (!c.contains(key)) return fallback;
        if (!c.isInt(key) || c.getInt(key) < 1) {
            errors.add(key + " must be a whole number, 1 or more");
            return fallback;
        }
        return c.getInt(key);
    }

    private List<String> patterns(ConfigurationSection c, String key) {
        if (!c.contains(key)) return List.of();
        if (!c.isList(key)) {
            errors.add(key + " must be a list");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : c.getStringList(key)) out.add(p.toUpperCase(Locale.ROOT));
        return out;
    }

    /** Matches a material name against a pattern where * stands for any run of characters. */
    public static boolean matches(String pattern, String name) {
        if (!pattern.contains("*")) return pattern.equals(name);
        return Pattern.compile(Pattern.quote(pattern).replace("*", "\\E.*\\Q")).matcher(name).matches();
    }

    public static boolean matchesAny(List<String> patterns, String name) {
        for (String p : patterns) if (matches(p, name)) return true;
        return false;
    }
}
