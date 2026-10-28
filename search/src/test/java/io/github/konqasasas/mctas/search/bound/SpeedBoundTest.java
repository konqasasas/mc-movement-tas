package io.github.konqasasas.mctas.search.bound;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import io.github.konqasasas.mctas.search.exact.ExactModel;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The speed bound is never above a known run, and random runs on the exact physics never go farther
 * in {@code k} ticks than it allows nor land sooner after a jump.
 */
public class SpeedBoundTest {

    private static final String[] SETS = {"v0", "v1-dev", "fall", "place"};

    @Test
    public void neverAboveTheKnownRuns() throws IOException {
        int checked = 0;
        for (Problem p : problems()) {
            if (p.target == null) continue;
            int lb = new SpeedBound(p).ticks(p.maxTicks);
            assertTrue(p.id + ": bound " + lb + " above the run of " + p.target.ticks, lb <= p.target.ticks);
            checked++;
        }
        assertTrue(checked >= 18);
    }

    @Test
    public void everyWitnessStateNeedsNoMoreThanItTook() throws IOException {
        int checked = 0;
        for (String set : SETS) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("..", "problems", set), "*.problem.json")) {
                for (Path f : ds) {
                    Problem p = ProblemIO.read(f);
                    if (p.target == null || p.target.witness == null) continue;
                    String text = new String(Files.readAllBytes(f.resolveSibling(p.target.witness)), StandardCharsets.UTF_8);
                    List<TickInput> in = InputText.parse(text, p.spawn);
                    RunResult res = Evaluator.run(p, in);
                    SpeedBound sb = new SpeedBound(p);
                    ExactModel model = new ExactModel(p);
                    PlayerState s = model.spawn(p);
                    for (int t = 0; t < res.goalTick; t++) {
                        int lb = sb.ticksFrom(s.posX, s.box.minY, s.posZ, s.motionX, s.motionZ);
                        assertTrue(p.id + " tick " + t + ": " + lb + " > " + (res.goalTick - t), lb <= res.goalTick - t);
                        s = model.tick(s, in.get(t));
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked >= 18);
    }

    @Test
    public void randomRunsKeepTheLimits() throws IOException {
        for (Problem p : problems()) {
            SpeedBound sb = new SpeedBound(p);
            double[] far = sb.reach(60, Double.POSITIVE_INFINITY);
            ExactModel model = new ExactModel(p);
            Random rnd = new Random(p.id.hashCode());
            int jumps = 0;
            for (int r = 0; r < 60; r++) {
                List<PlayerState> path = new ArrayList<PlayerState>();
                PlayerState s = model.spawn(p);
                path.add(s);
                int keys = PlayerInput.W | PlayerInput.SPRINT;
                float yaw = p.spawn.yaw;
                for (int t = 1; t <= 150 && s.posY > p.voidY; t++) {
                    if (rnd.nextDouble() < 0.2) keys = randomKeys(rnd);
                    if (rnd.nextDouble() < 0.2) yaw += (float) (rnd.nextGaussian() * 30);
                    int k = keys;
                    if (rnd.nextDouble() < 0.5) k |= PlayerInput.JUMP;
                    s = model.tick(s, PlayerInput.of(k, yaw));
                    path.add(s);
                }
                for (int i = 0; i < path.size(); i++) {
                    PlayerState a = path.get(i);
                    for (int k = 1; k < far.length && i + k < path.size(); k++) {
                        PlayerState b = path.get(i + k);
                        double d = Math.hypot(b.posX - a.posX, b.posZ - a.posZ);
                        if (d > far[k] + 1e-7) fail(p.id + " run " + r + ": " + d + " in " + k + " ticks from " + a);
                    }
                    if (i == 0 || !path.get(i - 1).onGround || a.onGround || a.posY <= path.get(i - 1).posY) continue;
                    // A jump (or a bounce) left the ground on tick i.
                    for (int j = i + 1; j < path.size(); j++) {
                        if (!path.get(j).onGround) continue;
                        jumps++;
                        if (j - i < sb.airTicks) fail(p.id + " run " + r + ": landed " + (j - i) + " ticks after the jump at " + a);
                        break;
                    }
                }
            }
            if (p.inventory.isEmpty()) assertTrue(p.id + ": too few jumps", jumps > 20);
        }
    }

    private static List<Problem> problems() throws IOException {
        List<Problem> out = new ArrayList<Problem>();
        for (String set : SETS) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("..", "problems", set), "*.problem.json")) {
                for (Path f : ds) out.add(ProblemIO.read(f));
            }
        }
        return out;
    }

    private static int randomKeys(Random rnd) {
        int k = 0;
        if (rnd.nextDouble() < 0.9) k |= PlayerInput.W;
        if (rnd.nextDouble() < 0.8) k |= PlayerInput.SPRINT;
        if (rnd.nextDouble() < 0.25) k |= PlayerInput.A;
        if (rnd.nextDouble() < 0.25) k |= PlayerInput.D;
        if (rnd.nextDouble() < 0.05) k |= PlayerInput.S;
        if (rnd.nextDouble() < 0.08) k |= PlayerInput.SNEAK;
        return k;
    }
}
