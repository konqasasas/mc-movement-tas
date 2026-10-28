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
}
