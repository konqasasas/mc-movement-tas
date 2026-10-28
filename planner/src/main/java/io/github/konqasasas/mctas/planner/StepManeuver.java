package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.List;

/**
 * The planner's own transitions: for each aim and strafe, run towards the aim and branch a jump off
 * every ground tick (or only the immediate hop and the edge jump when the aim is still out of reach),
 * plus walking onto another platform or running off the edge without jumping. Each branch is exactly
 * what {@link Step#play} does with that {@code jumpAt}, so the plan can be replayed from its steps.
 */
final class StepManeuver implements Maneuver {
    /**
     * Strafes the search expands. Straight and 45-degree strafing run the same way (the yaw turns with
     * the keys), and strafing accelerates about 2% more, so only the left strafe is expanded; the
     * tightener still tries the others.
     */
    static final int[] STRAFES = {1};
    static final int[] ALL_STRAFES = {0, 1, -1};
    /** Beyond this distance past the best jump's reach, only hop at once or jump at the edge. */
    final double far;

    StepManeuver(double far) {
        this.far = far;
    }

    @Override
    public void expand(Run from, List<double[]> aims, List<Successor> out) {
        for (double[] aim : aims) {
            for (int strafe : STRAFES) {
                if (!from.s.onGround) {
                    Step st = new Step(aim[0], aim[2], aim[1], region(aim), strafe, Step.NO_JUMP);
                    Run r = from.copy();
                    List<PlayerInput> in = new ArrayList<PlayerInput>();
                    st.play(r, in);
                    if (!r.dead) out.add(new Successor(st, r, in));
                    continue;
                }
                ground(from, aim, strafe, out);
            }
        }
    }

    private void ground(Run from, double[] aim, int strafe, List<Successor> out) {
        Course c = from.course;
        int platform = c.platformAt(from.s);
        Step walk = new Step(aim[0], aim[2], aim[1], region(aim), strafe, Step.NO_JUMP);
        Run r = from.copy();
        Run prev = null;
        List<PlayerInput> in = new ArrayList<PlayerInput>();
        boolean jumpedPrev = false;
        for (int k = 0; ; k++) {
            double reach = c.reach.reach(aim[2] - r.s.posY);
            double d = Math.hypot(aim[0] - r.s.posX, aim[1] - r.s.posZ);
            boolean branch = k == 0 || d <= reach + far || reach < 0;
            if (branch) jump(r, in, aim, strafe, k, out);
            jumpedPrev = branch;
            if (k >= Step.MAX_GROUND) return;
            prev = r.copy();
            Step.tick(r, walk.move(r), in);
            if (r.over()) {
                if (r.done()) out.add(new Successor(walk, r, in));
                return;
            }
            if (!r.s.onGround) {
                // Ran off the edge: also the jump on the last ground tick, if not branched already.
                if (!jumpedPrev) jump(prev, in.subList(0, in.size() - 1), aim, strafe, k, out);
                walk.air(r, in);
                if (!r.dead) out.add(new Successor(walk, r, in));
                return;
            }
            int now = c.platformAt(r.s);
            if (now >= 0 && now != platform) {
                out.add(new Successor(walk, r, in));
                return;
            }
        }
    }

    static double[] region(double[] aim) {
        return aim.length >= 7 ? new double[] {aim[3], aim[4], aim[5], aim[6]} : null;
    }

    private static void jump(Run r0, List<PlayerInput> prefix, double[] aim, int strafe, int k, List<Successor> out) {
        Step st = new Step(aim[0], aim[2], aim[1], region(aim), strafe, k);
        Run r = r0.copy();
        List<PlayerInput> in = new ArrayList<PlayerInput>(prefix);
        Step.tick(r, st.jump(r), in);
        if (!r.over()) st.air(r, in);
        if (!r.dead) out.add(new Successor(st, r, in));
    }
}
