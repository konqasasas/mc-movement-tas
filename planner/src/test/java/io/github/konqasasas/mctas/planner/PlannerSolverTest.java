package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PlannerSolverTest {
    private static PlannerSolver.Solution solve(String id, long millis) throws Exception {
        Problem p = ProblemIO.read(Paths.get("../problems/" + id + ".problem.json"));
        PlannerSolver.Solution sol = new PlannerSolver(p, millis).segments(false).solve();
        assertNotNull(id + " unsolved", sol);
        RunResult r = Evaluator.run(p, sol.inputs);
        assertTrue(r.finished());
        assertEquals(sol.ticks, r.ticks());
        assertTrue(ToggleRule.violations(p, sol.inputs).isEmpty());
        return sol;
    }

    @Test
    public void solvesShortJumpsAtTheirTargets() throws Exception {
        assertEquals(4, solve("v0/02-gap-1", 3000).ticks);
        assertEquals(11, solve("v0/04-gap-3", 3000).ticks);
    }

    @Test
    public void solvesAZigzagCourse() throws Exception {
        assertTrue(solve("v0/10-three-jumps-zigzag", 5000).ticks <= 35);
    }

    @Test
    public void airControlLandsOnTheAimedTop() {
        // Falling from 1.25 above a top: the landing tick is found from the vertical motion alone.
        assertEquals(-1, Step.movesBeforeLanding(64.0, -0.1, 65.0));
        assertTrue(Step.movesBeforeLanding(66.25, 0.0, 65.0) > 0);
    }
}
