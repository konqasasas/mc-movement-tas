package io.github.konqasasas.mctas.search.bound;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A lower bound for any course: the player has to cover the distance from the start box to the
 * goal, and no input sequence moves faster than a speed limit that holds for every block.
 *
 * <p>Horizontally, each tick is one of a few regimes, each a friction {@code f} and a longest
 * acceleration {@code a}: on the ground over each slipperiness the world has (with or without a
 * sprint jump), in the air, in water or in lava. A tick moves the player by at most {@code |v| + a}
 * and leaves a speed of at most {@code f (|v| + a)}; collisions, ladders, cobwebs, soul sand and
 * the 0.003 cutoff only slow the player down. A jump tick never ends on the ground (its motionY is
 * positive) and the player lands only while moving down, so after a jump at least {@link #airTicks}
 * ticks pass in the air before the next ground tick. That count comes from the world: the jump arc
 * under each ceiling and onto each surface near each place the player can take off from, with
 * ladders and webs where there are any. A dynamic programme over the speed then gives the farthest
 * distance {@code k} ticks can cover, from the fastest speed the player can have when entering the
 * start box.
 *
 * <p>Vertically, a tick raises the feet by at most 0.6 blocks: a step-up rises at most the step
 * height, and nothing but a slime block sets motionY above the jump's 0.42. With slime blocks in the
 * world there is no vertical bound.
 *
 * <p>With blocks in the hotbar, the world can gain any block: then the bound allows ice and slime
 * everywhere and a ceiling over every take-off.
 *
 * <p>Assumes no potion effects and that the player is never inside a block (so
 * {@code pushOutOfBlocks} never fires; the physics flags the one way into a block, standing up
 * under a low ceiling, as unsupported).
 */
public final class SpeedBound {

    private static final double ROT = RelaxedModel.ROT * (1 + 1e-6);
    private static final double AIR_FRICTION = 0.91F;
    private static final double AIR_ACCEL = 0.026D * ROT;
    private static final double JUMP_BOOST = 0.2D * ROT;
    /** Water current: {@code 0.014} times a unit flow vector. */
    private static final double CURRENT = 0.014D;
    private static final double STEP = 0.6000000238418579D;
    private static final double GRID = 1e-4;
    /** {@link #ticksFrom} keeps every {@code ROW_STEP}-th speed, rounding a state's speed up. */
    private static final int ROW_STEP = 10;
    /** The longest air time looked at after a jump; the flat-ground arc lands after 11. */
    static final int MAX_AIR = 11;

    private static final class Regime {
        final double friction;
        final double accel;
        final boolean ground;
        final boolean jump;

        Regime(double friction, double accel, boolean ground, boolean jump) {
            this.friction = friction;
            this.accel = accel;
            this.ground = ground;
            this.jump = jump;
        }
    }

    private final Problem problem;
    private final List<Regime> regimes = new ArrayList<Regime>();
    private final boolean slime;
    /** The fewest ticks in the air after a jump tick, the landing tick included; at least 1. */
    final int airTicks;
    /**
     * The fastest speeds with {@code c} air ticks still owed: index 0 is free to touch the ground,
     * a jump owes {@link #airTicks}.
     */
    double[] vmax;
    /** rows[k - 1][j]: the farthest {@code k} ticks get from speed {@code j * GRID * ROW_STEP}, free to land. */
    private volatile float[][] rows;

    public SpeedBound(Problem problem) {
        this.problem = problem;
        final TreeSet<Float> slips = new TreeSet<Float>();
        // Standing over air (an edge) uses the slipperiness of air.
        slips.add(BlockType.AIR.slipperiness());
        final boolean[] liquid = new boolean[3];
        problem.world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                slips.add(type.slipperiness());
                String n = type.name();
                if (n.contains("water")) liquid[0] = true;
                if (n.contains("lava")) liquid[1] = true;
                if (n.startsWith("minecraft:slime")) liquid[2] = true;
            }
        });
        // Blocks in the hotbar can be placed anywhere: any slipperiness, slime, and a ceiling over any take-off.
        boolean placing = !problem.inventory.isEmpty();
        if (placing) {
            slips.add(0.8F);
            slips.add(0.98F);
        }
        slime = liquid[2] || placing;
        for (float s : slips) {
            float f6 = s * 0.91F;
            double accel = 0.13D * (double) (0.16277136F / (f6 * f6 * f6)) * ROT;
            regimes.add(new Regime(f6, accel, true, false));
            regimes.add(new Regime(f6, accel + JUMP_BOOST, true, true));
        }
        regimes.add(new Regime(AIR_FRICTION, AIR_ACCEL, false, false));
        if (liquid[0]) regimes.add(new Regime(0.8F, 0.02D * ROT + CURRENT, false, false));
        if (liquid[1]) regimes.add(new Regime(0.5D, 0.02D * ROT + CURRENT, false, false));
        vmax = fastest(1);
        // The places to look at around a take-off: as far as the player can get in the air.
        double v = 0;
        for (double x : vmax) v = Math.max(v, x);
        airTicks = placing ? 1 : new JumpArcs(problem.world, (int) Math.ceil(MAX_AIR * (v + AIR_ACCEL)) + 2).fewest();
        vmax = fastest(airTicks);
    }

    /** The state after a tick of regime {@code r} with {@code c} air ticks owed, or -1 when it may not run. */
    private static int next(Regime r, int c, int owed) {
        if (r.ground) return c > 0 ? -1 : r.jump ? owed : 0;
        return Math.max(0, c - 1);
    }

    /** Iterates the speed limits from a standing start to their fixpoint. */
    private double[] fastest(int owed) {
        double[] v = new double[owed + 1];
        for (int it = 0; it < 100000; it++) {
            double[] n = v.clone();
            for (int c = 0; c <= owed; c++) {
                for (Regime r : regimes) {
                    int to = next(r, c, owed);
                    if (to >= 0) n[to] = Math.max(n[to], r.friction * (v[c] + r.accel));
                }
            }
            boolean done = true;
            for (int c = 0; c <= owed; c++) done &= n[c] <= v[c] + 1e-12;
            if (done) break;
            v = n;
        }
        for (int c = 0; c <= owed; c++) v[c] += 1e-9;
        return v;
    }

    /** The horizontal distance between the start box and the goal. */
    double horizontalGap() {
        Region s = problem.start, g = problem.goal;
        double dx = Math.max(0, Math.max(g.minX - s.maxX, s.minX - g.maxX));
        double dz = Math.max(0, Math.max(g.minZ - s.maxZ, s.minZ - g.maxZ));
        return Math.hypot(dx, dz);
    }

    /** How far the goal's bottom is above the start box's top. */
    double rise() {
        return problem.goal.minY - problem.start.maxY;
    }

    /** The fewest ticks after the start that can cover the horizontal gap, at most {@code maxTicks}. */
    int horizontalTicks(int maxTicks) {
        double gap = horizontalGap();
        if (gap <= 0) return 0;
        double[] far = reach(maxTicks, gap);
        return far.length - 1 + (far[far.length - 1] >= gap ? 0 : 1);
    }

    /**
     * The farthest the player gets horizontally in {@code k} ticks, from any tick of any run, for
     * {@code k} up to {@code maxTicks} or until it reaches {@code stop}.
     */
    double[] reach(int maxTicks, double stop) {
        return reach(maxTicks, stop, null);
    }

    /** {@link #reach(int, double)}, also keeping each tick's distances from a free start in {@code rows}. */
    private double[] reach(int maxTicks, double stop, List<float[]> rows) {
        List<Double> out = new ArrayList<Double>();
        out.add(0.0);
        int owed = airTicks;
        double top = 0;
        for (double v : vmax) top = Math.max(top, v);
        int n = (int) Math.ceil(top / GRID) + 2;
        // w[c][i]: the farthest k ticks get from speed i * GRID with c air ticks owed.
        double[][] w = new double[owed + 1][n];
        for (int k = 1; k <= maxTicks; k++) {
            double[][] nw = new double[owed + 1][n];
            for (int c = 0; c <= owed; c++) {
                for (int i = 0; i < n; i++) {
                    double v = i * GRID;
                    double best = 0;
                    for (Regime r : regimes) {
                        int to = next(r, c, owed);
                        if (to < 0) continue;
                        double d = v + r.accel;
                        int vi = Math.min(n - 1, (int) Math.ceil(Math.min(r.friction * d, vmax[to]) / GRID));
                        best = Math.max(best, d + w[to][vi]);
                    }
                    nw[c][i] = best;
                }
            }
            w = nw;
            double far = 0;
            for (int c = 0; c <= owed; c++) far = Math.max(far, w[c][Math.min(n - 1, (int) Math.ceil(vmax[c] / GRID))]);
            out.add(far);
            if (rows != null) {
                float[] row = new float[(n - 1) / ROW_STEP + 1];
                for (int j = 0; j < row.length; j++) row[j] = (float) (w[0][Math.min(n - 1, j * ROW_STEP)] * (1 + 1e-6));
                rows.add(row);
            }
            if (far >= stop) break;
        }
        double[] a = new double[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** The speed step of {@link #hops}. */
    static final double HOP_GRID = 0.005;

    /** The fastest horizontal speed anywhere (the speed {@link #hops} rounds every start up to). */
    double top() {
        double top = 0;
        for (double v : vmax) top = Math.max(top, v);
        return top;
    }

    /**
     * Tables for a hop from a tick that ended on the ground to the next anchor (a tick that ends on the
     * ground, on a ladder, in a liquid or a web), which is free flight in between: {@code [0][n][i]} is
     * the farthest a hop of {@code n} ticks gets from speed {@code i * HOP_GRID}, and {@code [1][n][i]}
     * the fastest it can be going at the end, and {@code [2][n][i]} the farthest its last tick moves.
     * The first tick starts on the ground (or in a liquid
     * there), and is a jump only when the hop is longer than a tick: a jump moves up, so its tick does
     * not end on the ground. The other ticks start in free air. Speeds are rounded up to the grid and
     * capped at {@link #top()}.
     */
    double[][][] hops(int maxN) {
        return hops(maxN, true);
    }

    /** {@link #hops(int)}, for hops that may start with a jump or (false) may not. */
    double[][][] hops(int maxN, boolean jumps) {
        double top = top();
        int n = (int) Math.ceil(top / HOP_GRID) + 1;
        double[][] far = new double[maxN + 1][n], fast = new double[maxN + 1][n], last = new double[maxN + 1][n];
        for (int i = 0; i < n; i++) {
            double v = i * HOP_GRID;
            for (Regime r : regimes) {
                if (r.jump && !jumps) continue;
                double d = v + r.accel, u = Math.min(top, r.friction * d);
                if (!r.jump && maxN >= 1) {
                    far[1][i] = Math.max(far[1][i], d);
                    fast[1][i] = Math.max(fast[1][i], u);
                    last[1][i] = Math.max(last[1][i], d);
                }
                double sum = d;
                for (int k = 2; k <= maxN; k++) {
                    double m = u + AIR_ACCEL;
                    sum += m;
                    u = Math.min(top, AIR_FRICTION * m);
                    far[k][i] = Math.max(far[k][i], sum);
                    fast[k][i] = Math.max(fast[k][i], u);
                    last[k][i] = Math.max(last[k][i], m);
                }
            }
            for (int k = 1; k <= maxN; k++) {
                far[k][i] *= 1 + 1e-9;
                last[k][i] *= 1 + 1e-9;
                fast[k][i] = Math.min(top, fast[k][i] * (1 + 1e-9));
            }
        }
        return new double[][][] {far, fast, last};
    }

    /** The fewest ticks after the start that can climb to the goal (0 when it is not higher, or with slime). */
    int verticalTicks() {
        double up = rise();
        if (slime || up <= 0) return 0;
        return (int) Math.ceil(up / STEP - 1e-9);
    }

    /** A lower bound on the ticks from the start entry to the goal. */
    public int ticks(int maxTicks) {
        return Math.max(horizontalTicks(maxTicks), verticalTicks());
    }

    /**
     * Gets {@link #ticksFrom} ready to look up to {@code horizon} ticks ahead; it otherwise does so on
     * its first call, for the problem's tick limit.
     */
    public synchronized void prepare(int horizon) {
        List<float[]> r = new ArrayList<float[]>();
        reach(horizon, Double.POSITIVE_INFINITY, r);
        rows = r.toArray(new float[0][]);
    }

    /**
     * A lower bound on the ticks a player in this state needs to reach the goal, for pruning a search
     * or stopping it: no run from here enters the goal sooner. Past the prepared horizon it answers
     * the horizon plus one. Positions are the feet ({@code posX}, {@code box.minY}, {@code posZ}).
     */
    public int ticksFrom(double x, double feetY, double z, double motionX, double motionZ) {
        float[][] r = rows;
        if (r == null) {
            prepare(problem.maxTicks);
            r = rows;
        }
        Region g = problem.goal;
        double dx = Math.max(0, Math.max(g.minX - x, x - g.maxX));
        double dz = Math.max(0, Math.max(g.minZ - z, z - g.maxZ));
        double dist = Math.hypot(dx, dz);
        int up = 0;
        double rise = g.minY - feetY;
        if (!slime && rise > 0) up = (int) Math.ceil(rise / STEP - 1e-9);
        if (dist <= 0) return up;
        int j = (int) Math.ceil(Math.hypot(motionX, motionZ) / (GRID * ROW_STEP));
        int lo = 0, hi = r.length;
        // The first tick count whose farthest distance covers the gap.
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            float[] row = r[mid];
            if (row[Math.min(row.length - 1, j)] >= dist) hi = mid;
            else lo = mid + 1;
        }
        return Math.max(up, lo + 1);
    }

    @Override
    public String toString() {
        return String.format("gap %.3f rise %.3f vmax %.4f air %d regimes %d%s", horizontalGap(), rise(), vmax[0], airTicks,
            regimes.size(), slime ? " slime" : "");
    }

    /**
     * The fewest air ticks after a jump anywhere in a world. For each surface the player can stand
     * on, it collects the ceilings (bottoms at least 1.8 above) and the surfaces to land on within
     * {@code reach} blocks, and whether a ladder, web or liquid is near; then it follows every jump
     * arc that those allow. Liquids are not followed: near one the count is 1.
     */
    static final class JumpArcs {
        private static final double EPS = 1e-7;
        private final Map<Long, List<double[]>> columns = new HashMap<Long, List<double[]>>();
        /** Per column, the heights of blocks that are ladders (0), webs (1) or liquids (2). */
        private final Map<Long, List<double[]>> features = new HashMap<Long, List<double[]>>();
        private final int reach;
        private final Map<String, Integer> memo = new HashMap<String, Integer>();

        JumpArcs(final SparseBlockWorld world, int reach) {
            this.reach = reach;
            world.forEach(new SparseBlockWorld.Visitor() {
                @Override
                public void visit(int x, int y, int z, BlockType type) {
                    String n = type.name();
                    int kind = n.contains("ladder") || n.contains("vine") || n.contains("trapdoor") ? 0
                        : n.equals("minecraft:web") ? 1 : n.contains("water") || n.contains("lava") ? 2 : -1;
                    if (kind >= 0) list(features, x, z).add(new double[] {kind, y});
                    List<Aabb> boxes = new ArrayList<Aabb>();
                    type.addCollisionBoxes(world, x, y, z, new Aabb(x - 1, y - 1, z - 1, x + 2, y + 3, z + 2), boxes);
                    for (Aabb b : boxes) list(columns, x, z).add(new double[] {b.minY, b.maxY, b.minX, b.maxX, b.minZ, b.maxZ});
                }
            });
        }

        private static List<double[]> list(Map<Long, List<double[]>> m, int x, int z) {
            long k = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
            List<double[]> l = m.get(k);
            if (l == null) m.put(k, l = new ArrayList<double[]>());
            return l;
        }

        private static List<double[]> get(Map<Long, List<double[]>> m, int x, int z) {
            List<double[]> l = m.get(((long) x << 32) ^ (z & 0xFFFFFFFFL));
            return l == null ? java.util.Collections.<double[]>emptyList() : l;
        }

        int fewest() {
            int best = MAX_AIR;
            for (Map.Entry<Long, List<double[]>> e : columns.entrySet()) {
                int x = (int) (e.getKey() >> 32), z = (int) e.getKey().longValue();
                for (double[] t : e.getValue()) {
                    if (blocked(e.getValue(), t)) continue;
                    best = Math.min(best, fromTop(x, z, t[1]));
                    if (best == 1) return 1;
                }
            }
            return best;
        }

        /** A box right above {@code t} that covers its whole top: nobody stands there. */
        private static boolean blocked(List<double[]> column, double[] t) {
            for (double[] b : column) {
                if (b[0] >= t[1] - EPS && b[0] < t[1] + 1.8 - EPS
                    && b[2] <= t[2] && b[3] >= t[3] && b[4] <= t[4] && b[5] >= t[5]) return true;
            }
            return false;
        }

        private int fromTop(int x, int z, double y) {
            TreeSet<Double> ceilings = new TreeSet<Double>(), floors = new TreeSet<Double>();
            boolean ladder = false, web = false;
            floors.add(0.0);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    for (double[] b : get(columns, x + dx, z + dz)) {
                        double g = b[0] - y - 1.8;
                        if (g >= -EPS && g < 1.26) ceilings.add(Math.max(0, g));
                        double h = b[1] - y;
                        if (h < 1.26 && h > -6) floors.add(h);
                        // A jump tick can step up onto a top up to 0.6 higher, and lands on the next tick.
                        if (h > EPS && h <= STEP + EPS) return 1;
                    }
                    for (double[] f : get(features, x + dx, z + dz)) {
                        if (f[1] < y - 6 || f[1] > y + 3) continue;
                        if (f[0] == 2) return 1;
                        if (f[0] == 0) ladder = true;
                        else web = true;
                    }
                }
            }
            String key = ceilings + "|" + floors + "|" + ladder + web;
            Integer m = memo.get(key);
            if (m == null) {
                m = MAX_AIR;
                for (Double g : ceilings) m = Math.min(m, arc(g, floors, ladder, web));
                m = Math.min(m, arc(Double.POSITIVE_INFINITY, floors, ladder, web));
                memo.put(key, m);
            }
            return m;
        }

        /**
         * Follows the jump from height 0 under a ceiling {@code g} above standing height, trying each
         * ladder and web effect on each tick, until the first landing on one of {@code floors}.
         * Returns the air ticks after the jump tick, at most {@link #MAX_AIR}.
         */
        static int arc(double g, TreeSet<Double> floors, boolean ladder, boolean web) {
            int a = arc(g, floors, ladder, web, MAX_AIR + 1);
            return a < 0 ? MAX_AIR : Math.min(a, MAX_AIR);
        }

        /** The air ticks of the plain jump arc under ceiling {@code g} onto one of {@code floors}, or {@link HopBound#INF}. */
        static int arcOrNone(double g, TreeSet<Double> floors) {
            int a = arc(g, floors, false, false, 400);
            return a < 0 ? HopBound.INF : a;
        }

        /** {@link #arc(double, TreeSet, boolean, boolean)} looking {@code limit} ticks ahead; -1 when nothing lands. */
        private static int arc(double g, TreeSet<Double> floors, boolean ladder, boolean web, int limit) {
            // States: height, motionY, in a web.
            Set<List<Double>> states = new HashSet<List<Double>>();
            states.add(java.util.Arrays.asList(0.0, (double) 0.42F, 0.0));
            for (int tick = 1; tick <= limit; tick++) {
                Set<List<Double>> nextStates = new HashSet<List<Double>>();
                for (List<Double> st : states) {
                    double y = st.get(0), my = st.get(1);
                    boolean inWeb = st.get(2) > 0;
                    if (tick > 1 && Math.abs(my) < 0.003D) my = 0;
                    List<Double> starts = new ArrayList<Double>();
                    starts.add(my);
                    if (ladder) {
                        starts.add(Math.max(my, -0.15D));
                        if (my < 0) starts.add(0.0);
                    }
                    for (double m0 : starts) {
                        double dy = m0, m1 = m0;
                        if (inWeb) {
                            dy *= 0.05000000074505806D;
                            m1 = 0;
                        }
                        boolean land = false;
                        if (dy > 0 && y + dy > g) {
                            dy = Math.max(0, g - y);
                            m1 = 0;
                        } else if (dy < 0) {
                            Double h = floors.floor(y + EPS);
                            if (h != null && h > y + dy - EPS) land = true;
                        }
                        if (land) {
                            if (tick > 1) return tick - 1;
                            continue;
                        }
                        double ny = y + dy;
                        List<Double> afters = new ArrayList<Double>();
                        afters.add(m1);
                        if (ladder) afters.add(0.2D);
                        for (double m2 : afters) {
                            double m3 = (m2 - 0.08D) * 0.9800000190734863D;
                            nextStates.add(java.util.Arrays.asList(round(ny), round(m3), 0.0));
                            if (web) nextStates.add(java.util.Arrays.asList(round(ny), round(m3), 1.0));
                        }
                    }
                }
                states = nextStates;
            }
            return -1;
        }

        private static double round(double v) {
            return Math.rint(v * 1e9) / 1e9;
        }
    }
}
