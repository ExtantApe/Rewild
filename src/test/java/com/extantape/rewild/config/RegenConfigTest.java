package com.extantape.rewild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegenConfigTest {
    static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(RegenConfigTest.class.getResourceAsStream("/config.yml")));
    }

    @Test
    void shippedConfigIsSafe() {
        RegenConfig c = new RegenConfig(shipped());
        assertTrue(c.healthy(), String.valueOf(c.errors()));
        assertTrue(c.dryRun, "dry run by default");
        assertFalse(c.blueMap, "the map layer would show where people build, so it's off by default");
        assertTrue(c.enabledWorldNames().isEmpty(), "no world is enabled by default");
        assertEquals(30, c.world("x").daysWithoutChanges());
        assertEquals(7, c.world("x").daysWithoutVisitors());
    }

    @Test
    void neverResetWorldsWinOverEnabled() {
        YamlConfiguration y = shipped();
        y.set("worlds.kids.enabled", true);
        y.set("worlds.survival.enabled", true);
        y.set("never-reset-worlds", List.of("kids"));
        RegenConfig c = new RegenConfig(y);
        assertFalse(c.world("kids").enabled());
        assertTrue(c.world("survival").enabled());
        assertEquals(List.of("survival"), c.enabledWorldNames());
    }

    @Test
    void badValuesAreErrorsNotGuesses() {
        YamlConfiguration y = shipped();
        y.set("run.max-chunks", "lots");
        y.set("defaults.idle.days-without-changes", -1);
        y.set("worlds.w.keep.intent-score", "high");
        RegenConfig c = new RegenConfig(y);
        assertFalse(c.healthy());
        assertEquals(3, c.errors().size(), String.valueOf(c.errors()));
    }

    @Test
    void perWorldOverridesFallBackToDefaults() {
        YamlConfiguration y = shipped();
        y.set("worlds.w.enabled", true);
        y.set("worlds.w.idle.days-without-changes", 60);
        RegenConfig c = new RegenConfig(y);
        assertEquals(60, c.world("w").daysWithoutChanges());
        assertEquals(7, c.world("w").daysWithoutVisitors());
    }

    @Test
    void wildcardPatterns() {
        assertTrue(RegenConfig.matches("*_BED", "RED_BED"));
        assertTrue(RegenConfig.matches("POTTED_*", "POTTED_FERN"));
        assertFalse(RegenConfig.matches("*_BED", "BEDROCK"));
        assertTrue(RegenConfig.matches("CHEST", "CHEST"));
        assertFalse(RegenConfig.matches("CHEST", "TRAPPED_CHEST"));
    }
}
