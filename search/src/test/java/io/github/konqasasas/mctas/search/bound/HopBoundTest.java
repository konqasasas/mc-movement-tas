package io.github.konqasasas.mctas.search.bound;

import io.github.konqasasas.mctas.physics.testing.Slow;
import org.junit.experimental.categories.Category;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
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
 * there or (from the start) what it took to get there; and on random courses (full blocks, every
 * shape, liquids) the bound between two states of a random run is never above the ticks the run
 * took between them.
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
                            if (t > res.startTick) {
                                int early = hb.ticksFromStart(s.posX, s.box.minY, s.posZ);
                                assertTrue(p.id + " tick " + t + ": from start " + early + " > " + (t - res.startTick), early <= t - res.startTick);
                            }
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
     * A jump next to a half slab steps up onto it during the jump tick (the jump's 0.42 is below the
     * slab's 0.5, so the move collides and steps up) and lands on the next tick: one air tick after a
     * jump, with the jump's boost.
     */
    @Test
    public void aJumpOntoAHalfSlabLandsOnTheNextTick() {
        SparseBlockWorld world = new SparseBlockWorld();
        world.fill(-2, 0, -2, 2, 0, 20, VanillaBlocks.require("minecraft:stone"));
        world.fill(-2, 1, 6, 2, 1, 8, VanillaBlocks.require("minecraft:stone_slab[half=bottom]"));
        List<PlayerState> path = new ArrayList<PlayerState>();
        // Two ticks of running up to the slab, then the jump (from a top, not the start box).
        PlayerState s = PlayerState.standing(0.5, 1, 5.0, 0);
        path.add(s);
        for (int t = 0; t < 4; t++) {
            int keys = PlayerInput.W | PlayerInput.SPRINT | (t >= 2 ? PlayerInput.JUMP : 0);
            s = Physics.tick(world, s, PlayerInput.of(keys, 0));
            path.add(s);
        }
        PlayerState a = path.get(0), b = path.get(4);
        assertTrue("the run lands on the slab a tick after the jump: " + b, b.onGround && !path.get(3).onGround && path.get(2).onGround && b.posY == 1.5);
        Problem p = new Problem("slab", "", world, new Spawn(new Vec3(a.posX, a.posY, a.posZ), 0, 0),
            box(a.posX, a.posY, a.posZ), box(b.posX, b.posY, b.posZ), 400, -64, null);
        assertTrue("air ticks after a jump " + new SpeedBound(p).airTicks, new SpeedBound(p).airTicks == 1);
        assertTrue("bound above the run", segment(p, path, 0, 4) >= 0);
    }

    /**
     * A ladder climbs 0.1176 a tick: walking into one and up a wall 6 high takes 69 ticks from
     * standing, and the bound knows it takes most of that (it used to be 7).
     */
    @Test
    public void climbingALadderTakesAboutAsLongAsTheBound() {
        SparseBlockWorld world = ladderWall(6);
        List<PlayerState> path = new ArrayList<PlayerState>();
        PlayerState s = PlayerState.standing(0.5, 1, 7.0, 0);
        path.add(s);
        while (!(s.onGround && s.box.minY > 6.99) && path.size() < 200) {
            s = Physics.tick(world, s, PlayerInput.of(PlayerInput.W, 0));
            path.add(s);
        }
        int took = path.size() - 1;
        assertTrue("the run gets up the wall: " + s, s.onGround && took < 200);
        PlayerState a = path.get(0);
        Problem p = new Problem("ladder", "", world, new Spawn(new Vec3(a.posX, a.posY, a.posZ), 0, 0),
            box(a.posX, a.posY, a.posZ), box(s.posX, s.box.minY, s.posZ), 400, -64, null);
        int bound = new HopBound(p, p.start, p.goal, 0).ticks();
        assertTrue("bound " + bound + " against " + took, bound <= took && bound * 10 >= took * 6);
    }

    /** On walls with ladders, the bound between two states of a random run is never above what the run took. */
    @Test
    @Category(Slow.class)
    public void randomLadderSegmentsTakeNoLessThanTheBound() {
        int checked = 0, onLadder = 0;
        for (long seed = 1; seed <= 40; seed++) {
            Random rnd = new Random(seed);
            SparseBlockWorld world = ladderWall(2 + rnd.nextInt(6));
            if (rnd.nextBoolean()) world.fill(-3, 1, 5 + rnd.nextInt(3), 3, 1 + rnd.nextInt(2), 5 + rnd.nextInt(3),
                VanillaBlocks.require("minecraft:stone_slab[half=bottom]"));
            PlayerState s = PlayerState.standing(-1.5 + 3 * rnd.nextDouble(), 1, 3 + 4 * rnd.nextDouble(), 0);
            List<PlayerState> path = new ArrayList<PlayerState>();
            path.add(s);
            int keys = PlayerInput.W;
            float yaw = 0;
            for (int t = 0; t < 150; t++) {
                if (rnd.nextInt(6) == 0) {
                    keys = (rnd.nextInt(5) > 0 ? PlayerInput.W : rnd.nextBoolean() ? PlayerInput.S : 0)
                        | (rnd.nextInt(3) == 0 ? PlayerInput.A : rnd.nextInt(3) == 0 ? PlayerInput.D : 0)
                        | (rnd.nextInt(3) == 0 ? PlayerInput.JUMP : 0) | (rnd.nextBoolean() ? PlayerInput.SPRINT : 0)
                        | (rnd.nextInt(8) == 0 ? PlayerInput.SNEAK : 0);
                    yaw = (float) (rnd.nextGaussian() * 40);
                }
                s = Physics.tick(world, s, PlayerInput.of(keys, yaw));
                if (s.posY < 0) break;
                path.add(s);
                if (world.getBlock((int) Math.floor(s.posX), (int) Math.floor(s.box.minY), (int) Math.floor(s.posZ)).name().contains("ladder")) onLadder++;
            }
            for (int k = 0; k < 4 && path.size() > 20; k++) {
                int i = rnd.nextInt(path.size() - 10);
                int j = Math.min(path.size() - 1, i + 1 + rnd.nextInt(60));
                PlayerState a = path.get(i), b = path.get(j);
                Problem p = new Problem("ladder" + seed, "", world, new Spawn(new Vec3(a.posX, a.posY, a.posZ), 0, 0),
                    box(a.posX, a.posY, a.posZ), box(b.posX, b.posY, b.posZ), 400, -64, null);
                if (segment(p, path, i, j) < 0) fail("seed " + seed + ": bound from tick " + i + " to " + j + " above the run (" + a + " -> " + b + ")");
                checked++;
            }
        }
        assertTrue(checked > 100 && onLadder > 500);
    }

    /** A floor, a stone wall {@code h} high along z = 10 and three ladders up it (x = -1..1). */
    private static SparseBlockWorld ladderWall(int h) {
        SparseBlockWorld world = new SparseBlockWorld();
        world.fill(-3, 0, 0, 3, 0, 14, VanillaBlocks.require("minecraft:stone"));
        world.fill(-3, 1, 10, 3, h, 14, VanillaBlocks.require("minecraft:stone"));
        world.fill(-1, 1, 9, 1, h, 9, VanillaBlocks.require("minecraft:ladder[facing=north]"));
        return world;
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
