package com.extantape.rewild.keep;

import com.extantape.rewild.scan.ContextSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Nothing resets until every plugin has an answer; kept areas survive a restart, readable as plain text. */
class RespectAndKeepsTest {
    @TempDir
    Path dir;

    @Test
    void nothingResetsUntilEveryInstalledPluginHasAnAnswer() throws IOException {
        Respect r = new Respect(dir.toFile());
        List<String> installed = List.of("Rewild", "Homesteader", "LanternGraves", "Essentials");
        String why = r.blocking(installed, n -> true);
        assertNotNull(why);
        assertTrue(why.contains("Essentials, Homesteader, LanternGraves"), why);
        assertFalse(why.contains("Rewild"), "Rewild never needs an answer about itself");
        r.setRespected("Homesteader", true);
        r.setRespected("LanternGraves", true);
        assertTrue(r.blocking(installed, n -> true).contains("Essentials"));
        r.setRespected("Essentials", false);
        assertNull(r.blocking(installed, n -> true));
        r.save();
        Respect again = new Respect(dir.toFile());
        assertTrue(again.respected("homesteader"), "names match whatever their case");
        assertTrue(again.ignored("Essentials"));
        assertNull(again.blocking(installed, n -> true));
        // A plugin installed later has no answer, so resets stop until an admin looks at it.
        assertNotNull(again.blocking(List.of("Homesteader", "LanternGraves", "Essentials", "Waystation"), n -> true));
    }

    @Test
    void aRespectedPluginThatIsntRunningStopsResets() {
        Respect r = new Respect(dir.toFile());
        r.setRespected("Homesteader", true);
        String why = r.blocking(List.of("Homesteader"), n -> false);
        assertNotNull(why);
        assertTrue(why.contains("isn't running"), why);
    }

    @Test
    void aBrokenOrContradictoryFileStopsResets() throws IOException {
        Files.writeString(dir.resolve(Respect.FILE), "keep-plugins: [A]\nignore-plugins: [a]\n");
        assertNotNull(new Respect(dir.toFile()).blocking(List.of("A"), n -> true));
        Files.writeString(dir.resolve(Respect.FILE), "keep-plugins: [A\n");
        assertNotNull(new Respect(dir.toFile()).problem());
    }

    @Test
    void keepsAreSavedAsPlainLinesAndRemovableByIdOrExactCorners() throws IOException {
        CommandKeeps k = new CommandKeeps(dir);
        var a = k.add("admin", new KeepPlan.Area("world", 10, 64, 10, 12, 70, 12, "the old well"));
        var b = k.add("Homesteader", new KeepPlan.Area("world", 5, 60, 5, 0, 65, 0, "a homestead"));
        assertEquals(1, a.id());
        assertEquals(2, b.id());
        assertEquals(0, b.area().minX(), "corners are sorted");
        assertSame(a.id(), k.add("admin", new KeepPlan.Area("world", 10, 64, 10, 12, 70, 12, "again")).id(), "the same keep twice is one keep");
        String text = Files.readString(dir.resolve(CommandKeeps.FILE));
        assertTrue(text.contains("1 admin world 10 64 10 12 70 12 the old well"), text);

        CommandKeeps again = new CommandKeeps(dir);
        assertEquals(2, again.all().size());
        assertEquals(0, again.removeExact("admin", "world", 0, 60, 0, 5, 65, 5), "only the owner's own keep matches");
        assertEquals(1, again.removeExact("Homesteader", "world", 5, 65, 5, 0, 60, 0), "corners in any order");
        assertNotNull(again.remove(1));
        assertNull(again.remove(1));
        assertTrue(new CommandKeeps(dir).all().isEmpty());
    }

    @Test
    void aHugeKeepIsRefused() {
        CommandKeeps k = new CommandKeeps(dir);
        assertThrows(IOException.class, () -> k.add("admin", new KeepPlan.Area("world", -30000, -64, -30000, 30000, 320, 30000, "typo")));
    }

    @Test
    void anUnreadableKeepsFileIsAProblemNotAnEmptyList() throws IOException {
        Files.writeString(dir.resolve(CommandKeeps.FILE), "1 admin world ten 64 10 12 70 12 note\n");
        CommandKeeps k = new CommandKeeps(dir);
        assertNotNull(k.problem());
        assertThrows(IOException.class, () -> k.add("admin", new KeepPlan.Area("world", 0, 0, 0, 0, 0, 0, "")));
    }

    @Test
    void areasSurviveTheShutdownSnapshot() throws IOException {
        ContextSnapshot s = new ContextSnapshot();
        s.takenAt = 5;
        s.areas.add(new KeepPlan.Area(null, 1, 2, 3, 4, 5, 6, "a gate (Waystation)"));
        s.write(dir);
        ContextSnapshot back = ContextSnapshot.read(dir);
        assertEquals(1, back.areas.size());
        KeepPlan.Area a = back.areas.get(0);
        assertEquals(List.of(1, 2, 3, 4, 5, 6), List.of(a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ()));
        assertEquals("a gate (Waystation)", a.reason());
    }
}
