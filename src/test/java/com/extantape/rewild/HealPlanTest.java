package com.extantape.rewild;

import static org.junit.jupiter.api.Assertions.*;

import com.extantape.rewild.heal.HealPlan;
import com.extantape.rewild.heal.HealPlan.Cell;
import com.extantape.rewild.heal.HealPlan.Placement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class HealPlanTest {
    static final Predicate<String> PLANTS = m -> Set.of("short_grass", "poppy", "snow", "fern").contains(m);

    /** A crater: a 5 by 5 bowl, 4 layers deep, grass on top, stone below. */
    static List<Cell> crater() {
        List<Cell> cells = new ArrayList<>();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
            cells.add(new Cell(x, 63, z, "minecraft:grass_block[snowy=false]"));
            cells.add(new Cell(x, 62, z, "minecraft:dirt"));
            cells.add(new Cell(x, 61, z, "minecraft:stone"));
            if (x > 0 && x < 4 && z > 0 && z < 4) cells.add(new Cell(x, 60, z, "minecraft:stone"));
        }
        cells.add(new Cell(2, 64, 2, "minecraft:poppy"));
        return cells;
    }

    static int stepOf(List<List<Placement>> steps, int x, int y, int z, String data) {
        for (int i = 0; i < steps.size(); i++)
            for (Placement p : steps.get(i)) if (p.x() == x && p.y() == y && p.z() == z && p.data().equals(data)) return i;
        return -1;
    }

    @Test
    void aTypicalCraterTakesAboutAWeekAndFillsBottomUp() {
        List<List<Placement>> steps = HealPlan.steps(crater(), 7, PLANTS);
        assertTrue(steps.size() >= 5 && steps.size() <= 7, "about a week of daily steps: " + steps.size());
        assertEquals(0, stepOf(steps, 2, 60, 2, "minecraft:stone"), "the bottom layer first, the day after");
        assertTrue(stepOf(steps, 2, 61, 2, "minecraft:stone") > 0, "the next layer later");
        assertTrue(stepOf(steps, 2, 62, 2, "minecraft:dirt") > stepOf(steps, 2, 61, 2, "minecraft:stone"));
        for (List<Placement> s : steps)
            for (int i = 1; i < s.size(); i++) assertTrue(s.get(i).y() >= s.get(i - 1).y(), "each step places bottom up");
    }

    @Test
    void grassComesBackAsDirtFirstThenGrassReplacingIt() {
        List<List<Placement>> steps = HealPlan.steps(crater(), 7, PLANTS);
        int dirt = stepOf(steps, 0, 63, 0, "minecraft:dirt");
        int grass = stepOf(steps, 0, 63, 0, "minecraft:grass_block[snowy=false]");
        assertTrue(dirt >= 0 && grass > dirt, "dirt at step " + dirt + ", grass at " + grass);
        Placement g = steps.get(grass).stream().filter(p -> p.x() == 0 && p.y() == 63 && p.z() == 0).findFirst().orElseThrow();
        assertEquals("minecraft:dirt", g.over(), "the grass may replace the dirt laid before it, and nothing else solid");
    }

    @Test
    void smallPlantsComeBackLast() {
        List<List<Placement>> steps = HealPlan.steps(crater(), 7, PLANTS);
        assertEquals(steps.size() - 1, stepOf(steps, 2, 64, 2, "minecraft:poppy"));
    }

    @Test
    void aFelledTreeComesBackAsASaplingThenAYoungTreeThenWhole() {
        List<Cell> cells = new ArrayList<>();
        cells.add(new Cell(0, 63, 0, "minecraft:grass_block"));
        for (int y = 64; y <= 69; y++) cells.add(new Cell(0, y, 0, "minecraft:oak_log[axis=y]"));
        cells.add(new Cell(1, 66, 0, "minecraft:oak_leaves[distance=1,persistent=false]")); // near the lower trunk
        cells.add(new Cell(1, 70, 0, "minecraft:oak_leaves[distance=1,persistent=false]")); // up top
        List<List<Placement>> steps = HealPlan.steps(cells, 7, PLANTS);
        int sapling = stepOf(steps, 0, 64, 0, "minecraft:oak_sapling");
        int youngBase = stepOf(steps, 0, 64, 0, "minecraft:oak_log[axis=y]");
        int top = stepOf(steps, 0, 69, 0, "minecraft:oak_log[axis=y]");
        assertTrue(sapling > stepOf(steps, 0, 63, 0, "minecraft:dirt"), "the sapling goes in after the ground");
        assertTrue(youngBase > sapling, "then the young trunk replaces it");
        assertEquals("minecraft:oak_sapling", steps.get(youngBase).stream().filter(p -> p.y() == 64).findFirst().orElseThrow().over());
        assertEquals(youngBase, stepOf(steps, 0, 66, 0, "minecraft:oak_log[axis=y]"), "the lower half comes with the young tree");
        assertEquals(youngBase, stepOf(steps, 1, 66, 0, "minecraft:oak_leaves[distance=1,persistent=false]"), "with the leaves near it");
        assertTrue(top > youngBase, "the top of the trunk comes last");
        assertEquals(top, stepOf(steps, 1, 70, 0, "minecraft:oak_leaves[distance=1,persistent=false]"));
    }

    @Test
    void touchingLogsAreOneTreeAndSeparateOnesAreTwo() {
        List<Cell> cells = new ArrayList<>();
        for (int y = 64; y <= 66; y++) cells.add(new Cell(0, y, 0, "minecraft:birch_log"));
        cells.add(new Cell(1, 67, 1, "minecraft:birch_log")); // diagonal branch: same tree
        for (int y = 64; y <= 66; y++) cells.add(new Cell(5, y, 5, "minecraft:spruce_log"));
        List<List<Placement>> steps = HealPlan.steps(cells, 7, PLANTS);
        long saplings = steps.stream().flatMap(List::stream).filter(p -> p.data().endsWith("_sapling")).count();
        assertEquals(2, saplings, "one sapling per tree");
        assertTrue(stepOf(steps, 0, 64, 0, "minecraft:birch_sapling") >= 0);
        assertTrue(stepOf(steps, 5, 64, 5, "minecraft:spruce_sapling") >= 0);
    }

    @Test
    void saplingsMatchTheWoodAndStrippedOrUnknownLogsGetNone() {
        List<Cell> cells = List.of(new Cell(0, 64, 0, "minecraft:mangrove_log"), new Cell(9, 64, 9, "minecraft:stripped_oak_log"),
            new Cell(20, 64, 20, "minecraft:crimson_stem"));
        List<List<Placement>> steps = HealPlan.steps(cells, 7, PLANTS);
        Set<String> placedSaplings = new HashSet<>();
        steps.stream().flatMap(List::stream).filter(p -> p.y() == 64 && !p.data().contains("log") && !p.data().contains("stem")).forEach(p -> placedSaplings.add(p.data()));
        assertEquals(Set.of("minecraft:mangrove_propagule", "minecraft:crimson_fungus"), placedSaplings);
        assertEquals(1, steps.stream().flatMap(List::stream).filter(p -> p.data().equals("minecraft:stripped_oak_log")).count(), "it still comes back, just without a sapling");
    }

    @Test
    void aDeepHoleTakesMoreLayersPerStepNotMoreDays() {
        List<Cell> cells = new ArrayList<>();
        for (int y = 30; y < 60; y++) cells.add(new Cell(0, y, 0, "minecraft:stone"));
        List<List<Placement>> steps = HealPlan.steps(cells, 7, PLANTS);
        assertEquals(4, steps.size(), "30 layers spread over the 4 ground steps of a week");
        assertEquals(30, steps.stream().mapToInt(List::size).sum(), "every block is placed exactly once");
    }

    @Test
    void airIsNeverPlacedAndEveryRecordedBlockComesBackOnce() {
        List<Cell> cells = new ArrayList<>(crater());
        cells.add(new Cell(9, 70, 9, "minecraft:air"));
        cells.add(new Cell(9, 71, 9, "minecraft:cave_air"));
        List<List<Placement>> steps = HealPlan.steps(cells, 7, PLANTS);
        long finals = steps.stream().flatMap(List::stream).filter(p -> !p.data().equals("minecraft:dirt") || p.over() != null).count();
        assertTrue(steps.stream().flatMap(List::stream).noneMatch(p -> p.data().contains("air")));
        long grass = crater().stream().filter(c -> c.data().startsWith("minecraft:grass_block")).count();
        assertEquals(crater().size() + grass, steps.stream().mapToInt(List::size).sum(), "each block once, plus dirt before each grass");
        assertTrue(finals > 0);
    }

    @Test
    void nothingToHealMeansNoSteps() {
        assertTrue(HealPlan.steps(List.of(), 7, PLANTS).isEmpty());
        assertTrue(HealPlan.steps(List.of(new Cell(0, 64, 0, "minecraft:air")), 7, PLANTS).isEmpty());
    }
}
