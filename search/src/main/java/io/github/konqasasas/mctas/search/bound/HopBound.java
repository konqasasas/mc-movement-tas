package io.github.konqasasas.mctas.search.bound;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeSet;

/**
 * A lower bound from the places a run has to touch. Every tick of a run either ends on the ground
 * (feet on the top of a collision box under the player), on a ladder or vine, in a liquid or a web
 * (together the anchors), or in free flight between two anchors. A free flight starts with motionY
 * at most 0.42 (a jump; a slime block bounces at most as fast as the player fell onto it) and lands
 * only while moving down, so it is limited in how high it can get, how long it can last and how far
 * it can go. Each hop from one anchor to the next therefore takes at least as many ticks as
 * <ul>
 *   <li>the speed bound needs for the horizontal distance between the two places,</li>
 *   <li>a climb needs: one tick for a step of 0.6 or less, otherwise the jump arc up to the new top
 *       and back down onto it (under the lowest ceiling near the take-off), and 0.6 a tick out of a
 *       ladder, liquid or web,</li>
 *   <li>a fall needs from the fastest the player can be falling there,</li>
 * </ul>
 * and a hop that the longest flight cannot cover does not exist. The shortest path over the anchors
 * from the start box to the goal (Dijkstra, backwards from the goal) is a lower bound on the run.
 *
 * <p>Places are boxes, not points: a top is the collision box's top face widened by the player's
 * half width (the player stands on it while overlapping it), and a ladder, liquid or web is its
 * block widened the same way over a range of feet heights. Walking along a floor therefore costs a
 * tick per block crossed, less than the speed bound's own rate, so this bound is meant for courses
 * that climb or have to go round; use the larger of the two.
 *
 * <p>Blocks in the hotbar (placing) are not handled: the bound is then 0.
 */
public final class HopBound {

    static final int SURFACE = 0, SPECIAL = 1, START = 2, GOAL = 3;
    private static final double HALF = 0.3;
    private static final double EPS = 1e-7;
    private static final double JUMP = (double) 0.42F;
    private static final double STEP = 0.6000000238418579D;
    private static final double TERMINAL = 3.92D;
    /** Ceilings this close to a take-off (in blocks, by column) shape its jump arc. */
    private static final int CEILING_REACH = 3;
    public static final int INF = Integer.MAX_VALUE / 4;

    static final class Node {
        final int type;
        final double x0, x1, z0, z1, yLo, yHi;
        /** The fastest upward and downward motionY at the start of the first tick of a flight from here. */
        final double up, down;
        double[] ceilings = new double[0];

        Node(int type, double x0, double x1, double z0, double z1, double yLo, double yHi, double up, double down) {
            this.type = type;
            this.x0 = x0;
            this.x1 = x1;
            this.z0 = z0;
            this.z1 = z1;
            this.yLo = yLo;
            this.yHi = yHi;
            this.up = up;
            this.down = down;
        }

        double distance(Node o) {
            double dx = Math.max(0, Math.max(o.x0 - x1, x0 - o.x1));
            double dz = Math.max(0, Math.max(o.z0 - z1, z0 - o.z1));
            return Math.hypot(dx, dz);
        }

        boolean contains(double x, double z) {
            return x > x0 - EPS && x < x1 + EPS && z > z0 - EPS && z < z1 + EPS;
        }
    }

    private final Problem problem;
    private final double[] far;
    private final List<Node> nodes = new ArrayList<Node>();
    private final Map<Long, List<Integer>> columns = new HashMap<Long, List<Integer>>();
    private final boolean placing;
    private final Node start, goal;
    private final Region problemStart, problemGoal;
    private int radius;
    /** Ticks from each node to the goal; {@link #INF} when the goal cannot be reached from there. */
    private int[] toGoal;
    private int bound;

    public HopBound(Problem problem) {
        this(problem, problem.start, problem.goal);
    }

    /** The bound from any state in {@code from} to entering {@code to}, on the problem's world. */
    public HopBound(Problem problem, Region from, Region to) {
        this.problem = problem;
        this.placing = !problem.inventory.isEmpty();
        this.problemStart = from;
        this.problemGoal = to;
        SpeedBound speed = new SpeedBound(problem);
        this.far = speed.reach(problem.maxTicks, Double.POSITIVE_INFINITY);
        final double[] top = {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        final List<double[]> boxes = new ArrayList<double[]>();
        final List<double[]> specials = new ArrayList<double[]>();
        final SparseBlockWorld world = problem.world;
        final boolean[] slimeWorld = {false};
        world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                String n = type.name();
                boolean slime = n.startsWith("minecraft:slime");
                if (slime) slimeWorld[0] = true;
                int kind = n.contains("ladder") || n.contains("vine") || n.contains("trapdoor") ? 0
                    : n.contains("water") || n.contains("lava") || n.equals("minecraft:web") ? 1 : -1;
                if (kind >= 0) specials.add(new double[] {x, y, z, kind});
                List<Aabb> out = new ArrayList<Aabb>();
                type.addCollisionBoxes(world, x, y, z, new Aabb(x - 1, y - 1, z - 1, x + 2, y + 3, z + 2), out);
                for (Aabb b : out) {
                    boxes.add(new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, slime ? 1 : 0, x, z});
                    top[0] = Math.max(top[0], b.maxY);
                    top[1] = Math.min(top[1], b.minY);
                }
            }
        });
        double highest = Math.max(top[0], Math.max(from.maxY, to.maxY)) + 2;
        double lowest = Math.min(Double.isInfinite(top[1]) ? from.minY : top[1], Math.min(from.minY, to.minY)) - 2;

        // Boxes by column, to find what covers a top and the ceilings over it.
        Map<Long, List<double[]>> byColumn = new HashMap<Long, List<double[]>>();
        for (double[] b : boxes) list(byColumn, (int) b[7], (int) b[8]).add(b);
        for (double[] b : boxes) {
            List<double[]> col = byColumn.get(key((int) b[7], (int) b[8]));
            if (covered(col, b)) continue;
            boolean slime = b[6] > 0;
            double up = slime ? impact(highest - b[4]) : JUMP;
            Node n = new Node(SURFACE, b[0] - HALF, b[3] + HALF, b[2] - HALF, b[5] + HALF, b[4], b[4], up, -0.0784D);
            n.ceilings = ceilings(byColumn, (int) b[7], (int) b[8], b[4]);
            add(n, (int) b[7], (int) b[8]);
        }
        for (double[] s : specials) {
            int x = (int) s[0], y = (int) s[1], z = (int) s[2];
            double down = s[3] == 0 ? -0.2254D : -TERMINAL;
            add(new Node(SPECIAL, x - HALF, x + 1 + HALF, z - HALF, z + 1 + HALF, y - 2, y + 1, JUMP, down), x, z);
        }
        start = new Node(START, from.minX, from.maxX, from.minZ, from.maxZ, from.minY, from.maxY,
            slimeWorld[0] ? impact(highest - from.minY) : JUMP, -TERMINAL);
        goal = new Node(GOAL, to.minX, to.maxX, to.minZ, to.maxZ, to.minY, to.maxY, 0, 0);
        for (Node n : new Node[] {start, goal}) {
            int id = nodes.size();
            nodes.add(n);
            for (int x = (int) Math.floor(n.x0); x <= (int) Math.floor(n.x1); x++) {
                for (int z = (int) Math.floor(n.z0); z <= (int) Math.floor(n.z1); z++) col(x, z).add(id);
            }
        }
        // The farthest any hop can go: the longest flight from the highest place to the lowest.
        double fastestUp = JUMP;
        for (Node n : nodes) fastestUp = Math.max(fastestUp, n.up);
        int longest = Math.min(far.length - 1, latestLanding(fastestUp, lowest - highest));
        radius = (int) Math.ceil(far[longest]) + 2;
        solve();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static List<double[]> list(Map<Long, List<double[]>> m, int x, int z) {
        List<double[]> l = m.get(key(x, z));
        if (l == null) m.put(key(x, z), l = new ArrayList<double[]>());
        return l;
    }

    private List<Integer> col(int x, int z) {
        List<Integer> l = columns.get(key(x, z));
        if (l == null) columns.put(key(x, z), l = new ArrayList<Integer>());
        return l;
    }

    private void add(Node n, int x, int z) {
        col(x, z).add(nodes.size());
        nodes.add(n);
    }

    /** A box in the same column right above the top of {@code t} that covers it all: nobody stands there. */
    private static boolean covered(List<double[]> column, double[] t) {
        for (double[] b : column) {
            if (b[1] >= t[4] - EPS && b[1] < t[4] + 1.8 - EPS
                && b[0] <= t[0] && b[3] >= t[3] && b[2] <= t[2] && b[5] >= t[5]) return true;
        }
        return false;
    }

    /** Gaps between the head of a player standing at {@code y} and the bottoms of boxes nearby, under 1.26. */
    private static double[] ceilings(Map<Long, List<double[]>> byColumn, int x, int z, double y) {
        TreeSet<Double> gaps = new TreeSet<Double>();
        for (int dx = -CEILING_REACH; dx <= CEILING_REACH; dx++) {
            for (int dz = -CEILING_REACH; dz <= CEILING_REACH; dz++) {
                List<double[]> col = byColumn.get(key(x + dx, z + dz));
                if (col == null) continue;
                for (double[] b : col) {
                    double g = b[1] - y - 1.8;
                    if (g >= -EPS && g < 1.26) gaps.add(Math.max(0, g));
                }
            }
        }
        double[] out = new double[gaps.size()];
        int i = 0;
        for (double g : gaps) out[i++] = g;
        return out;
    }

    /** How fast the player hits the ground after falling {@code h} from rest: a slime block bounces this fast at most. */
    static double impact(double h) {
        double y = 0, m = 0;
        for (int t = 0; t < 10000 && y < h; t++) {
            m = (m - 0.08D) * 0.9800000190734863D;
            y -= m;
        }
        return Math.min(TERMINAL, -m) + 1e-6;
    }

    /** Heights after each tick of a free flight that starts with motionY {@code u}; index 0 is 0. */
    private static double[] flight(double u, int ticks) {
        double[] y = new double[ticks + 1];
        double m = u;
        for (int t = 1; t <= ticks; t++) {
            if (t > 1 && Math.abs(m) < 0.003D) m = 0;
            y[t] = y[t - 1] + m;
            m = (m - 0.08D) * 0.9800000190734863D;
        }
        return y;
    }

    /** The first tick a flight from motionY {@code u} is at least {@code r} up, while rising; INF if never. */
    static int riseTicks(double u, double r) {
        double[] y = flight(u, 200);
        for (int t = 1; t < y.length; t++) {
            if (y[t] < y[t - 1]) break;
            if (y[t] >= r - EPS) return t;
        }
        return INF;
    }

    /** The last tick a flight from motionY {@code u} can land at height {@code r}: when it first comes down to it. */
    static int latestLanding(double u, double r) {
        double y = 0, m = u;
        for (int t = 1; t < 100000; t++) {
            if (t > 1 && Math.abs(m) < 0.003D) m = 0;
            double ny = y + m;
            if (ny < y && ny <= r + EPS) return t;
            y = ny;
            m = (m - 0.08D) * 0.9800000190734863D;
        }
        return INF;
    }

    /** The fewest ticks to fall {@code r}, falling at {@code down} (negative) on the first tick. */
    static int fallTicks(double down, double r) {
        double y = 0, m = down;
        for (int t = 1; t < 100000; t++) {
            y += m;
            if (-y >= r - EPS) return t;
            m = Math.max(-TERMINAL, (m - 0.08D) * 0.9800000190734863D);
        }
        return INF;
    }

    /** The fewest ticks of a jump from a top with {@code ceilings} near it that lands {@code r} higher. */
    static int jumpOnto(double[] ceilings, double r) {
        TreeSet<Double> floors = new TreeSet<Double>();
        floors.add(r);
        int best = INF;
        int free = SpeedBound.JumpArcs.arcOrNone(Double.POSITIVE_INFINITY, floors);
        if (free < INF) best = free + 1;
        for (double g : ceilings) {
            if (g < r - EPS) continue;
            int a = SpeedBound.JumpArcs.arcOrNone(g, floors);
            if (a < INF) best = Math.min(best, a + 1);
        }
        return best;
    }

    private final Map<Long, Integer> jumpMemo = new HashMap<Long, Integer>();

    private int jumpOntoMemo(Node a, double r) {
        long k = (Double.doubleToLongBits(r) * 31) ^ Arrays.hashCode(a.ceilings);
        Integer v = jumpMemo.get(k);
        if (v == null) jumpMemo.put(k, v = jumpOnto(a.ceilings, r));
        return v;
    }

    /** The fewest ticks of a hop from an anchor in {@code a} to the next one in {@code b} (or into the goal), or INF. */
    int hop(Node a, Node b) {
        double d = a.distance(b);
        if (d > far[far.length - 1]) return INF;
        int fewest;
        boolean lands = b.type == SURFACE;
        double rise = b.yLo - a.yHi;
        double drop = a.yLo - b.yHi;
        boolean slime = a.up > JUMP;
        if (rise > EPS) {
            if (a.type == SPECIAL) {
                fewest = (int) Math.ceil(rise / STEP - 1e-9);
            } else if (slime || a.type == START) {
                int t = riseTicks(a.up, rise);
                if (t < INF && lands) t++;
                fewest = rise <= STEP + EPS ? 1 : t;
            } else if (rise <= STEP + EPS) {
                fewest = 1;
            } else {
                fewest = lands ? jumpOntoMemo(a, rise) : riseTicks(a.up, rise);
            }
        } else if (drop > EPS) {
            fewest = fallTicks(a.down, drop);
        } else {
            fewest = 1;
        }
        if (fewest >= INF) return INF;
        int latest = Math.max(1, latestLanding(a.up, b.yLo - a.yHi));
        latest = Math.min(latest, far.length - 1);
        fewest = Math.max(1, fewest);
        if (fewest > latest) return INF;
        // The first tick count in [fewest, latest] that covers the distance.
        int lo = fewest, hi = latest + 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (far[mid] >= d) hi = mid;
            else lo = mid + 1;
        }
        return lo <= latest ? lo : INF;
    }

    private void solve() {
        int n = nodes.size();
        toGoal = new int[n];
        Arrays.fill(toGoal, INF);
        if (placing) {
            Arrays.fill(toGoal, 0);
            bound = 0;
            return;
        }
        int g = nodes.indexOf(goal), s = nodes.indexOf(start);
        if (problemStart.intersects(problemGoal)) {
            bound = 0;
            toGoal[s] = 0;
        }
        toGoal[g] = 0;
        PriorityQueue<long[]> queue = new PriorityQueue<long[]>((p, q) -> Long.compare(p[0], q[0]));
        queue.add(new long[] {0, g});
        boolean[] done = new boolean[n];
        while (!queue.isEmpty()) {
            long[] e = queue.poll();
            int bi = (int) e[1];
            if (done[bi]) continue;
            done[bi] = true;
            Node b = nodes.get(bi);
            int cx0 = (int) Math.floor(b.x0) - radius, cx1 = (int) Math.floor(b.x1) + radius;
            int cz0 = (int) Math.floor(b.z0) - radius, cz1 = (int) Math.floor(b.z1) + radius;
            for (int x = cx0; x <= cx1; x++) {
                for (int z = cz0; z <= cz1; z++) {
                    List<Integer> col = columns.get(key(x, z));
                    if (col == null) continue;
                    for (int ai : col) {
                        if (done[ai] || ai == g) continue;
                        int c = hop(nodes.get(ai), b);
                        if (c >= INF) continue;
                        int t = toGoal[bi] + c;
                        if (t < toGoal[ai]) {
                            toGoal[ai] = t;
                            queue.add(new long[] {t, ai});
                        }
                    }
                }
            }
        }
        bound = problemStart.intersects(problemGoal) ? 0 : toGoal[s];
    }

    /** A lower bound on the ticks from the start entry to the goal; {@link #INF} if no run can get there. */
    public int ticks() {
        return bound;
    }

    /**
     * A lower bound on the ticks to the goal for a player standing on the ground with the feet at
     * {@code feetY} (the moment of a landing, say): the least over the tops it can be standing on.
     * 0 when it matches none (or with blocks to place).
     */
    public int ticksFromGround(double x, double feetY, double z) {
        if (placing) return 0;
        int best = INF;
        boolean found = false;
        for (int cx = (int) Math.floor(x) - 1; cx <= (int) Math.floor(x) + 1; cx++) {
            for (int cz = (int) Math.floor(z) - 1; cz <= (int) Math.floor(z) + 1; cz++) {
                List<Integer> col = columns.get(key(cx, cz));
                if (col == null) continue;
                for (int i : col) {
                    Node n = nodes.get(i);
                    if (n.type != SURFACE || Math.abs(n.yLo - feetY) > 1e-6 || !n.contains(x, z)) continue;
                    found = true;
                    best = Math.min(best, toGoal[i]);
                }
            }
        }
        return found ? best : 0;
    }

    int nodeCount() {
        return nodes.size();
    }

    @Override
    public String toString() {
        return String.format("nodes %d radius %d", nodes.size(), radius);
    }
}
