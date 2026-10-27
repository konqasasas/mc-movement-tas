package io.github.konqasasas.mctas.problem;

import io.github.konqasasas.mctas.problem.judge.Judge;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import io.github.konqasasas.mctas.problem.world.BlockWorld;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JudgeTest {
    private static Problem line(double spawnZ) {
        return new Problem("t", "t", new BlockWorld(), new Spawn(new Vec3(0.5, 65, spawnZ), 0, 0),
            new Region(0, 60, 0, 1, 70, 1), new Region(0, 65, 5, 1, 65, 6), 100, 0, null);
    }

    private static RunResult walk(Problem p, double... zs) {
        Judge j = new Judge(p);
        for (double z : zs) {
            if (j.observe(new Vec3(0.5, 65, z))) break;
        }
        return j.result();
    }

    @Test
    public void timeRunsFromStartEntryToFirstGoalTick() {
        RunResult r = walk(line(-1), -0.5, 0.0, 2.0, 4.9, 5.0, 5.5);
        assertTrue(r.finished());
        assertEquals(2, r.startTick);
        assertEquals(5, r.goalTick);
        assertEquals(3, r.ticks());
    }

    @Test
    public void reEnteringStartRestartsTheClock() {
        RunResult r = walk(line(-1), 0.5, 1.5, 0.9, 3.0, 5.0);
        assertEquals(3, r.startTick);
        assertEquals(2, r.ticks());
    }

    @Test
    public void stayingInsideStartIsNotANewEntry() {
        RunResult r = walk(line(-1), 0.2, 0.4, 0.6, 5.0);
        assertEquals(1, r.startTick);
        assertEquals(3, r.ticks());
    }

    @Test
    public void spawnInsideStartCountsAsEntryAtTickZero() {
        RunResult r = walk(line(0.5), 2.0, 5.0);
        assertEquals(0, r.startTick);
        assertEquals(2, r.ticks());
    }

    @Test
    public void goalWithoutStartDoesNotFinish() {
        RunResult r = walk(line(3), 5.0, 5.5);
        assertFalse(r.finished());
    }

    @Test
    public void boundariesAreInclusive() {
        Region g = new Region(0, 65, 5, 1, 65, 6);
        assertTrue(g.contains(1.0, 65.0, 6.0));
        assertFalse(g.contains(1.0, 65.0000001, 6.0));
    }

    @Test
    public void fallingBelowVoidYFails() {
        Judge j = new Judge(line(-1));
        j.observe(new Vec3(0.5, 65, 0.5));
        assertTrue(j.observe(new Vec3(0.5, -1, 0.5)));
        assertEquals(RunResult.Status.FELL_INTO_VOID, j.result().status);
    }
}
