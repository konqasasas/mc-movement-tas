package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounces off slime up into a body and climbs out onto a foothold ({@link Course.Surface#viaBounceBody}):
 * for the best few such footholds from the player's platform, a {@link BounceClimb} landing on the
 * slime under the entry cell, and a bounce {@link Step} at the foothold itself, for each jump tick.
 */
final class BounceClimbManeuver implements Maneuver {
    static final int TARGETS = 2;
    static final int[] JUMPS = {0, 1, 2, 3, 4, 6, Step.NO_JUMP};
    /** How far inside the slime's footholds (grown by the half width) the centre lands: 0.3 to the block, and a margin. */
    static final double LAND_IN = 0.55;

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
            // The bounce needs the centre over the slime (the block under the centre is the one landed on): land well inside.
            double[] land = {slime[0] + LAND_IN, slime[1] - LAND_IN, slime[2] + LAND_IN, slime[3] - LAND_IN};
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
                // The bounce straight at the foothold: on a ladder on the way, the step's push on it climbs.
                Step direct = new Step(clamp(e.cell[0] + 0.5, region[0], region[1]), h.top, clamp(e.cell[2] + 0.5, region[2], region[3]), region, 1, j, 0F,
                    new double[] {sx, sz}, new double[] {land[0], land[1], land[2], land[3], e.slime.top});
                r = from.copy();
                in = new ArrayList<PlayerInput>();
                direct.play(r, in);
                if (!r.dead && c.platformAt(r.s) == h.platform) out.add(new Successor(direct, r, in));
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
