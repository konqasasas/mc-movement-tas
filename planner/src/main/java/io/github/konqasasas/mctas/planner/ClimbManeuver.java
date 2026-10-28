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
    /** Bodies tried per target platform (each body is a different way there). */
    static final int BODIES = 2;
    /** (Foothold, body) pairs looked at per node at most. */
    static final int ATTEMPTS = 12;
    static final boolean TRACE = System.getenv("PLANNER_TRACE") != null;
    static final int[] JUMPS = {0, 1, 3, 6, 10, 15};
    /** Landings on a block of a platform that the climbs are tried from, per search. */
    static final int PER_PLATFORM = 2;
    private final java.util.Map<Long, Integer> perPlatform = new java.util.HashMap<Long, Integer>();

    @Override
    public void expand(Run from, List<double[]> aims, List<Successor> out) {
        if (!from.s.onGround) return;
        Course c = from.course;
        Course.Surface a = c.support(from.s.posX, from.s.posY, from.s.posZ);
        if (a == null) return;
        // The climbs off a platform hardly depend on how the player got there, only on where: try
        // them from the first few landings on each block of each platform only (they are expensive:
        // up to 300 ticks each).
        long spot = ((long) a.platform << 40) ^ ((long) ((int) Math.floor(from.s.posX) & 0xFFFFF) << 20) ^ ((int) Math.floor(from.s.posZ) & 0xFFFFF);
        int uses = perPlatform.merge(spot, 1, Integer::sum);
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
        java.util.Map<Integer, Integer> platforms = new java.util.HashMap<Integer, Integer>();
        java.util.Set<Long> pairs = new java.util.HashSet<Long>();
        int tried = 0;
        for (int i : order) {
            Course.Surface h = cand.get(i);
            Integer ways = platforms.get(h.platform);
            if (ways == null && platforms.size() >= TARGETS) continue;
            if (ways != null && ways >= BODIES) continue;
            if (!pairs.add(((long) h.platform << 32) | body.get(i))) continue;
            List<int[]> cells = c.bodies.get(body.get(i));
            // Exits: the cell nearest the foothold (and level with it), and the highest cell nearest
            // it, to drop from onto it (a ladder too short to climb out of the top).
            int[] exit = null, high = null;
            double exitScore = Double.POSITIVE_INFINITY, highScore = Double.POSITIVE_INFINITY;
            for (int[] cell : cells) {
                if (!c.canLeave(cell, h)) continue;
                double d = Math.hypot(cell[0] + 0.5 - h.cx(), cell[2] + 0.5 - h.cz());
                double sc = d + Math.abs(cell[1] + 1 - h.top);
                if (sc < exitScore) {
                    exitScore = sc;
                    exit = cell;
                }
                double hs = d - 2 * cell[1];
                if (hs < highScore) {
                    highScore = hs;
                    high = cell;
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
            platforms.merge(h.platform, 1, Integer::sum);
            List<int[]> exits = new ArrayList<int[]>();
            exits.add(exit);
            if (high != exit && high[1] > exit[1]) exits.add(high);
            for (int[] x1 : exits) {
                for (int e = 0; e < Math.min(ENTRIES, entries.size()); e++) {
                    List<int[]> path = c.water(x1[0], x1[1], x1[2]) ? path(c, body.get(i), entries.get(e), x1) : null;
                    for (int j : JUMPS) {
                        ClimbStep st = new ClimbStep(entries.get(e), x1, h.top, region, j, path, c.bodyKeys(body.get(i)));
                        Run r = from.copy();
                        List<PlayerInput> in = new ArrayList<PlayerInput>();
                        st.play(r, in);
                        if (!r.dead) out.add(new Successor(st, r, in));
                    }
                }
            }
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, List<int[]>> paths = new java.util.concurrent.ConcurrentHashMap<String, List<int[]>>();

    /** The way through a body of water from the entry cell to the exit cell (cached). */
    private List<int[]> path(Course c, int body, int[] from, int[] to) {
        String k = body + java.util.Arrays.toString(from) + java.util.Arrays.toString(to);
        List<int[]> p = paths.get(k);
        if (p == null) {
            p = c.pathThrough(body, from, to);
            if (p == null) p = new ArrayList<int[]>();
            paths.put(k, p);
        }
        return p.isEmpty() ? null : p;
    }

    /**
     * Cells of the body the player can get into from a's platform (walking on it is free, so from any
     * of its footholds; cached per platform and body).
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, List<int[]>> enterable = new java.util.concurrent.ConcurrentHashMap<Long, List<int[]>>();

    private List<int[]> enterable(Course c, Course.Surface a, int body) {
        return enterable.computeIfAbsent(((long) a.platform << 32) | body, k -> {
            List<int[]> out = new ArrayList<int[]>();
            for (int[] cell : c.bodies.get(body)) {
                for (Course.Surface f : c.platforms.get(a.platform)) {
                    if (Math.abs(f.x - cell[0]) > 6 || Math.abs(f.z - cell[2]) > 6 || !c.canEnter(f, cell)) continue;
                    out.add(cell);
                    break;
                }
            }
            return out;
        });
    }

    /** Distance to the cell, then on through the body to the exit (slower, so weighted up). */
    private static double entryScore(int[] cell, double x, double z, double top, int[] exit) {
        return Math.hypot(cell[0] + 0.5 - x, cell[2] + 0.5 - z) + 0.5 * Math.abs(cell[1] - top)
            + 2 * Math.hypot(cell[0] - exit[0], cell[2] - exit[2]) + 0.5 * Math.abs(cell[1] - exit[1]);
    }
}
