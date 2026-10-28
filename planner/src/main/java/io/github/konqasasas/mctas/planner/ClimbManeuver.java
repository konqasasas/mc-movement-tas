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
    static final int[] JUMPS = {0, 1, 3, 6, 10, 15, ClimbStep.EDGE};
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
        Course.Surface missed = null;
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
            java.util.Map<int[], double[]> vias = new java.util.IdentityHashMap<int[], double[]>();
            for (int[] cell : enterable(c, a, body.get(i))) {
                double base = Math.min(a.top, cell[1]);
                if (!c.blockedLine(x, z, cell[0] + 0.5, cell[2] + 0.5, base)) {
                    entries.add(cell);
                    continue;
                }
                double[] v1 = openSide(c, cell, x, z, base);
                if (v1 == null) continue;
                entries.add(cell);
                vias.put(cell, v1);
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
            int before = out.size();
            for (int[] x1 : exits) {
                for (int e = 0; e < Math.min(ENTRIES, entries.size()); e++) {
                    List<int[]> path = c.water(x1[0], x1[1], x1[2]) ? path(c, body.get(i), entries.get(e), x1) : null;
                    for (int j : JUMPS) {
                        ClimbStep st = new ClimbStep(entries.get(e), x1, h.top, region, j, path, c.bodyKeys(body.get(i)), vias.get(entries.get(e)));
                        Run r = from.copy();
                        List<PlayerInput> in = new ArrayList<PlayerInput>();
                        st.play(r, in);
                        if (!r.dead) out.add(new Successor(st, r, in));
                    }
                }
            }
            boolean reached = false;
            for (int k = before; k < out.size() && !reached; k++) reached = c.platformAt(out.get(k).run.s) == h.platform;
            if (!reached && missed == null) missed = h;
        }
        // The controller missed the best foothold it had a body for (a jump into a ladder set in a
        // slot, say): search the inputs there tick by tick instead (WS8), once.
        if (missed != null && TECHNIQUE_MILLIS > 0 && searched.merge(((long) a.platform << 32) | missed.platform, 1, Integer::sum) <= TECHNIQUE_TRIES) {
            technique(from, missed, out);
        }
    }

    /** Local searches per (platform, target platform) and search: one that found nothing mostly finds nothing again. */
    static final int TECHNIQUE_TRIES = 2;
    private final java.util.Map<Long, Integer> searched = new java.util.HashMap<Long, Integer>();

    /** Time for the local technique search of a missed climb, per call (0: none). */
    static final long TECHNIQUE_MILLIS = Long.getLong("planner.climbTechnique", 1000);

    private static void technique(Run from, Course.Surface h, List<Successor> out) {
        Course c = from.course;
        // Any landing on h's platform (its extent; a landing off it inside the box is dropped below).
        double x0 = h.minX, x1 = h.maxX, z0 = h.minZ, z1 = h.maxZ;
        for (Course.Surface q : c.platforms.get(h.platform)) {
            x0 = Math.min(x0, q.minX);
            x1 = Math.max(x1, q.maxX);
            z0 = Math.min(z0, q.minZ);
            z1 = Math.max(z1, q.maxZ);
        }
        io.github.konqasasas.mctas.technique.Target t = new io.github.konqasasas.mctas.technique.Target(x0, x1, z0, z1, h.top);
        io.github.konqasasas.mctas.technique.SegmentSearch.Options o = io.github.konqasasas.mctas.technique.SegmentSearch.Options.fast();
        o.voidY = c.problem.voidY;
        o.maxTicks = 100;
        o.results = 2;
        List<io.github.konqasasas.mctas.technique.Technique.Segment> segs;
        try {
            segs = io.github.konqasasas.mctas.technique.Technique.solve(c.world, from.s.copy(), Math.min(from.tick - from.lastToggle, 1000),
                io.github.konqasasas.mctas.technique.Exit.landing(t), io.github.konqasasas.mctas.technique.Technique.headingTo(from.s, t.centerX(), t.centerZ()), TECHNIQUE_MILLIS, o);
        } catch (RuntimeException e) {
            return;
        }
        if (TRACE) System.err.println("climb technique onto " + h + ": " + segs.size() + " ways");
        for (io.github.konqasasas.mctas.technique.Technique.Segment seg : segs) {
            FixedMove m = new FixedMove(seg.inputs);
            Run r = from.copy();
            List<PlayerInput> in = new ArrayList<PlayerInput>();
            m.play(r, in);
            if (!r.dead && r.s.onGround && c.platformAt(r.s) == h.platform) out.add(new Successor(m, r, in));
        }
    }

    /**
     * A point a block out from a side of the cell that is open at the cell's height, reached straight
     * from (x, z) and with a straight way on into the cell; the nearest such, or null.
     */
    private static double[] openSide(Course c, int[] cell, double x, double z, double base) {
        double[] best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            double vx = cell[0] + 0.5 + d[0], vz = cell[2] + 0.5 + d[1];
            if (c.blockedLine(x, z, vx, vz, base) || c.blockedLine(vx, vz, cell[0] + 0.5, cell[2] + 0.5, base)) continue;
            double dd = Math.hypot(vx - x, vz - z);
            if (dd < bestD) {
                bestD = dd;
                best = new double[] {vx, vz};
            }
        }
        return best;
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
