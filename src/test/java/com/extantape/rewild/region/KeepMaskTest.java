package com.extantape.rewild.region;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What holds a kept block up, and the other half of two-block things, so nothing kept floats or splits. */
class KeepMaskTest {
    final Map<String, KeepMask.State> world = new HashMap<>();

    void put(int x, int y, int z, String name, String... props) {
        Map<String, String> p = new HashMap<>();
        for (int i = 0; i + 1 < props.length; i += 2) p.put(props[i], props[i + 1]);
        world.put(x + "," + y + "," + z, new KeepMask.State(name, p));
    }

    KeepMask keep(int x, int y, int z) {
        KeepMask m = new KeepMask(-64, 384);
        m.keep(x, y, z);
        m.addSupports((a, b, c) -> world.getOrDefault(a + "," + b + "," + c, new KeepMask.State("minecraft:stone", Map.of())));
        return m;
    }

    @Test
    void standingBlocksKeepTheBlockBelow() {
        put(5, 70, 5, "minecraft:oak_fence");
        KeepMask m = keep(5, 70, 5);
        assertTrue(m.kept(5, 69, 5));
        assertEquals(2, m.count());
    }

    @Test
    void hangingThingsKeepTheBlockAbove() {
        put(5, 70, 5, "minecraft:lantern", "hanging", "true");
        assertTrue(keep(5, 70, 5).kept(5, 71, 5));
        world.clear();
        put(5, 70, 5, "minecraft:oak_hanging_sign");
        assertTrue(keep(5, 70, 5).kept(5, 71, 5));
        world.clear();
        put(5, 70, 5, "minecraft:stone_button", "face", "ceiling");
        assertTrue(keep(5, 70, 5).kept(5, 71, 5));
    }

    @Test
    void wallThingsKeepTheBlockBehindThem() {
        // A wall torch facing north sits on the block to its south.
        put(5, 70, 5, "minecraft:wall_torch", "facing", "north");
        KeepMask m = keep(5, 70, 5);
        assertTrue(m.kept(5, 70, 6));
        assertFalse(m.kept(5, 69, 5));
        world.clear();
        put(5, 70, 5, "minecraft:ladder", "facing", "east");
        assertTrue(keep(5, 70, 5).kept(4, 70, 5));
        world.clear();
        put(5, 70, 5, "minecraft:lever", "face", "wall", "facing", "south");
        assertTrue(keep(5, 70, 5).kept(5, 70, 4));
    }

    @Test
    void doorsBedsAndTallPlantsKeepBothHalvesAndWhatTheyStandOn() {
        put(5, 70, 5, "minecraft:oak_door", "half", "lower");
        put(5, 71, 5, "minecraft:oak_door", "half", "upper");
        KeepMask m = keep(5, 71, 5);
        assertTrue(m.kept(5, 70, 5) && m.kept(5, 69, 5));
        world.clear();
        // A bed's foot, facing east: its head is one block east.
        put(5, 70, 5, "minecraft:red_bed", "part", "foot", "facing", "east");
        put(6, 70, 5, "minecraft:red_bed", "part", "head", "facing", "east");
        m = keep(5, 70, 5);
        assertTrue(m.kept(6, 70, 5) && m.kept(5, 69, 5) && m.kept(6, 69, 5));
    }

    @Test
    void airAndWaterNeedNothing() {
        put(5, 70, 5, "minecraft:air");
        assertEquals(1, keep(5, 70, 5).count());
        world.clear();
        put(5, 70, 5, "minecraft:water");
        assertEquals(1, keep(5, 70, 5).count());
    }

    @Test
    void positionsOutsideTheChunkAreIgnored() {
        put(15, 70, 5, "minecraft:wall_torch", "facing", "west");
        KeepMask m = keep(15, 70, 5);
        assertEquals(1, m.count(), "the torch's wall is in the next chunk, which isn't changing");
        assertFalse(m.inRange(16, 70, 5));
    }
}
