package com.extantape.rewild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Moves a server's files over from the old plugin folder after the rename from WildernessRegen (2026-11-09).
 * It's a move, not a copy, because the folder holds undo backups of region files that can be large, and an
 * approved run must be found in exactly one place. Paths inside are relative to the server, so they still work.
 */
final class Migration {
    private Migration() {}

    /** What happened: a line for the log (null if nothing to do), and whether it failed. */
    record Result(String message, boolean failed) {}

    static Result moveIfMissing(Path old, Path now) {
        if (!Files.isDirectory(old)) return new Result(null, false);
        if (Files.exists(now)) return new Result("Both plugins/" + old.getFileName() + " and plugins/" + now.getFileName()
                + " exist. Using plugins/" + now.getFileName() + "; the old folder is ignored. Delete it once you've checked nothing in it is needed.", false);
        try {
            Files.move(old, now, StandardCopyOption.ATOMIC_MOVE);
            return new Result("Moved plugins/" + old.getFileName() + " to plugins/" + now.getFileName()
                    + " (WildernessRegen is now called Rewild). Runs, undo backups and settings came across unchanged.", false);
        } catch (IOException e) {
            return new Result("Couldn't move plugins/" + old.getFileName() + " to plugins/" + now.getFileName() + ": " + e
                    + ". Stop the server and rename the folder by hand.", true);
        }
    }
}
