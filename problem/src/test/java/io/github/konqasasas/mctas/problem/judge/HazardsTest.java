package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.Spawn;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * D-005 in the judge: a run along a stone path to a goal 8 blocks ahead is invalid when it touches
 * lava, fire or cactus, walks on magma without sneaking, or lands on farmland after falling more
 * than 0.5; walking on farmland or sneaking over magma is fine.
 */
public class HazardsTest {

    /** A stone path along z from 0 to 12 at y = 0, with {@code block} at (x, y, 4..6), walked along from x = 0.5. */
    private static Problem course(String block, int x, int y) {
        return course(block, x, y, 0.5);
    }

    private static Problem course(String block, int x, int y, double spawnX) {
        SparseBlockWorld world = new SparseBlockWorld();
        world.fill(-2, 0, 0, 2, 0, 12, VanillaBlocks.require("minecraft:stone"));
        if (block != null) world.fill(x, y, 4, x, y, 6, VanillaBlocks.require(block));
        return new Problem("hazard", "", world, new Spawn(new Vec3(spawnX, 1, 0.5), 0, 0),
            new Region(0, 1, 0, 1, 2, 1), new Region(-2, 1, 9, 3, 3, 10), 400, -64, null);
    }

    private static RunResult walk(Problem p, Key... keys) {
        List<TickInput> in = new ArrayList<TickInput>();
        for (int t = 0; t < 300; t++) {
            EnumSet<Key> k = EnumSet.of(Key.W);
            for (Key key : keys) k.add(key);
            in.add(new TickInput(k, 0, 0));
        }
        return Evaluator.run(p, in);
    }

    @Test
    public void aCleanPathFinishes() {
        assertEquals(RunResult.Status.FINISHED, walk(course(null, 0, 0)).status);
    }

    @Test
    public void walkingThroughLavaOrFireHurts() {
        for (String b : new String[] {"minecraft:lava[level=0]", "minecraft:fire"}) {
            RunResult r = walk(course(b, 0, 1));
            assertEquals(b, RunResult.Status.HURT, r.status);
            assertEquals("damage", r.violation);
        }
    }

    @Test
    public void brushingACactusHurts() {
        // A cactus to the side (x = 1): its box starts at 1.0625, so a run at x = 0.75 (box up to
        // 1.05) passes it without a collision but overlaps its block; one at x = 0.5 does not.
        assertEquals(RunResult.Status.HURT, walk(course("minecraft:cactus", 1, 1, 0.75)).status);
        assertEquals(RunResult.Status.FINISHED, walk(course("minecraft:cactus", 1, 1, 0.5)).status);
    }

    @Test
    public void magmaHurtsUnlessSneaking() {
        Problem p = course("minecraft:magma", 0, 0);
        RunResult r = walk(p);
        assertEquals(RunResult.Status.HURT, r.status);
        assertTrue("on the first tick over the magma: " + r.violationTick, r.violationTick > 5);
        assertEquals(RunResult.Status.FINISHED, walk(p, Key.SNEAK).status);
    }

    @Test
    public void farmlandIsTrampledByAJumpButNotByWalking() {
        Problem p = course("minecraft:farmland", 0, 0);
        assertEquals(RunResult.Status.FINISHED, walk(p).status);
        RunResult r = walk(p, Key.JUMP);
        assertEquals(RunResult.Status.HURT, r.status);
        assertEquals("trampling", r.violation);
    }
}
