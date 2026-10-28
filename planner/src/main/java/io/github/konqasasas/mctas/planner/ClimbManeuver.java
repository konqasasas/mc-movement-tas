package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.List;

/**
 * Transitions along the graph's moves through ladders, vines and water ({@link Course.Surface#viaBody}):
 * for the best few such footholds from the player's surface, a {@link ClimbStep} for each jump tick.
 */
final class ClimbManeuver implements Maneuver {
    static final int TARGETS = 3;
    static final int[] JUMPS = {0, 1, 2, 4, 6, 9, 12, 16};

    @Override
    public void expand(Run from, List<double[]> aims, List<Successor> out) {
        if (!from.s.onGround) return;
        Course c = from.course;
        Course.Surface a = c.support(from.s.posX, from.s.posY, from.s.posZ);
        if (a == null || a.viaBody.isEmpty()) return;
        List<Course.Surface> cand = new ArrayList<Course.Surface>();
        for (Course.Surface s : c.platforms.get(a.platform)) {
            for (Course.Surface h : s.viaBody) if (h.toGo < Double.POSITIVE_INFINITY && !cand.contains(h)) cand.add(h);
        }
        double v = c.reach.maxSpeed();
        final double x = from.s.posX, z = from.s.posZ;
        cand.sort((p, q) -> Double.compare(p.dist(x, z) / v + p.toGo, q.dist(x, z) / v + q.toGo));
        java.util.Set<Integer> platforms = new java.util.HashSet<Integer>();
        for (Course.Surface h : cand) {
            if (platforms.size() >= TARGETS) break;
            if (!platforms.add(h.platform)) continue;
            double[] region = {h.minX + 0.3, h.maxX - 0.3, h.minZ + 0.3, h.maxZ - 0.3};
            for (int j : JUMPS) {
                ClimbStep st = new ClimbStep(h.cx(), h.cz(), h.top, region, j);
                Run r = from.copy();
                List<PlayerInput> in = new ArrayList<PlayerInput>();
                st.play(r, in);
                if (!r.dead) out.add(new Successor(st, r, in));
            }
        }
    }
}
