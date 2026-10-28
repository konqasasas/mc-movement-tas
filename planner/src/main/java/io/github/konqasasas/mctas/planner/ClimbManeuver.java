package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.List;

/**
 * Transitions along the graph's moves through ladders, vines and water ({@link Course.Surface#viaBody}):
 * for the best few such footholds from the player's platform (each with every body that leads there, as
 * long as the player can get into it with nothing in the way), the body's entry cells nearest the player, the exit cell nearest the foothold, and a {@link ClimbStep} for each jump tick.
 */
final class ClimbManeuver implements Maneuver {
    static final int TARGETS = 3;
    static final int ENTRIES = 2;
    /** (Foothold, body) pairs looked at per node at most. */
    static final int ATTEMPTS = 12;
    static final boolean TRACE = System.getenv("PLANNER_TRACE") != null;
    static final int[] JUMPS = {0, 1, 3, 6, 10, 15};
    /** Landings on a platform that the climbs are tried from, per search. */
    static final int PER_PLATFORM = 6;
    private final java.util.Map<Integer, Integer> perPlatform = new java.util.HashMap<Integer, Integer>();

    @Override
    public void expand(Run from, List<double[]> aims, List<Successor> out) {
        if (!from.s.onGround) return;
        Course c = from.course;
        Course.Surface a = c.support(from.s.posX, from.s.posY, from.s.posZ);
        if (a == null) return;
        // The climbs off a platform hardly depend on how the player got there: try them from the
        // first few landings on each platform only (they are expensive: up to 300 ticks each).
        int uses = perPlatform.merge(a.platform, 1, Integer::sum);
        if (uses > PER_PLATFORM) return;
        List<Course.Surface> cand = new ArrayList<Course.Surface>();
        List<Integer> body = new ArrayList<Integer>();
        for (Course.Surface s : c.platforms.get(a.platform)) {
            for (int i = 0; i < s.viaBody.size(); i++) {
                Course.Surface h = s.viaBody.get(i);
                if (h.toGo == Double.POSITIVE_INFINITY) continue;
                boolean dup = false;
                for (int k = 0; k < cand.size() && !dup; k++) dup = cand.get(k) == h && body.get(k).equals(s.viaBodyId.get(i));
                if (!dup) {
                    cand.add(h);
                    body.add(s.viaBodyId.get(i));
                }
            }
        }
        if (cand.isEmpty()) return;
        double v = c.reach.maxSpeed();
        final double x = from.s.posX, z = from.s.posZ;
        Integer[] order = new Integer[cand.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (p, q) -> Double.compare(cand.get(p).dist(x, z) / v + cand.get(p).toGo, cand.get(q).dist(x, z) / v + cand.get(q).toGo));
        java.util.Set<Integer> platforms = new java.util.HashSet<Integer>();
        int tried = 0;
        for (int i : order) {
            Course.Surface h = cand.get(i);
            if (platforms.size() >= TARGETS) break;
            if (platforms.contains(h.platform)) continue;
            List<int[]> cells = c.bodies.get(body.get(i));
            int[] exit = null;
            double exitScore = Double.POSITIVE_INFINITY;
            for (int[] cell : cells) {
                if (!c.canLeave(cell, h)) continue;
                double sc = Math.hypot(cell[0] + 0.5 - h.cx(), cell[2] + 0.5 - h.cz()) + Math.abs(cell[1] + 1 - h.top);
                if (sc < exitScore) {
                    exitScore = sc;
                    exit = cell;
                }
            }
            if (exit == null) continue;
            if (++tried > ATTEMPTS) break;
            List<int[]> entries = new ArrayList<int[]>();
            for (int[] cell : enterable(c, a, body.get(i))) {
                if (c.blockedLine(x, z, cell[0] + 0.5, cell[2] + 0.5, Math.min(a.top, cell[1]))) continue;
                entries.add(cell);
            }
            final int[] ex = exit;
            entries.sort((p, q) -> Double.compare(entryScore(p, x, z, a.top, ex), entryScore(q, x, z, a.top, ex)));
            double[] region = {h.minX + 0.3, h.maxX - 0.3, h.minZ + 0.3, h.maxZ - 0.3};
            if (TRACE) System.err.println("climb target " + h + " top " + h.top + " body " + body.get(i) + " exit " + java.util.Arrays.toString(exit) + " entries " + entries.size()
                + (entries.isEmpty() ? "" : " first " + java.util.Arrays.toString(entries.get(0))));
            if (entries.isEmpty()) continue;
            platforms.add(h.platform);
            for (int e = 0; e < Math.min(ENTRIES, entries.size()); e++) {
                for (int j : JUMPS) {
                    ClimbStep st = new ClimbStep(entries.get(e), exit, h.top, region, j);
                    Run r = from.copy();
                    List<PlayerInput> in = new ArrayList<PlayerInput>();
                    st.play(r, in);
                    if (!r.dead) out.add(new Successor(st, r, in));
                }
            }
        }
    }

    /** Cells of the body the player can get into from foothold a (cached per foothold and body). */
    private final java.util.concurrent.ConcurrentHashMap<Long, List<int[]>> enterable = new java.util.concurrent.ConcurrentHashMap<Long, List<int[]>>();

    private List<int[]> enterable(Course c, Course.Surface a, int body) {
        return enterable.computeIfAbsent(((long) a.id << 32) | body, k -> {
            List<int[]> out = new ArrayList<int[]>();
            for (int[] cell : c.bodies.get(body)) if (c.canEnter(a, cell)) out.add(cell);
            return out;
        });
    }

    /** Distance to the cell, then on through the body to the exit (slower, so weighted up). */
    private static double entryScore(int[] cell, double x, double z, double top, int[] exit) {
        return Math.hypot(cell[0] + 0.5 - x, cell[2] + 0.5 - z) + 0.5 * Math.abs(cell[1] - top)
            + 2 * Math.hypot(cell[0] - exit[0], cell[2] - exit[2]) + 0.5 * Math.abs(cell[1] - exit[1]);
    }
}
