package io.github.konqasasas.mctas.search.bound;

import io.github.konqasasas.mctas.physics.testing.Slow;
import org.junit.experimental.categories.Category;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.testing.RandomScenario;
import io.github.konqasasas.mctas.physics.testing.Slow;
import org.junit.experimental.categories.Category;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.Spawn;
import io.github.konqasasas.mctas.problem.Vec3;
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
 * The hop bound is never above a known run, nor, from a landing, above what the run took from
 * there; and on random courses (full blocks, every shape, liquids) the bound between two states of
 * a random run is never above the ticks the run took between them.
 */
public class HopBoundTest {

    private static final String[] SETS = {"v0", "v1-dev", "fall", "place"};

    @Test
    @Category(Slow.class)
    public void neverAboveTheKnownRuns() throws IOException {
        int checked = 0;
        for (String set : SETS) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("..", "problems", set), "*.problem.json")) {
                for (Path f : ds) {
                    Problem p = ProblemIO.read(f);
                    if (p.target == null || p.target.witness == null) continue;
                    HopBound hb = new HopBound(p);
                    assertTrue(p.id + ": " + hb.ticks() + " > " + p.target.ticks, hb.ticks() <= p.target.ticks);
                    String text = new String(Files.readAllBytes(f.resolveSibling(p.target.witness)), StandardCharsets.UTF_8);
                    List<TickInput> in = InputText.parse(text, p.spawn);
                    RunResult res = Evaluator.run(p, in);
                    ExactModel model = new ExactModel(p);
                    PlayerState s = model.spawn(p);
                    List<PlayerState> path = new ArrayList<PlayerState>();
                    for (int t = 0; t < res.goalTick; t++) {
                        path.add(s);
                        if (s.onGround) {
                            int lb = hb.ticksFromGround(s.posX, s.box.minY, s.posZ);
                            assertTrue(p.id + " tick " + t + ": " + lb + " > " + (res.goalTick - t), lb <= res.goalTick - t);
                        }
                        s = model.tick(s, in.get(t));
                    }
                    path.add(s);
                    // Pieces of the (nearly fastest) run: the bound between two of its states.
                    Random rnd = new Random(p.id.hashCode());
                    for (int k = 0; k < 4; k++) {
                        int i = res.startTick + rnd.nextInt(Math.max(1, res.goalTick - res.startTick - 2));
                        int j = Math.min(res.goalTick, i + 2 + rnd.nextInt(30));
                        int took = segment(p, path, i, j);
                        if (took < 0) fail(p.id + " pieces " + i + ".." + j + ": bound above the run");
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked >= 18);
    }

    @Test
    @Category(Slow.class)
    public void randomSegmentsTakeNoLessThanTheBound() {
        int checked = 0;
        for (String course : new String[] {"full", "shapes", "fluids"}) {
            for (long seed = 1; seed <= 15; seed++) {
                RandomScenario sc = RandomScenario.generate(course, seed, 200);
                List<PlayerState> path = new ArrayList<PlayerState>();
                PlayerState s = sc.start;
                path.add(s);
                for (int t = 0; t < sc.inputs.size(); t++) {
                    s = Physics.tick(sc.world, s, sc.inputs.get(t));
                    if (s.posY < 0 || s.unsupportedAction) break;
                    path.add(s);
                }
                Random rnd = new Random(seed * 31 + course.hashCode());
                for (int k = 0; k < 3 && path.size() > 20; k++) {
                    int i = rnd.nextInt(path.size() - 10);
                    int j = Math.min(path.size() - 1, i + 1 + rnd.nextInt(60));
                    PlayerState a = path.get(i), b = path.get(j);
                    Problem p = new Problem(course + seed, "", sc.world, new Spawn(new Vec3(a.posX, a.posY, a.posZ), 0, 0),
                        box(a.posX, a.posY, a.posZ), box(b.posX, b.posY, b.posZ), 400, -64, null);
                    if (segment(p, path, i, j) < 0) {
                        fail(course + " seed " + seed + ": bound from tick " + i + " to " + j + " above the run (" + a + " -> " + b + ")");
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 100);
    }

    /**
     * Checks the bound from state {@code i} to entering a small box round state {@code j} (also from
     * its speed when it is on the ground); the run's ticks, or -1.
     */
    private static int segment(Problem p, List<PlayerState> path, int i, int j) {
        PlayerState a = path.get(i), b = path.get(j);
        Region from = box(a.posX, a.posY, a.posZ), to = box(b.posX, b.posY, b.posZ);
        int entry = j;
        for (int t = i; t <= j; t++) {
            PlayerState q = path.get(t);
            if (to.contains(q.posX, q.posY, q.posZ)) {
                entry = t;
                break;
            }
        }
        HopBound hb = new HopBound(p, from, to);
        if (System.getenv("MCTAS_DEBUG") != null) System.err.println(p.id + " took " + (entry - i) + " bound " + hb.ticks());
        if (hb.ticks() > entry - i) return -1;
        if (a.onGround && new HopBound(p, from, to, Math.hypot(a.motionX, a.motionZ)).ticks() > entry - i) return -1;
        return entry - i;
    }

    private static Region box(double x, double y, double z) {
        return new Region(x - 1e-3, y - 1e-3, z - 1e-3, x + 1e-3, y + 1e-3, z + 1e-3);
    }
}
