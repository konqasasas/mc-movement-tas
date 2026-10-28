package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TechniqueTest {

    /** A run-up on the floor, then a 1-block step at z 30, under a 2-high ceiling ending 1 block before it. */
    private static SparseBlockWorld stepUnderCeiling() {
        return StepWindow.world(1.0, 30, 2, 1);
    }

    private static PlayerState sprinting(double z) {
        PlayerState s = StepWindow.runUp(40);
        double shift = z - s.posZ;
        s.setPosition(s.posX, s.posY, z);
        s.lastReportedPosZ += shift;
        return s;
    }

    @Test
    public void segmentsReplayToTheirExitAndKeepTheToggleGap() {
        SparseBlockWorld w = stepUnderCeiling();
        PlayerState entry = sprinting(25.0);
        Target top = new Target(-3, 4, 31, 33, StepWindow.FLOOR + 1);
        SegmentSearch.Options o = new SegmentSearch.Options();
        o.width = 3000;
        List<Technique.Segment> segs = Technique.solve(w, entry, 100, Exit.landing(top), 0, 60_000, o);
        assertFalse(segs.isEmpty());
        double lastSpeed = -1;
        int lastTicks = 0;
        for (Technique.Segment seg : segs) {
            assertTrue(seg.ticks() > lastTicks || seg.exitSpeed() > lastSpeed);
            assertTrue(seg.exitSpeed() > lastSpeed);
            lastSpeed = seg.exitSpeed();
            lastTicks = seg.ticks();
            PlayerState s = entry;
            int lastToggle = -100;
            for (int t = 1; t <= seg.ticks(); t++) {
                PlayerState ns = Physics.tick(w, s, seg.inputs.get(t - 1));
                if (ns.sprinting != s.sprinting || ns.prevSneak != s.prevSneak) {
                    assertTrue("toggle gap at tick " + t, t - lastToggle >= SegmentSearch.TOGGLE_GAP);
                    lastToggle = t;
                }
                assertFalse(ns.unsupportedAction);
                s = ns;
            }
            assertTrue(s.sameAs(seg.exit));
            assertTrue(s.onGround);
            assertEquals(StepWindow.FLOOR + 1, s.posY, 1e-9);
            assertEquals(seg.ticks() - lastToggle, seg.ticksSinceLastToggle);
        }
    }

    @Test
    public void searchBeatsRunningIntoTheStep() {
        // Holding W+A into the face and jumping on contact (what a naive policy does) versus the search.
        SparseBlockWorld w = stepUnderCeiling();
        PlayerState entry = sprinting(25.0);
        Target top = new Target(-3, 4, 31, 33, StepWindow.FLOOR + 1);
        Exit exit = Exit.landing(top);
        PlayerState s = entry;
        int naive = -1;
        int lastToggle = -100;
        for (int t = 1; t <= 80 && naive < 0; t++) {
            PlayerInput a = s.collidedHorizontally && s.onGround
                    ? PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 0)
                    : PlayerInput.of(PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT, 45);
            PlayerState ns = Physics.tick(w, s, a);
            if (ns.sprinting != s.sprinting) lastToggle = t;
            if (exit.reached(s, ns)) naive = t;
            s = ns;
        }
        assertTrue(naive > 0);
        SegmentSearch.Options o = new SegmentSearch.Options();
        o.width = 3000;
        List<SegmentSearch.Result> rs = new SegmentSearch().search(w, entry, -100, exit, 0, o);
        assertFalse(rs.isEmpty());
        assertTrue("search " + rs.get(0).ticks() + " vs naive " + naive, rs.get(0).ticks() < naive);
    }

    @Test
    public void forwardJumpWindowForAOneBlockStep() {
        List<StepWindow.Jump> js = StepWindow.sweep(1.0, 0, 0, StepWindow.runUp(40), StepWindow.Takeoff.FORWARD, 4.0,
                0.02);
        double lo = Double.NaN, hi = Double.NaN;
        for (StepWindow.Jump j : js) {
            if (!j.clean) continue;
            if (Double.isNaN(lo)) lo = j.distance;
            hi = j.distance;
            assertEquals(9, j.landTicks);
        }
        assertEquals(0.79, lo, 1e-9);
        assertEquals(2.51, hi, 1e-9);
    }

    @Test
    public void underTheCeilingOnlyTheBackwardJumpClimbs() {
        PlayerState run = StepWindow.runUp(40);
        for (StepWindow.Jump j : StepWindow.sweep(1.0, 2, 1, run, StepWindow.Takeoff.FORWARD, 4.0, 0.02)) {
            assertFalse(j.clean);
        }
        boolean any = false;
        for (StepWindow.Jump j : StepWindow.sweep(1.0, 2, 1, run, StepWindow.Takeoff.BACK_DIAGONAL, 4.0, 0.02)) {
            any |= j.clean;
        }
        assertTrue(any);
    }

    @Test
    public void fastModeAnswersQuickly() {
        SparseBlockWorld w = StepWindow.world(1.0, 30, 0, 0);
        PlayerState entry = sprinting(25.0);
        Target top = new Target(-3, 4, 31, 33, StepWindow.FLOOR + 1);
        long t0 = System.nanoTime();
        List<Technique.Segment> segs = Technique.solve(w, entry, 100, Exit.landing(top), 0, 5_000,
                SegmentSearch.Options.fast());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(segs.isEmpty());
        assertTrue("took " + ms + " ms", ms < 3_000);
    }

    /**
     * The fence posts of realdev ex1 (z 302..313): up a plank, back onto a post, onto a higher post, onto
     * a slab. The landings are named in order, since the way goes backwards first.
     */
    @Test
    public void chainClimbsBackAndForthOverPosts() {
        SparseBlockWorld w = new SparseBlockWorld();
        io.github.konqasasas.mctas.physics.BlockType planks = io.github.konqasasas.mctas.problem.world.Blocks
                .byId("minecraft:planks");
        io.github.konqasasas.mctas.physics.BlockType post = io.github.konqasasas.mctas.problem.world.Blocks
                .byId("minecraft:cobblestone_wall");
        io.github.konqasasas.mctas.physics.BlockType slab = io.github.konqasasas.mctas.problem.world.Blocks
                .byId("minecraft:wooden_slab[half=bottom]");
        int f = StepWindow.FLOOR;
        w.fill(-2, f - 1, 0, 2, f - 1, 5, StepWindow.STONE);
        w.fill(-1, f - 1, 8, 1, f - 1, 8, planks);
        w.fill(0, f, 8, 0, f, 8, post);
        w.fill(0, f, 10, 0, f, 10, planks);
        w.fill(-1, f, 10, -1, f + 1, 10, planks);
        w.fill(-1, f + 1, 10, -1, f + 1, 10, post);
        w.fill(-2, f + 2, 13, 2, f + 2, 13, planks);
        w.fill(-2, f + 3, 13, 2, f + 3, 13, slab);
        PlayerState entry = PlayerState.standing(0.5, f, 2.5, 0);
        java.util.List<Target> legs = java.util.Arrays.asList(
                new Target(0, 1, 10, 11, f + 1),
                new Target(-0.05, 1.05, 8.25, 8.75, f + 1.5),
                new Target(-1.05, 0.05, 10.25, 10.75, f + 2.5),
                new Target(-2, 3, 13, 14, f + 3.5));
        List<Technique.Segment> segs = Technique.chain(w, entry, 100, legs, 5_000, 2, SegmentSearch.Options.fast());
        assertFalse(segs.isEmpty());
        PlayerState s = entry;
        for (PlayerInput a : segs.get(0).inputs) s = Physics.tick(w, s, a);
        assertTrue(s.onGround);
        assertEquals(f + 3.5, s.posY, 1e-9);
        assertTrue("ticks " + segs.get(0).ticks(), segs.get(0).ticks() <= 80);
    }
}
