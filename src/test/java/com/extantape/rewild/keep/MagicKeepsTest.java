package com.extantape.rewild.keep;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MagicKeepsTest {
    @Test
    void aMagicWorldWithoutMagicRunningFailsClosed() {
        assertNotNull(MagicKeeps.problem("magic", Set.of("magic"), false, null));
    }

    @Test
    void otherWorldsDontNeedMagic() {
        assertNull(MagicKeeps.problem("survival", Set.of("magic"), false, null));
    }

    @Test
    void magicThatCantAnswerStopsResetsEverywhere() {
        assertNotNull(MagicKeeps.problem("survival", Set.of("magic"), true, "NoSuchMethodException"));
        assertNotNull(MagicKeeps.problem("magic", Set.of("magic"), true, "NoSuchMethodException"));
    }

    @Test
    void magicThatAnswersIsFine() {
        assertNull(MagicKeeps.problem("magic", Set.of("magic"), true, null));
    }

    @Test
    void aMagicBlockKeepsItsStructureAroundIt() {
        KeepPlan.Area a = MagicKeeps.around("magic", 10, 64, -5, 8, "a Magic block");
        assertEquals(2, a.minX());
        assertEquals(18, a.maxX());
        assertEquals(56, a.minY());
        assertEquals(72, a.maxY());
        assertEquals(-13, a.minZ());
        assertEquals(3, a.maxZ());
    }
}
