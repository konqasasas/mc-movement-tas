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
 * block widened the same way over a range of feet heights. The game moves y before x and z, so the
 * top under a landing is under the position at the start or at the end of that tick; each hop
 * therefore gets one tick more of horizontal reach than it has ticks. Walking along a floor therefore costs a
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
    public static final int INF = Integer.MAX_VALUE / 4;

    static final class Node {
        final int type;
        final double x0, x1, z0, z1, yLo, yHi;
        /** Ceilings near a top: rows of gap over the head, then the box's footprint widened by the half width. */
        double[][] ceilingBoxes = new double[0][];
        /**
         * The heights of the tops near a top that a fall can land on and step up from onto it in the same
         * tick (its own height and those up to 0.6 lower).
         */
        double[] stepFrom = new double[0];
        /** The fastest upward and downward motionY at the start of the first tick of a flight from here. */
        final double up, down;

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
    /** {@link SpeedBound#hops}: reach and end speed of a hop from the ground, by ticks and start speed. */
    private final double[][] hopFar, hopFast, hopLast;
    /** The start's horizontal speed on the ground, or -1 for any state in the start box. */
    private final double startSpeed;
    private double lowestTop;
    /** Also run the forward search over positions (stronger, slower). */
    static boolean useForward = System.getenv("MCTAS_NOFORWARD") == null;
    /** Ticks from each node to the goal; {@link #INF} when the goal cannot be reached from there. */
    private int[] toGoal;
    private int bound;

    public HopBound(Problem problem) {
        this(problem, problem.start, problem.goal);
    }

    /** The bound from any state in {@code from} to entering {@code to}, on the problem's world. */
    public HopBound(Problem problem, Region from, Region to) {
        this(problem, from, to, -1);
    }

    /**
     * The bound from a state in {@code from} to entering {@code to}; with {@code groundSpeed} at least 0,
     * only from states that have just ended a tick on the ground with at most that horizontal speed
     * (a landing in a run), which the forward search uses to limit the first hop.
     */
    public HopBound(Problem problem, Region from, Region to, double groundSpeed) {
        this.problem = problem;
        this.startSpeed = groundSpeed;
        this.placing = !problem.inventory.isEmpty();
        this.problemStart = from;
        this.problemGoal = to;
        SpeedBound speed = new SpeedBound(problem);
        this.far = speed.reach(problem.maxTicks, Double.POSITIVE_INFINITY);
        double[][][] h = speed.hops(far.length - 1);
        this.hopFar = h[0];
        this.hopFast = h[1];
        this.hopLast = h[2];
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
        lowestTop = lowest;

        // A jump reaches its apex within 7 ticks; the ceilings it can hit are within that reach of the take-off.
        int ceilingReach = (int) Math.ceil(HALF + far[Math.min(7, far.length - 1)]) + 1;
        // Boxes by column, to find what covers a top and the ceilings over it.
        Map<Long, List<double[]>> byColumn = new HashMap<Long, List<double[]>>();
        for (double[] b : boxes) list(byColumn, (int) b[7], (int) b[8]).add(b);
        for (double[] b : boxes) {
            List<double[]> col = byColumn.get(key((int) b[7], (int) b[8]));
            if (covered(col, b)) continue;
            boolean slime = b[6] > 0;
            double up = slime ? impact(highest - b[4]) : JUMP;
            Node n = new Node(SURFACE, b[0] - HALF, b[3] + HALF, b[2] - HALF, b[5] + HALF, b[4], b[4], up, -0.0784D);
            n.ceilingBoxes = ceilings(byColumn, (int) b[7], (int) b[8], b[4], ceilingReach);
            n.stepFrom = stepFrom(byColumn, (int) b[7], (int) b[8], b[4]);
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
        radius = (int) Math.ceil(far[Math.min(far.length - 1, longest + 1)]) + 2;
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

    /** Boxes near a top whose bottoms are 0 to 1.26 over the head of a player standing on it, as {gap, x0, x1, z0, z1}. */
    private static double[][] ceilings(Map<Long, List<double[]>> byColumn, int x, int z, double y, int reach) {
        List<double[]> out = new ArrayList<double[]>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                List<double[]> col = byColumn.get(key(x + dx, z + dz));
                if (col == null) continue;
                for (double[] b : col) {
                    double g = b[1] - y - 1.8;
                    if (g >= -EPS && g < 1.26) {
                        out.add(new double[] {Math.max(0, g), b[0] - HALF, b[3] + HALF, b[2] - HALF, b[5] + HALF});
                    }
                }
            }
        }
        return out.toArray(new double[0][]);
    }

    /** Tops in the columns around (x, z) from 0.6 below {@code y} up to {@code y}. */
    private static double[] stepFrom(Map<Long, List<double[]>> byColumn, int x, int z, double y) {
        TreeSet<Double> hs = new TreeSet<Double>();
        hs.add(y);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<double[]> col = byColumn.get(key(x + dx, z + dz));
                if (col == null) continue;
                for (double[] b : col) {
                    if (b[4] >= y - STEP - EPS && b[4] <= y + EPS) hs.add(b[4]);
                }
            }
        }
        double[] out = new double[hs.size()];
        int i = 0;
        for (double h : hs) out[i++] = h;
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

    /** Feet heights after each tick of a jump under a ceiling {@code g} over the head (infinite for none); index 0 is 0. */
    private double[] jumpArc(double g) {
        double[] y = arcMemo.get(g);
        if (y != null) return y;
        y = new double[far.length + 2];
        double m = JUMP;
        for (int t = 1; t < y.length; t++) {
            if (t > 1 && Math.abs(m) < 0.003D) m = 0;
            double ny = y[t - 1] + m;
            if (m > 0 && ny > g) {
                ny = Math.max(y[t - 1], g);
                m = 0;
            }
            y[t] = ny;
            m = (m - 0.08D) * 0.9800000190734863D;
        }
        arcMemo.put(g, y);
        return y;
    }

    private final Map<Double, double[]> arcMemo = new HashMap<Double, double[]>();

    /** The first tick a flight {@code y} comes down onto height {@code h} (relative to the take-off), or INF. */
    private static int landsOn(double[] y, double h) {
        for (int t = 1; t < y.length; t++) {
            if (y[t] < y[t - 1] && y[t] <= h + EPS && y[t - 1] >= h - EPS) return t;
            if (y[t] < y[t - 1] && y[t - 1] < h - EPS) return INF;
        }
        return INF;
    }

    /** The first tick a flight {@code y} has the feet in [lo, hi] (relative to the take-off), or INF. */
    private static int passes(double[] y, double lo, double hi) {
        for (int t = 1; t < y.length; t++) {
            if (y[t] >= lo - EPS && y[t] <= hi + EPS) return t;
            if (y[t] < y[t - 1] && y[t] < lo - STEP - EPS) return INF;
        }
        return INF;
    }

    /**
     * The first tick of a flight {@code y} from {@code a} that ends standing in {@code b}: landing on
     * one of b's step-from tops (b's own top, or one up to 0.6 below it that the landing steps up from);
     * for the goal or a ladder, liquid or web, also passing through its feet range or landing up to 0.6
     * below it. INF if never.
     */
    private static int earliest(double[] y, Node a, Node b) {
        if (b.type == SURFACE) {
            int best = INF;
            for (double h : b.stepFrom) best = Math.min(best, landsOn(y, h - a.yHi));
            return best;
        }
        double lo = b.yLo - a.yHi, hi = b.yHi - a.yLo;
        int best = passes(y, lo, hi);
        for (int t = 1; t < y.length && best == INF; t++) {
            if (y[t] < y[t - 1] && y[t] <= hi + EPS && y[t - 1] >= lo - STEP - EPS) best = t;
            if (y[t] < y[t - 1] && y[t - 1] < lo - STEP - EPS) break;
        }
        return best;
    }

    /** The last tick a flight {@code y} can still end standing in {@code b} (see {@link #earliest}). */
    private static int latest(double[] y, Node a, Node b) {
        double lo = (b.type == SURFACE ? b.stepFrom[0] : b.yLo - STEP) - a.yHi;
        for (int t = 1; t < y.length; t++) {
            if (y[t] < y[t - 1] && y[t] <= lo + EPS) return t;
        }
        return y.length - 1;
    }

    /** dropK[k]: how far a player walking off a top has fallen after k ticks (it falls 0.0784 on the first). */
    private double[] walkDrop;

    private double walkDrop(int k) {
        if (walkDrop == null) {
            walkDrop = new double[far.length + 2];
            double m = -0.0784D, y = 0;
            for (int t = 1; t < walkDrop.length; t++) {
                y -= m;
                walkDrop[t] = y;
                m = Math.max(-TERMINAL, (m - 0.08D) * 0.9800000190734863D);
            }
        }
        return walkDrop[Math.min(k, walkDrop.length - 1)];
    }

    /** The first n in [lo, hi] with one tick to spare over the distance (see {@link #hop}), or INF. */
    private int covering(int lo, int hi, double d) {
        hi = Math.min(hi, far.length - 2);
        lo = Math.max(lo, 1);
        if (lo > hi) return INF;
        int a = lo, b = hi + 1;
        while (a < b) {
            int mid = (a + b) >>> 1;
            if (far[mid + 1] >= d) b = mid;
            else a = mid + 1;
        }
        return a <= hi ? a : INF;
    }

    /**
     * The fewest ticks of a hop from an anchor in {@code a} to the next one in {@code b} (or into the
     * goal), or INF. The horizontal reach has one tick to spare: a landing moves y before x and z, so
     * the top under a landing is under the position before or after that tick, and the positions two
     * anchors stand for can be one tick further apart than the ticks between them.
     */
    int hop(Node a, Node b) {
        double d = a.distance(b);
        if (d > far[far.length - 1]) return INF;
        int best = INF;
        int[] r = ranges(a, b);
        for (int i = 0; i < r.length; i += 2) best = Math.min(best, covering(r[i], r[i + 1], d));
        return best;
    }

    /**
     * The tick counts a hop from an anchor in {@code a} to the next one in {@code b} (or into the goal)
     * can take as far as heights go, as ranges {lo1, hi1, lo2, hi2, ...}: one tick on the ground, a fall
     * from walking off, or a jump (under a ceiling it can be under on the way).
     */
    int[] ranges(Node a, Node b) {
        boolean lands = b.type == SURFACE;
        // Feet heights of b relative to a: the range to reach.
        double lo = b.yLo - a.yHi, hi = b.yHi - a.yLo;
        int[] out = new int[6];
        int k = 0;
        if (a.type == SURFACE && a.up <= JUMP) {
            // One tick on the ground, stepping up 0.6 at most or dropping onto a top just below.
            if (lo <= STEP + EPS && hi >= -0.0784D - EPS) {
                out[k++] = 1;
                out[k++] = 1;
            }
            // Walking off: landing on tick n on a top between walkDrop(n - 1) and walkDrop(n) down (b's
            // own, or one it steps up from), or into the goal or a ladder, liquid or web on the way down.
            double reachLo = lands ? b.stepFrom[0] - a.yHi : lo - STEP;
            double reachHi = lands ? b.yHi - a.yLo : hi;
            int first = Math.max(2, reachHi < -EPS ? fallTicks(a.down, -reachHi) : 1);
            if (first < INF && -walkDrop(first - 1) >= reachLo - EPS) {
                int last = first;
                while (last + 1 < far.length && -walkDrop(last) >= reachLo - EPS) last++;
                out[k++] = first;
                out[k++] = last;
            }
            // A jump, under any ceiling near the take-off that the flight can be under when it gets there.
            double[] free = jumpArc(Double.POSITIVE_INFINITY);
            int last = latest(free, a, b);
            int firstJump = earliest(free, a, b);
            double lowest = lands ? b.stepFrom[0] - a.yHi : lo - STEP;
            for (double[] c : a.ceilingBoxes) {
                if (c[0] < lowest - EPS) continue;
                double[] y = jumpArc(c[0]);
                int n = earliest(y, a, b);
                if (n >= firstJump) continue;
                int t = 1;
                while (t < y.length - 1 && y[t] < c[0] - EPS) t++;
                double dx0 = Math.max(0, Math.max(c[1] - a.x1, a.x0 - c[2])), dz0 = Math.max(0, Math.max(c[3] - a.z1, a.z0 - c[4]));
                double dx1 = Math.max(0, Math.max(c[1] - b.x1, b.x0 - c[2])), dz1 = Math.max(0, Math.max(c[3] - b.z1, b.z0 - c[4]));
                if (Math.hypot(dx0, dz0) > far[Math.min(far.length - 1, t + 1)]) continue;
                if (Math.hypot(dx1, dz1) > far[Math.min(far.length - 1, Math.max(0, n - t + 1))]) continue;
                firstJump = n;
            }
            if (firstJump < INF && Math.max(2, firstJump) <= last) {
                out[k++] = Math.max(2, firstJump);
                out[k++] = last;
            }
            return Arrays.copyOf(out, k);
        }
        // Out of a ladder, liquid or web, from the start, or bouncing off slime: motionY at most a.up
        // (0.6 a tick out of a ladder, liquid or web), and a step of 0.6 at the end.
        int fewest;
        if (lo > STEP + EPS) {
            if (a.type == SPECIAL) {
                fewest = (int) Math.ceil((lo - STEP) / STEP - 1e-9);
            } else {
                int t = riseTicks(a.up, lo - STEP);
                fewest = t < INF && lands ? t + 1 : t;
                if (!lands) fewest = Math.min(fewest, riseTicks(a.up, lo));
            }
        } else if (hi < -EPS) {
            fewest = fallTicks(a.down, -hi);
        } else {
            fewest = 1;
        }
        if (fewest >= INF) return new int[0];
        int latest = Math.min(far.length - 2, Math.max(1, latestLanding(a.up, lo - STEP)));
        fewest = Math.max(1, fewest);
        return fewest <= latest ? new int[] {fewest, latest} : new int[0];
    }

    // ---- forward search over end-of-tick positions ----

    /** Directions of the polygon's sides: k * 22.5 degrees. */
    private static final int DIRS = 8;
    private static final double[] COS = new double[DIRS], SIN = new double[DIRS];

    static {
        for (int k = 0; k < DIRS; k++) {
            COS[k] = Math.cos(k * Math.PI / DIRS);
            SIN[k] = Math.sin(k * Math.PI / DIRS);
        }
        COS[DIRS / 2] = 0;
    }

    /**
     * A convex polygon of positions: bounds {@code v[2k] <= x cos + z sin <= v[2k + 1]} along
     * {@link #DIRS} directions (x is direction 0, z direction DIRS / 2), kept tight after an
     * intersection. Growing it by r adds r to every bound, which covers the disc of radius r round
     * every point (the polygon round a disc is at most 2% wider than it).
     */
    static final class Oct {
        final double[] v;

        Oct(double[] v) {
            this.v = v;
        }

        static Oct rect(double x0, double x1, double z0, double z1) {
            double[] w = new double[2 * DIRS];
            for (int k = 0; k < DIRS; k++) {
                double a = COS[k] * x0, b = COS[k] * x1, c = SIN[k] * z0, d = SIN[k] * z1;
                w[2 * k] = Math.min(a, b) + Math.min(c, d);
                w[2 * k + 1] = Math.max(a, b) + Math.max(c, d);
            }
            return new Oct(w);
        }

        Oct grow(double r) {
            double[] w = v.clone();
            for (int k = 0; k < DIRS; k++) {
                w[2 * k] -= r;
                w[2 * k + 1] += r;
            }
            return new Oct(w);
        }

        /** The intersection, or null when it is empty. */
        Oct and(Oct o) {
            // Both are tight, so when one holds the other the smaller one is the answer.
            if (contains(o)) return o;
            if (o.contains(this)) return this;
            double[] w = new double[2 * DIRS];
            for (int i = 0; i < 2 * DIRS; i += 2) {
                w[i] = Math.max(v[i], o.v[i]);
                w[i + 1] = Math.min(v[i + 1], o.v[i + 1]);
                if (w[i] > w[i + 1] + EPS) return null;
            }
            // Clip the bounding box by every bound, then read the bounds back off the corners.
            double x0 = w[0], x1 = w[1], z0 = w[DIRS], z1 = w[DIRS + 1];
            double[] px = {x0, x1, x1, x0}, pz = {z0, z0, z1, z1};
            int n = 4;
            for (int k = 1; k < DIRS && n > 0; k++) {
                if (k == DIRS / 2) continue;
                for (int side = 0; side < 2 && n > 0; side++) {
                    double sign = side == 0 ? -1 : 1, bound = side == 0 ? -w[2 * k] : w[2 * k + 1];
                    double[] qx = new double[n + 1], qz = new double[n + 1];
                    int m = 0;
                    for (int a = 0; a < n; a++) {
                        int b = (a + 1) % n;
                        double fa = sign * (COS[k] * px[a] + SIN[k] * pz[a]) - bound;
                        double fb = sign * (COS[k] * px[b] + SIN[k] * pz[b]) - bound;
                        if (fa <= EPS) {
                            qx[m] = px[a];
                            qz[m++] = pz[a];
                        }
                        if ((fa < -EPS && fb > EPS) || (fa > EPS && fb < -EPS)) {
                            double f = fa / (fa - fb);
                            if (m == qx.length) {
                                qx = Arrays.copyOf(qx, m * 2);
                                qz = Arrays.copyOf(qz, m * 2);
                            }
                            qx[m] = px[a] + f * (px[b] - px[a]);
                            qz[m++] = pz[a] + f * (pz[b] - pz[a]);
                        }
                    }
                    px = qx;
                    pz = qz;
                    n = m;
                }
            }
            if (n == 0) return null;
            for (int k = 0; k < DIRS; k++) {
                double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
                for (int a = 0; a < n; a++) {
                    double d = COS[k] * px[a] + SIN[k] * pz[a];
                    lo = Math.min(lo, d);
                    hi = Math.max(hi, d);
                }
                // Never tighter than the bounds themselves (the corners are rounded).
                w[2 * k] = Math.max(w[2 * k], lo - EPS);
                w[2 * k + 1] = Math.min(w[2 * k + 1], hi + EPS);
            }
            return new Oct(w);
        }

        /** The smallest polygon with these directions holding both. */
        Oct hull(Oct o) {
            double[] w = new double[2 * DIRS];
            for (int i = 0; i < 2 * DIRS; i += 2) {
                w[i] = Math.min(v[i], o.v[i]);
                w[i + 1] = Math.max(v[i + 1], o.v[i + 1]);
            }
            return new Oct(w);
        }

        boolean contains(Oct o) {
            for (int i = 0; i < 2 * DIRS; i += 2) {
                if (o.v[i] < v[i] - EPS || o.v[i + 1] > v[i + 1] + EPS) return false;
            }
            return true;
        }

        /** A lower bound on the distance to a box, from the bounding box. */
        double gap(Node b) {
            double dx = Math.max(0, Math.max(b.x0 - v[1], v[0] - b.x1));
            double dz = Math.max(0, Math.max(b.z0 - v[DIRS + 1], v[DIRS] - b.z1));
            return Math.hypot(dx, dz);
        }
    }

    /** A settled label: where the player can be at the end of an anchor tick, and how fast at most. */
    private static final class Seen {
        final Oct where;
        final int speed;

        Seen(Oct where, int speed) {
            this.where = where;
            this.speed = speed;
        }
    }

    /** Speeds are kept in steps of this many {@link SpeedBound#HOP_GRID}s per pending label. */
    private static final int SPEED_BUCKET = 4;

    /**
     * Ticks from the start to the goal, tracking where the player can be at the end of each anchor
     * tick and how fast it can be going: a hop of n ticks moves it at most far[n] (from the ground, at
     * most what a ground tick and then free flight get from that speed), and it ends over the next
     * place (widened by one tick's move, since a landing's top is under the position before or after
     * the tick) or in the goal. Label-setting by time; a label is dropped when an earlier one at the
     * same place is at least as fast and covers it.
     */
    private int forward() {
        int g = nodes.indexOf(goal), s = nodes.indexOf(start);
        double step = far[1];
        int top = hopFar[0].length - 1;
        // suffixFast[n][i]: the fastest any hop of n or more ticks from speed i ends.
        double[][] suffixFast = new double[hopFast.length + 1][top + 1];
        for (int n = hopFast.length - 1; n >= 1; n--) {
            for (int i = 0; i <= top; i++) suffixFast[n][i] = Math.max(hopFast[n][i], suffixFast[n + 1][i]);
        }
        Oct[] places = new Oct[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            places[i] = i == g || i == s ? Oct.rect(n.x0, n.x1, n.z0, n.z1)
                : Oct.rect(n.x0 - step, n.x1 + step, n.z0 - step, n.z1 + step);
        }
        List<List<Seen>> settled = new ArrayList<List<Seen>>();
        for (int i = 0; i < nodes.size(); i++) settled.add(new ArrayList<Seen>());
        // Labels waiting, one per (place, tick, speed bucket), merged into their hull at the fastest speed.
        Map<Long, Oct> pending = new HashMap<Long, Oct>();
        Map<Long, Integer> pendingSpeed = new HashMap<Long, Integer>();
        PriorityQueue<long[]> queue = new PriorityQueue<long[]>((p, q) -> Long.compare(p[0], q[0]));
        int v0 = startSpeed >= 0 ? speedIndex(startSpeed, top) : top;
        long k0 = label(0, s, v0);
        pending.put(k0, places[s]);
        pendingSpeed.put(k0, v0);
        queue.add(new long[] {0, k0});
        int[][] nbr = new int[nodes.size()][];
        int[][][] nbrRanges = new int[nodes.size()][][];
        int popped = 0;
        while (!queue.isEmpty()) {
            long[] e = queue.poll();
            int t = (int) e[0];
            Oct where = pending.remove(e[1]);
            if (where == null) continue;
            int v = pendingSpeed.remove(e[1]);
            int ai = (int) ((e[1] >>> 8) & 0xFFFFFF);
            if (ai == g) return t;
            if (covered(settled.get(ai), where, v)) continue;
            settled.get(ai).add(new Seen(where, v));
            if (++popped > 3000000) return 0;
            if (nbr[ai] == null) neighbours(ai, nbr, nbrRanges, s, step);
            Node a = nodes.get(ai);
            boolean ground = a.type == SURFACE || ai == s && startSpeed >= 0;
            for (int j = 0; j < nbr[ai].length; j++) {
                int bi = nbr[ai][j];
                Node b = nodes.get(bi);
                // A landing on a top is decided where the tick starts (y moves first), so the tick ends
                // within its own move of the place: from the ground, at most the hop's last tick.
                boolean exact = b.type == SURFACE && ground;
                double gap = where.gap(b) - (bi == g ? 0 : step);
                int[] r = nbrRanges[ai][j];
                for (int i = 0; i < r.length; i += 2) {
                    for (int n = r[i]; n <= r[i + 1] && t + n <= problem.maxTicks; n++) {
                        double reach = ground ? Math.min(far[n], hopFar[n][v]) : far[n];
                        if (reach < gap) continue;
                        Oct place = exact ? Oct.rect(b.x0, b.x1, b.z0, b.z1).grow(Math.min(step, hopLast[n][v])) : places[bi];
                        Oct o = where.grow(reach).and(place);
                        if (o == null) continue;
                        int w = bi == g ? 0 : ground ? speedIndex(hopFast[n][v], top) : top;
                        if (!covered(settled.get(bi), o, w)) {
                            long k = label(t + n, bi, w);
                            Oct old = pending.get(k);
                            if (old == null) {
                                pending.put(k, o);
                                pendingSpeed.put(k, w);
                                queue.add(new long[] {t + n, k});
                            } else {
                                if (!old.contains(o)) pending.put(k, old.hull(o));
                                if (w > pendingSpeed.get(k)) pendingSpeed.put(k, w);
                            }
                        }
                        // Longer hops land in the same place later and no faster: nothing new.
                        if (!exact && o.contains(places[bi]) && (!ground || w >= speedIndex(suffixFast[n + 1][v], top))) break;
                    }
                }
            }
        }
        return INF;
    }

    /** The key of a pending label: tick, place and speed bucket. */
    private static long label(int t, int node, int speed) {
        return ((long) t << 32) | ((long) node << 8) | (speed / SPEED_BUCKET);
    }

    private static int speedIndex(double v, int top) {
        return Math.min(top, (int) Math.ceil(v / SpeedBound.HOP_GRID - 1e-9));
    }

    /** The places a hop from {@code ai} can reach, with the tick counts each can take. */
    private void neighbours(int ai, int[][] nbr, int[][][] nbrRanges, int s, double step) {
        Node a = nodes.get(ai);
        int reach = (int) Math.ceil(far[Math.min(far.length - 1, latestLanding(a.up, lowestTop - a.yHi - 2) + 1)] + step) + 2;
        List<Integer> ids = new ArrayList<Integer>();
        List<int[]> rs = new ArrayList<int[]>();
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (int x = (int) Math.floor(a.x0) - reach; x <= (int) Math.floor(a.x1) + reach; x++) {
            for (int z = (int) Math.floor(a.z0) - reach; z <= (int) Math.floor(a.z1) + reach; z++) {
                List<Integer> col = columns.get(key(x, z));
                if (col == null) continue;
                for (int bi : col) {
                    if (bi == s || !seen.add(bi)) continue;
                    Node b = nodes.get(bi);
                    // Cheap check first: no flight down to b's lowest landing height lasts longer.
                    double deepest = (b.type == SURFACE ? b.stepFrom[0] : b.yLo - STEP) - a.yHi;
                    int longest = Math.min(far.length - 1, latestLanding(Math.max(a.up, JUMP), deepest) + 1);
                    if (a.distance(b) - 3 * step > far[longest]) continue;
                    int[] r = ranges(a, b);
                    if (r.length == 0) continue;
                    int hi = 0;
                    for (int i = 1; i < r.length; i += 2) hi = Math.max(hi, r[i]);
                    // From anywhere a label at a can be (a's place widened by a tick's move).
                    if (a.distance(b) - 3 * step > far[Math.min(far.length - 1, hi)]) continue;
                    ids.add(bi);
                    rs.add(r);
                }
            }
        }
        nbr[ai] = new int[ids.size()];
        nbrRanges[ai] = new int[ids.size()][];
        for (int i = 0; i < ids.size(); i++) {
            nbr[ai][i] = ids.get(i);
            nbrRanges[ai][i] = rs.get(i);
        }
    }

    private static boolean covered(List<Seen> list, Oct o, int speed) {
        for (Seen p : list) if (p.speed >= speed && p.where.contains(o)) return true;
        return false;
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
        if (bound > 0 && bound < INF && useForward) bound = Math.max(bound, forward());
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

    /** The cheapest chain of places from the start to the goal, one line per hop, for looking at where the bound comes from. */
    public List<String> path() {
        List<String> out = new ArrayList<String>();
        int s = nodes.indexOf(start), g = nodes.indexOf(goal);
        int cur = s;
        for (int guard = 0; cur != g && guard < 10000 && toGoal[cur] < INF; guard++) {
            Node a = nodes.get(cur);
            int next = -1, cost = 0;
            for (int bi = 0; bi < nodes.size(); bi++) {
                if (bi == cur || toGoal[bi] >= INF) continue;
                int c = hop(a, nodes.get(bi));
                if (c < INF && c + toGoal[bi] == toGoal[cur]) {
                    next = bi;
                    cost = c;
                    break;
                }
            }
            if (next < 0) break;
            Node b = nodes.get(next);
            out.add(String.format("%d ticks: (%.2f..%.2f, %.3f, %.2f..%.2f) -> %s (%.2f..%.2f, %.3f, %.2f..%.2f)", cost,
                a.x0, a.x1, a.yHi, a.z0, a.z1, b.type == SURFACE ? "top" : b.type == SPECIAL ? "special" : "goal",
                b.x0, b.x1, b.yLo, b.z0, b.z1));
            cur = next;
        }
        return out;
    }

    int nodeCount() {
        return nodes.size();
    }

    @Override
    public String toString() {
        return String.format("nodes %d radius %d", nodes.size(), radius);
    }
}
