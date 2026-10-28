package io.github.konqasasas.mctas.improve;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImproveTest {
    private static final Path V0 = Paths.get("..", "problems", "v0");

    private static Problem problem(String name) throws Exception {
        return ProblemIO.read(V0.resolve(name + ".problem.json"));
    }

    private static PlayerInput[] witness(Problem p, String name) throws Exception {
        String text = new String(Files.readAllBytes(V0.resolve(name + ".tas")), StandardCharsets.UTF_8);
        List<TickInput> in = InputText.parse(text, p.spawn);
        PlayerInput[] out = new PlayerInput[in.size()];
        for (int i = 0; i < out.length; i++) out[i] = PhysicsModel.toPhysics(in.get(i));
        return out;
    }

    @Test
    public void courseJudgesLikeTheProblemModule() throws Exception {
        for (String name : new String[] {"04-gap-3", "08-two-jumps"}) {
            Problem p = problem(name);
            PlayerInput[] in = witness(p, name);
            List<TickInput> ti = new ArrayList<TickInput>();
            for (PlayerInput x : in) ti.add(io.github.konqasasas.mctas.improve.cli.ImproveMain.toTick(x));
            RunResult rr = Evaluator.run(p, ti);
            Run r = new Course(p).evaluate(in);
            assertTrue(name + ": " + r, r.valid());
            assertEquals(name, rr.ticks(), r.ticks());
            assertEquals(name, rr.startTick, r.startTick());
            assertEquals(name, rr.goalTick, r.goalTick());
        }
    }

    @Test
    public void evaluatingFromATickReusesThePrefix() throws Exception {
        Problem p = problem("08-two-jumps");
        Course c = new Course(p);
        PlayerInput[] in = witness(p, "08-two-jumps");
        Run full = c.evaluate(in);
        Random rnd = new Random(3);
        for (int k = 0; k < 200; k++) {
            Run m = Micro.mutate(c, full, rnd);
            if (m == null) continue;
            Run again = c.evaluate(m.inputs());
            assertEquals(m.valid(), again.valid());
            if (m.valid()) {
                assertEquals(m.ticks(), again.ticks());
                assertEquals(m.slack(), again.slack(), 0);
            }
        }
    }

    @Test
    public void toggleGapMakesARunInvalid() throws Exception {
        Problem p = problem("04-gap-3");
        PlayerInput[] in = witness(p, "04-gap-3");
        // Drop sprint for one tick on the run-up, then take it back: two changes one tick apart.
        int t = 3;
        in[t] = new PlayerInput(in[t].keys & ~PlayerInput.SPRINT & ~PlayerInput.W, in[t].yaw, 0);
        Run r = new Course(p).evaluate(in);
        assertFalse(r.valid());
        assertTrue(ToggleFix.clash(r) > 0);
    }

    @Test
    public void toggleFixMendsAClash() throws Exception {
        Problem p = problem("08-two-jumps");
        Course c = new Course(p);
        PlayerInput[] in = witness(p, "08-two-jumps");
        int t = c.evaluate(in).startTick() / 2;
        in[t] = new PlayerInput(in[t].keys & ~PlayerInput.SPRINT & ~PlayerInput.W, in[t].yaw, 0);
        Run r = c.evaluate(in);
        assertTrue(r.toString(), ToggleFix.clash(r) > 0);
        Run fixed = new ToggleFix().apply(c, r, new Random(1));
        assertTrue(r + " -> " + fixed, fixed.valid());
    }

    @Test
    public void improverRecoversTicksLostInASlowedRun() throws Exception {
        Problem p = problem("08-two-jumps");
        Course c = new Course(p);
        PlayerInput[] in = witness(p, "08-two-jumps");
        Run base = c.evaluate(in);
        // Slow the witness down: stop moving for a few ticks after the start.
        List<PlayerInput> slow = new ArrayList<PlayerInput>();
        for (int i = 0; i < in.length; i++) {
            if (i == base.startTick() + 1) {
                for (int k = 0; k < 3; k++) slow.add(PlayerInput.of(PlayerInput.SPRINT, 0));
            }
            slow.add(in[i]);
        }
        Run seed = c.evaluate(slow.toArray(new PlayerInput[0]));
        if (!seed.valid()) return; // the slowed run falls; nothing to test on this witness
        assertTrue(seed.ticks() > base.ticks());
        Improver.Options o = new Improver.Options();
        o.threads = 2;
        o.maxWidth = 1000;
        Improver imp = new Improver(c, o);
        imp.seed(seed);
        Run best = imp.run(System.nanoTime() + 8_000_000_000L);
        assertTrue("best " + best + ", seed " + seed, best.ticks() < seed.ticks());
    }

    @Test
    public void cmaMinimisesAQuadratic() {
        Cma cma = new Cma(new double[] {3, -2, 1}, 1, new Random(1));
        for (int g = 0; g < 200; g++) {
            double[][] xs = cma.ask();
            double[] f = new double[xs.length];
            for (int k = 0; k < xs.length; k++) {
                double[] x = xs[k];
                f[k] = (x[0] - 1) * (x[0] - 1) + 10 * (x[1] + x[0]) * (x[1] + x[0]) + 3 * x[2] * x[2];
            }
            cma.tell(xs, f);
        }
        assertEquals(1, cma.mean[0], 1e-3);
        assertEquals(-1, cma.mean[1], 1e-3);
        assertEquals(0, cma.mean[2], 1e-3);
    }

    @Test
    public void windowInputsAreDistinctAndMostUsedFirst() {
        PlayerInput run = PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT, 0);
        PlayerInput jump = PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 0);
        PlayerInput sneak = PlayerInput.of(PlayerInput.W | PlayerInput.SNEAK, 90);
        PlayerInput[] in = {jump, run, run, sneak, run, sneak};
        List<PlayerInput> w = Rebuild.windowInputs(in, 0, in.length);
        assertEquals(3, w.size());
        assertEquals(run, w.get(0));
        assertEquals(sneak, w.get(1));
        assertEquals(jump, w.get(2));
        assertEquals(2, Rebuild.windowInputs(in, 3, 5).size());
    }
}
