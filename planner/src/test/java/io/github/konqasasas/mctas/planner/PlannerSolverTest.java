package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.testing.Slow;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The searches run against the clock, so they are slow tests (`slowTest`); only the controller checks run in `test`. */
public class PlannerSolverTest {
    private static PlannerSolver.Solution solve(String id, long millis) throws Exception {
        return solve(Paths.get("../problems/" + id + ".problem.json"), millis);
    }

    private static PlannerSolver.Solution solve(java.nio.file.Path path, long millis) throws Exception {
        String id = path.toString();
        Problem p = ProblemIO.read(path);
        PlannerSolver.Solution sol = new PlannerSolver(p, millis).segments(false).solve();
        assertNotNull(id + " unsolved", sol);
        RunResult r = Evaluator.run(p, sol.inputs);
        assertTrue(r.finished());
        assertEquals(sol.ticks, r.ticks());
        assertTrue(ToggleRule.violations(p, sol.inputs).isEmpty());
        return sol;
    }

    @Test
    @Category(Slow.class)
    public void solvesShortJumpsAtTheirTargets() throws Exception {
        assertEquals(4, solve("v0/02-gap-1", 3000).ticks);
        assertEquals(11, solve("v0/04-gap-3", 3000).ticks);
    }

    @Test
    @Category(Slow.class)
    public void solvesAZigzagCourse() throws Exception {
        assertTrue(solve("v0/10-three-jumps-zigzag", 5000).ticks <= 35);
    }

    @Test
    @Category(Slow.class)
    public void climbsALadderUpAWall() throws Exception {
        solve(Paths.get("src/test/resources/climb/ladder-wall.problem.json"), 10000);
    }

    @Test
    @Category(Slow.class)
    public void crossesAGapAlongVinesSneakingToHoldTheHeight() throws Exception {
        // Eleven blocks with no floor: only sliding along the vines, sneaking so as not to drop out of them, gets across.
        solve(Paths.get("src/test/resources/climb/vine-gap.problem.json"), 10000);
    }

    @Test
    public void airControlLandsOnTheAimedTop() {
        // Falling from 1.25 above a top: the landing tick is found from the vertical motion alone.
        assertEquals(-1, Step.movesBeforeLanding(64.0, -0.1, 65.0));
        assertTrue(Step.movesBeforeLanding(66.25, 0.0, 65.0) > 0);
    }
}
