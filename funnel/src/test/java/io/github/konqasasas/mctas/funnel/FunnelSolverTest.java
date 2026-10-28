package io.github.konqasasas.mctas.funnel;

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

/** The solver runs against the clock, so these are slow tests (`slowTest`). */
public class FunnelSolverTest {
    private static FunnelSolver.Solution solve(String id, long millis) throws Exception {
        Problem p = ProblemIO.read(Paths.get("../problems/" + id + ".problem.json"));
        FunnelSolver.Solution sol = new FunnelSolver(p, millis).threads(2).solve();
        assertNotNull(id + " unsolved", sol);
        RunResult r = Evaluator.run(p, sol.inputs);
        assertTrue(r.finished());
        assertEquals(sol.ticks, r.ticks());
        assertTrue(ToggleRule.violations(p, sol.inputs).isEmpty());
        return sol;
    }

    @Test
    @Category(Slow.class)
    public void solvesShortJumpsAfterAFreeRunUp() throws Exception {
        assertTrue(solve("v0/02-gap-1", 8000).ticks <= 6);
        assertTrue(solve("v0/05-up-1", 8000).ticks <= 10);
    }

    @Test
    @Category(Slow.class)
    public void solvesAZigzagCourse() throws Exception {
        assertTrue(solve("v0/10-three-jumps-zigzag", 8000).ticks <= 40);
    }
}
