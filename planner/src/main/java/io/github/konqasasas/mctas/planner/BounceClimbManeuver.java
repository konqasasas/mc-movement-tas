package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounces off slime up into a body and climbs out onto a foothold ({@link Course.Surface#viaBounceBody}):
 * for the best few such footholds from the player's platform, a {@link BounceClimb} landing on the
 * slime under the entry cell, for each jump tick.
 */
final class BounceClimbManeuver implements Maneuver {
    static final int TARGETS = 2;
    static final int[] JUMPS = {0, 1, 2, 3, 4, 6, Step.NO_JUMP};

    @Override
    public void expand(Run from, List<double[]> aims, List<Successor> out) {
        if (!from.s.onGround) return;
        Course c = from.course;
        Course.Surface a = c.support(from.s.posX, from.s.posY, from.s.posZ);
        if (a == null) return;
        List<Course.Surface> cand = new ArrayList<Course.Surface>();
        List<Course.BounceEntry> how = new ArrayList<Course.BounceEntry>();
        for (Course.Surface s : c.platforms.get(a.platform)) {
            for (int i = 0; i < s.viaBounceBody.size(); i++) {
                Course.Surface h = s.viaBounceBody.get(i);
                if (h.toGo == Double.POSITIVE_INFINITY || h.toGo >= a.toGo || cand.contains(h)) continue;
                cand.add(h);
                how.add(s.viaBounceEntry.get(i));
            }
        }
        Integer[] order = new Integer[cand.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (p, q) -> Double.compare(cand.get(p).toGo, cand.get(q).toGo));
        java.util.Set<Integer> platforms = new java.util.HashSet<Integer>();
        for (int i : order) {
            Course.Surface h = cand.get(i);
            if (!platforms.add(h.platform)) continue;
            if (platforms.size() > TARGETS) break;
            Course.BounceEntry e = how.get(i);
            int[] exit = exitCell(c, e.body, h);
            if (exit == null) continue;
            double[] slime = c.slimeExtent(e.slime);
            double[] land = {slime[0] + 0.3, slime[1] - 0.3, slime[2] + 0.3, slime[3] - 0.3};
            double sx = clamp(e.cell[0] + 0.5, land[0] + 0.1, land[1] - 0.1), sz = clamp(e.cell[2] + 0.5, land[2] + 0.1, land[3] - 0.1);
            double[] region = {h.minX + 0.3, h.maxX - 0.3, h.minZ + 0.3, h.maxZ - 0.3};
            List<int[]> path = c.water(exit[0], exit[1], exit[2]) ? c.pathThrough(e.body, e.cell, exit) : null;
            for (int j : JUMPS) {
                Step st = new Step(e.cell[0] + 0.5, e.cell[1], e.cell[2] + 0.5, null, 1, j, 0F, new double[] {sx, sz},
                    new double[] {land[0], land[1], land[2], land[3], e.slime.top});
                BounceClimb m = new BounceClimb(st, new ClimbStep(e.cell, exit, h.top, region, 0, path, c.bodyKeys(e.body)));
                Run r = from.copy();
                List<PlayerInput> in = new ArrayList<PlayerInput>();
                m.play(r, in);
                if (!r.dead) out.add(new Successor(m, r, in));
            }
        }
    }

    /** The body's cell nearest the foothold that it can be left from onto it (as {@link ClimbManeuver} picks). */
    static int[] exitCell(Course c, int body, Course.Surface h) {
        int[] exit = null;
        double best = Double.POSITIVE_INFINITY;
        for (int[] cell : c.bodies.get(body)) {
            if (!c.canLeave(cell, h)) continue;
            double sc = Math.hypot(cell[0] + 0.5 - h.cx(), cell[2] + 0.5 - h.cz()) + Math.abs(cell[1] + 1 - h.top);
            if (sc < best) {
                best = sc;
                exit = cell;
            }
        }
        return exit;
    }

    private static double clamp(double v, double lo, double hi) {
        return lo > hi ? (lo + hi) / 2 : Math.max(lo, Math.min(hi, v));
    }
}
