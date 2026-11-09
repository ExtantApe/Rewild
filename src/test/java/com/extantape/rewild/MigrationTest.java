package com.extantape.rewild;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {

    @Test
    void movesTheOldFolderWhenTheNewOneIsMissing(@TempDir Path plugins) throws Exception {
        Path old = plugins.resolve("WildernessRegen");
        Files.createDirectories(old.resolve("runs/r1"));
        Files.writeString(old.resolve("config.yml"), "worlds: {}\n");
        Files.writeString(old.resolve("runs/r1/run.properties"), "state=APPROVED\n");

        Migration.Result r = Migration.moveIfMissing(old, plugins.resolve("Rewild"));

        assertFalse(r.failed());
        assertNotNull(r.message());
        assertEquals("state=APPROVED\n", Files.readString(plugins.resolve("Rewild/runs/r1/run.properties")));
        assertEquals("worlds: {}\n", Files.readString(plugins.resolve("Rewild/config.yml")));
        assertFalse(Files.exists(old), "an approved run must live in one place only");
    }

    @Test
    void neverTouchesAnExistingNewFolder(@TempDir Path plugins) throws Exception {
        Files.createDirectories(plugins.resolve("WildernessRegen"));
        Files.writeString(plugins.resolve("WildernessRegen/config.yml"), "old");
        Files.createDirectories(plugins.resolve("Rewild"));
        Files.writeString(plugins.resolve("Rewild/config.yml"), "new");

        Migration.Result r = Migration.moveIfMissing(plugins.resolve("WildernessRegen"), plugins.resolve("Rewild"));

        assertFalse(r.failed());
        assertTrue(r.message().contains("ignored"));
        assertEquals("new", Files.readString(plugins.resolve("Rewild/config.yml")));
        assertEquals("old", Files.readString(plugins.resolve("WildernessRegen/config.yml")));
    }

    @Test
    void doesNothingOnAFreshServer(@TempDir Path plugins) {
        Migration.Result r = Migration.moveIfMissing(plugins.resolve("WildernessRegen"), plugins.resolve("Rewild"));
        assertNull(r.message());
        assertFalse(r.failed());
    }

    @Test
    void reportsAFailedMoveSoNothingRuns(@TempDir Path plugins) throws Exception {
        Files.createDirectories(plugins.resolve("WildernessRegen"));
        Files.writeString(plugins.resolve("WildernessRegen/config.yml"), "x");
        Path blockedParent = plugins.resolve("not-a-folder");
        Files.writeString(blockedParent, "a file, so nothing can be moved under it");

        Migration.Result r = Migration.moveIfMissing(plugins.resolve("WildernessRegen"), blockedParent.resolve("Rewild"));

        assertTrue(r.failed());
        assertTrue(Files.exists(plugins.resolve("WildernessRegen/config.yml")));
    }
}
