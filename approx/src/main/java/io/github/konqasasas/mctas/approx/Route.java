package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The coarse plan: which surfaces to land on, in which order.
 *
 * <p>Every block top the player can stand on becomes a surface. Two surfaces are joined by a walk
 * edge when the player's centre can pass from one to the other without leaving the ground, and by a
 * jump edge when a full-momentum sprint jump ({@link JumpTable}) covers the gap. Dijkstra from the
 * surfaces under the start box to the goal gives a chain of surfaces; consecutive surfaces joined by
 * walking at the same height form one platform. Each platform after the first is a waypoint that the
 * tick search must land on in order, and the last waypoint is the goal box itself.
 *
 * <p>The route also gives the tick search its distance estimate: a taut string through the chain,
 * measured from the player's position through the next waypoint.
 */
public final class Route {
    /** Sprint speed on the ground, blocks per tick; turns distances into tick costs. */
    static final double RUN_SPEED = 0.2806;
    private static final double EPS = 1e-6;
    private static final double STRING_INSET = 0.15;

    /** A standable block top: the footprint where the player's centre stands on it, and its height. */
    public static final class Surface {
        public final int x, y, z;
        public final double top;
        /** Footprint grown by the player's half width: the centre is supported anywhere inside. */
        public final double minX, minZ, maxX, maxZ;
        /**
         * A ladder or vine block instead of a block top: the player holds on in it with the feet
         * anywhere from {@code top} (its bottom) up to a block higher, and its footprint is the block
         * itself, since the block at the feet decides the hold.
         */
        public final boolean climb;

        Surface(int x, int y, int z, double top, double minX, double minZ, double maxX, double maxZ) {
            this(x, y, z, top, minX - 0.3, minZ - 0.3, maxX + 0.3, maxZ + 0.3, false);
        }

        private Surface(int x, int y, int z, double top, double minX, double minZ, double maxX, double maxZ, boolean climb) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.top = top;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.climb = climb;
        }

        static Surface climbCell(int x, int y, int z) {
            return new Surface(x, y, z, y, x, z, x + 1, z + 1, true);
        }

        double dist(double px, double pz) {
            double dx = Math.max(0, Math.max(minX - px, px - maxX));
            double dz = Math.max(0, Math.max(minZ - pz, pz - maxZ));
            return Math.sqrt(dx * dx + dz * dz);
        }

        double cx() {
            return (minX + maxX) / 2;
        }

        boolean contains(double px, double pz) {
            return px >= minX && px <= maxX && pz >= minZ && pz <= maxZ;
        }

        double cz() {
            return (minZ + maxZ) / 2;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + "," + z + (climb ? " climb" : " top " + top) + ")";
        }
    }

    /** One platform the player must land on, or the goal box. */
    public static final class Waypoint {
        /** The route's surfaces on this platform, in route order. Empty for the goal. */
        public final List<Surface> cells;
        /** First index of this waypoint's cells in {@link #chain}. */
        final int firstChainIndex;
        public final double top;
        final boolean goal;
        /** The route reaches this waypoint with a jump (or a drop), not by walking. */
        public final boolean needsJump;
        /** The route reaches this waypoint through ladder or vine blocks, holding on in them. */
        public final boolean viaClimb;

        Waypoint(List<Surface> cells, int firstChainIndex, double top, boolean goal, boolean needsJump) {
            this(cells, firstChainIndex, top, goal, needsJump, false);
        }

        Waypoint(List<Surface> cells, int firstChainIndex, double top, boolean goal, boolean needsJump, boolean viaClimb) {
            this.needsJump = needsJump;
            this.viaClimb = viaClimb;
            this.cells = cells;
            this.firstChainIndex = firstChainIndex;
            this.top = top;
            this.goal = goal;
        }

        @Override
        public String toString() {
            return goal ? "goal" : cells.get(0) + (cells.size() > 1 ? "+" + (cells.size() - 1) : "");
        }
    }

    private final Problem problem;
    private final Map<Long, List<Surface>> allByColumn = new HashMap<Long, List<Surface>>();
    public final List<Waypoint> waypoints = new ArrayList<Waypoint>();
    /** The route's surfaces from the start, then a pseudo-cell for the goal. */
    final List<Surface> chain = new ArrayList<Surface>();
    /** Taut-string points, one per chain cell. */
    private double[] px, pz;
    /** Remaining string length from each chain point to the goal. */
    private double[] suffix;
    /** Lowest top among the start platform and the waypoints, for pruning falls. */
    public final double floorY;
    public final double startTop;
    /** The stage on reaching the start: past this stage without a start entry, the run missed the start. */
    public final int startStage;

    /** Jumps must clear their gap with this much reach to spare; larger is safer, smaller finds shortcuts. */
    public final double slack;

    public Route(Problem problem) {
        this(problem, 0);
    }

    /** Jumps and walks may pass through things at body height on the way (the checks are straight-line guesses). */
    public final boolean throughWalls;
    /** Surfaces next to walls keep the body clear of them (see {@link #surfaces(SparseBlockWorld, boolean)}). */
    public final boolean clearOfWalls;

    public Route(Problem problem, double slack) {
        this(problem, slack, false);
    }

    public Route(Problem problem, double slack, boolean throughWalls) {
        this(problem, slack, throughWalls, false);
    }

    public Route(Problem problem, double slack, boolean throughWalls, boolean clearOfWalls) {
        this(problem, JumpTable.get(), slack, throughWalls, clearOfWalls);
    }

    Route(Problem problem, JumpTable jumps, double slack) {
        this(problem, jumps, slack, false, false);
    }

    Route(Problem problem, JumpTable jumps, double slack, boolean throughWalls, boolean clearOfWalls) {
        this.problem = problem;
        this.slack = slack;
        this.throughWalls = throughWalls;
        this.clearOfWalls = clearOfWalls;
        List<Surface> surfaces = surfaces(problem.world, clearOfWalls);
        for (Surface c : surfaces) allByColumn.computeIfAbsent(col(c.x, c.z), k -> new ArrayList<Surface>()).add(c);
        surfaces.addAll(climbCells(problem.world));
        Region g = problem.goal;
        Surface goalCell = new Surface(Integer.MIN_VALUE, 0, 0, g.minY, g.minX + 0.3, g.minZ + 0.3, g.maxX - 0.3, g.maxZ - 0.3);
        // Index surfaces by block column for neighbour lookups.
        Map<Long, List<Integer>> byColumn = new HashMap<Long, List<Integer>>();
        for (int i = 0; i < surfaces.size(); i++) {
            Surface c = surfaces.get(i);
            byColumn.computeIfAbsent(col(c.x, c.z), k -> new ArrayList<Integer>()).add(i);
        }
        Region s = problem.start;
        List<Integer> starts = new ArrayList<Integer>();
        for (int i = 0; i < surfaces.size(); i++) {
            Surface c = surfaces.get(i);
            if (!c.climb && c.top >= s.minY && c.top <= s.maxY && c.maxX >= s.minX && c.minX <= s.maxX && c.maxZ >= s.minZ && c.minZ <= s.maxZ) {
                starts.add(i);
            }
        }
        List<Surface> path = shortest(surfaces, byColumn, starts, -1, jumps);
        // The run-up from the spawn to the start, so the aim follows the ground there too.
        List<Surface> runUp = new ArrayList<Surface>();
        Surface spawnCell = support(problem.spawn.pos.x, problem.spawn.pos.z, problem.spawn.pos.y - 1e-6, problem.spawn.pos.y + 1e-6);
        if (!path.isEmpty() && spawnCell != null && !starts.contains(surfaces.indexOf(spawnCell))) {
            List<Surface> pre = shortest(surfaces, byColumn, Collections.singletonList(surfaces.indexOf(spawnCell)),
                surfaces.indexOf(path.get(0)), jumps);
            if (!pre.isEmpty()) runUp.addAll(pre.subList(0, pre.size() - 1));
        }
        // Waypoints come from the run-up too: the start box may hold a platform the run-up jumps onto.
        List<Surface> full = new ArrayList<Surface>(runUp);
        full.addAll(path);
        chain.addAll(full);
        // Platforms: walk-connected runs at the same height.
        double low = g.minY;
        double startTopV = full.isEmpty() ? problem.spawn.pos.y : full.get(0).top;
        int i = 1;
        boolean climbed = false;
        while (i < full.size()) {
            Surface first = full.get(i);
            if (first.climb) {
                // Held on through; the next platform is reached this way.
                climbed = true;
                i++;
                continue;
            }
            List<Surface> cells = new ArrayList<Surface>();
            cells.add(first);
            int j = i + 1;
            while (j < full.size() && walkable(full.get(j - 1), full.get(j)) && Math.abs(full.get(j).top - first.top) < EPS) {
                cells.add(full.get(j));
                j++;
            }
            boolean sameAsPrev = !climbed && walkable(full.get(i - 1), first) && Math.abs(full.get(i - 1).top - first.top) < EPS;
            if (!sameAsPrev) {
                waypoints.add(new Waypoint(cells, i, first.top, false, climbed || !runnable(full.get(i - 1), first), climbed));
                low = Math.min(low, first.top);
            }
            climbed = false;
            i = j;
        }
        int before = 0;
        for (Waypoint w : waypoints) if (w.firstChainIndex <= runUp.size()) before++;
        this.startStage = before;
        chain.add(goalCell);
        boolean goalJump = false;
        if (!full.isEmpty()) {
            Surface last = full.get(full.size() - 1);
            double gx = Math.max(0, Math.max(g.minX - last.maxX, last.minX - g.maxX));
            double gz = Math.max(0, Math.max(g.minZ - last.maxZ, last.minZ - g.maxZ));
            goalJump = !(Math.hypot(gx, gz) <= 0.45 && g.minY <= last.top + 0.6 && g.maxY >= last.top - 0.6);
        }
        waypoints.add(new Waypoint(Collections.<Surface>emptyList(), chain.size() - 1, g.minY, true, goalJump));
        this.startTop = startTopV;
        this.floorY = Math.min(low, startTopV);
        string();
    }

    /**
     * Dijkstra over the surfaces from the sources to {@code target}, or to the goal box when it is -1.
     * Returns the surfaces on the way, sources first, or an empty list when there is no way.
     */
    private List<Surface> shortest(List<Surface> surfaces, Map<Long, List<Integer>> byColumn, List<Integer> sources,
                                   int target, JumpTable jumps) {
        int n = surfaces.size();
        int goalId = n;
        double[] dist = new double[n + 1];
        int[] prev = new int[n + 1];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> pq = new PriorityQueue<double[]>(16, (a, b) -> Double.compare(a[0], b[0]));
        for (int i : sources) {
            dist[i] = 0;
            pq.add(new double[] {0, i});
        }
        int end = target < 0 ? goalId : target;
        int reach = (int) Math.ceil(jumps.maxReach()) + 2;
        while (!pq.isEmpty()) {
            double[] e = pq.poll();
            int u = (int) e[1];
            if (e[0] > dist[u]) continue;
            if (u == end) break;
            Surface a = surfaces.get(u);
            if (target < 0) {
                double ga = goalCost(a, jumps);
                if (dist[u] + ga < dist[goalId]) {
                    dist[goalId] = dist[u] + ga;
                    prev[goalId] = u;
                    pq.add(new double[] {dist[goalId], goalId});
                }
            }
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    List<Integer> list = byColumn.get(col(a.x + dx, a.z + dz));
                    if (list == null) continue;
                    for (int v : list) {
                        if (v == u) continue;
                        double c = edgeCost(a, surfaces.get(v), jumps);
                        if (c < 0) continue;
                        if (dist[u] + c < dist[v]) {
                            dist[v] = dist[u] + c;
                            prev[v] = u;
                            pq.add(new double[] {dist[v], v});
                        }
                    }
                }
            }
        }
        List<Surface> path = new ArrayList<Surface>();
        if (dist[end] == Double.POSITIVE_INFINITY) {
            if (System.getenv("ROUTE_DEBUG") != null) {
                for (int i = 0; i < n; i++) {
                    Surface c = surfaces.get(i);
                    System.err.println((dist[i] < Double.POSITIVE_INFINITY ? "R " : "- ") + c + " fp " + c.minX + ".." + c.maxX + " / " + c.minZ + ".." + c.maxZ);
                }
            }
            return path;
        }
        if (target >= 0) path.add(surfaces.get(target));
        for (int v = prev[end]; v >= 0; v = prev[v]) path.add(surfaces.get(v));
        Collections.reverse(path);
        return path;
    }

    /**
     * The point to run towards on the way to waypoint {@code stage}: the string point two cells
     * ahead of the route cell under (or nearest to) the player, but never past the waypoint. This
     * keeps a run on the route's ground instead of cutting a corner off a narrow platform.
     */
    public void aimAlong(double x, double z, int stage, double[] aim) {
        int target = waypoints.get(stage).firstChainIndex;
        int lo = stage == 0 ? 0 : waypoints.get(stage - 1).firstChainIndex;
        int k = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int i = lo; i < target; i++) {
            double d = chain.get(i).dist(x, z);
            if (d < best - 1e-9 || (d <= 1e-9 && best <= 1e-9)) {
                best = d;
                k = i;
            }
        }
        int a = k < 0 ? target : Math.min(k + 2, target);
        aim[0] = px[a];
        aim[1] = pz[a];
    }

    public boolean found() {
        return chain.size() > 1;
    }

    private static long col(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    static boolean walkable(Surface a, Surface b) {
        if (a.climb || b.climb) return false;
        double dy = b.top - a.top;
        return rectGap(a, b) < EPS && dy <= 0.6 + EPS;
    }

    /**
     * Walkable, or a gap narrow enough to run across: the box is only one tick without support and
     * steps up onto the far side (02-gap-1 is crossed this way in 4 ticks).
     */
    static boolean runnable(Surface a, Surface b) {
        if (a.climb || b.climb) return false;
        return rectGap(a, b) <= 0.45 && b.top - a.top <= 0.6 + EPS;
    }

    static double rectGap(Surface a, Surface b) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double centreDist(Surface a, Surface b) {
        double dx = a.cx() - b.cx(), dz = a.cz() - b.cz();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Ticks to go from surface a to surface b, or -1 when the edge does not exist. */
    private double edgeCost(Surface a, Surface b, JumpTable jumps) {
        if (a.climb || b.climb) return climbCost(a, b, jumps);
        double dy = b.top - a.top;
        double d = centreDist(a, b);
        if (walkable(a, b)) return !throughWalls && wallBetween(a, b) ? -1 : d / RUN_SPEED;
        double gap = rectGap(a, b);
        if (dy > jumps.maxRise() || gap > jumps.reach(dy) - slack) return -1;
        if (!throughWalls && obstructed(a, b)) return -1;
        // A jump costs at least its airtime; a short hop between close surfaces still takes it.
        return Math.max(d / RUN_SPEED, jumps.airTicks(dy)) + 1;
    }

    /** Holding on in ladders and vines, a sprint moves about this far a tick (it is capped at 0.15). */
    static final double CLIMB_SPEED = 0.12;

    /**
     * Edges into, through and out of ladder and vine blocks. Inside them the fall is slow and
     * sneaking stops it, so the player can move sideways from block to block at the same height or
     * sink to the one below; climbing up needs a wall to push against and is left out. Into one: a
     * jump or walk that reaches the block at some height of its span. Out of one: onto a block top
     * next to it no higher than the block's top.
     */
    private double climbCost(Surface a, Surface b, JumpTable jumps) {
        double d = centreDist(a, b);
        if (a.climb && b.climb) {
            if (rectGap(a, b) > EPS || (b.y != a.y && b.y != a.y - 1)) return -1;
            if (a.x == b.x && a.z == b.z && b.y != a.y - 1) return -1;
            return Math.max(d, 1.0 / 8) / CLIMB_SPEED;
        }
        if (a.climb) {
            // Step out onto a top no higher than the hold reaches, or drop onto one below.
            if (b.top > a.top + 1.0 + EPS || b.top < a.top - 3.0 || rectGap(a, b) > EPS) return -1;
            return d / CLIMB_SPEED;
        }
        // Into the block, entering at the height of the span nearest the take-off top.
        double feet = Math.max(b.top, Math.min(a.top, b.top + 0.99));
        double dy = feet - a.top;
        double gap = rectGap(a, b);
        if (gap < EPS && dy <= 0.6 + EPS) return d / RUN_SPEED;
        if (dy > jumps.maxRise() || gap > jumps.reach(dy) - slack) return -1;
        return Math.max(d / RUN_SPEED, jumps.airTicks(dy)) + 1;
    }

    /** Ladder and vine blocks. */
    static List<Surface> climbCells(SparseBlockWorld world) {
        final List<Surface> out = new ArrayList<Surface>();
        world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                if (isClimbable(type)) out.add(Surface.climbCell(x, y, z));
            }
        });
        return out;
    }

    static boolean isClimbable(BlockType type) {
        String n = type.name();
        return n.startsWith("minecraft:ladder") || n.startsWith("minecraft:vine");
    }

    /** The player's feet are in a ladder or vine block (vanilla's {@code isOnLadder}, without trapdoors). */
    boolean holding(double x, double y, double z) {
        return isClimbable(problem.world.getBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    /**
     * Something in the way of the body on the straight line between two surfaces' centres: a wall
     * or a step that the jump would run into. The route then has to go over it as a surface. Over
     * the gap the feet follow a jump arc from one top to the other, so a low block that the jump
     * clears does not count, and neither does a block that a line 0.4 to either side passes.
     */
    static final double PROBE = 0.1;

    private boolean obstructed(Surface a, Surface b) {
        double dx = b.cx() - a.cx(), dz = b.cz() - a.cz(), n = Math.hypot(dx, dz);
        if (n < EPS) return false;
        for (double off : new double[] {0, -0.4, 0.4}) {
            double ox = -dz / n * off, oz = dx / n * off;
            if (!blockedLine(a, b, a.cx() + ox, a.cz() + oz, b.cx() + ox, b.cz() + oz)) return false;
        }
        return true;
    }

    private boolean blockedLine(Surface a, Surface b, double ax, double az, double bx, double bz) {
        double len = Math.hypot(bx - ax, bz - az);
        int steps = Math.max(1, (int) Math.ceil(len / 0.25));
        int leave = 0, enter = steps;
        while (leave + 1 < steps && a.contains(ax + (bx - ax) * (leave + 1) / steps, az + (bz - az) * (leave + 1) / steps)) leave++;
        while (enter - 1 > leave && b.contains(ax + (bx - ax) * (enter - 1) / steps, az + (bz - az) * (enter - 1) / steps)) enter--;
        double[] arc = jumpArc(a.top, b.top);
        List<Aabb> hit = new ArrayList<Aabb>();
        for (int i = leave + 1; i < enter; i++) {
            double px = ax + (bx - ax) * i / steps, pz = az + (bz - az) * i / steps;
            double t = (arc.length - 1) * (i - leave) / (double) (enter - leave);
            int k = (int) t;
            double feet = k + 1 < arc.length ? arc[k] + (arc[k + 1] - arc[k]) * (t - k) : arc[k];
            Aabb probe = new Aabb(px - PROBE, feet + 0.1, pz - PROBE, px + PROBE, feet + 1.7, pz + PROBE);
            int bxI = (int) Math.floor(px), bzI = (int) Math.floor(pz);
            for (int yy = (int) Math.floor(feet + 0.1) - 1; yy <= (int) Math.floor(feet + 1.7); yy++) {
                problem.world.getBlock(bxI, yy, bzI).addCollisionBoxes(problem.world, bxI, yy, bzI, probe, hit);
            }
            if (!hit.isEmpty()) return true;
        }
        return false;
    }

    /**
     * A wall between two neighbouring surfaces that the player walks across: something solid
     * above step height on the line between their centres, and on the lines 0.4 to either side.
     */
    private boolean wallBetween(Surface a, Surface b) {
        double ax = a.cx(), az = a.cz(), bx = b.cx(), bz = b.cz();
        double dx = bx - ax, dz = bz - az, n = Math.hypot(dx, dz);
        if (n < EPS) return false;
        double y0 = Math.max(a.top, b.top) + 0.6, y1 = Math.max(a.top, b.top) + 1.7;
        int steps = Math.max(1, (int) Math.ceil(n / 0.25));
        for (double off : new double[] {0, -0.4, 0.4}) {
            double ox = -dz / n * off, oz = dx / n * off;
            boolean blocked = false;
            for (int i = 1; i < steps && !blocked; i++) {
                double px = ax + ox + dx * i / steps, pz = az + oz + dz * i / steps;
                blocked = solidAt(px, y0, pz, y1, PROBE);
            }
            if (!blocked) return false;
        }
        return true;
    }

    /** Feet height per tick of a jump from {@code from} until it comes down to {@code to}. */
    private static double[] jumpArc(double from, double to) {
        List<Double> ys = new ArrayList<Double>();
        double y = from, vy = 0.42;
        ys.add(y);
        while (ys.size() < 60) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            if (vy < 0 && y <= to) break;
            ys.add(y);
        }
        ys.add(Math.max(to, y));
        double[] out = new double[ys.size()];
        for (int i = 0; i < out.length; i++) out[i] = ys.get(i);
        return out;
    }

    private double goalCost(Surface a, JumpTable jumps) {
        Region g = problem.goal;
        double gx = Math.max(0, Math.max(g.minX - a.maxX, a.minX - g.maxX));
        double gz = Math.max(0, Math.max(g.minZ - a.maxZ, a.minZ - g.maxZ));
        double gap = Math.sqrt(gx * gx + gz * gz);
        double dy = Math.max(g.minY - a.top, Math.min(0, g.maxY - a.top));
        double cx = Math.max(g.minX, Math.min(g.maxX, a.cx())), cz = Math.max(g.minZ, Math.min(g.maxZ, a.cz()));
        double d = Math.hypot(cx - a.cx(), cz - a.cz());
        if (gap < EPS && g.minY <= a.top + 0.6 && g.maxY >= a.top) return d / RUN_SPEED;
        if (dy > jumps.maxRise() || gap > jumps.reach(dy) - slack) return Double.POSITIVE_INFINITY;
        return Math.max(d / RUN_SPEED, jumps.airTicks(dy)) + 1;
    }

    /** Relaxes one point per chain cell into a taut string from the first cell to the goal. */
    private void string() {
        int m = chain.size();
        px = new double[m];
        pz = new double[m];
        for (int k = 0; k < m; k++) {
            px[k] = chain.get(k).cx();
            pz[k] = chain.get(k).cz();
        }
        for (int it = 0; it < 200; it++) {
            for (int k = 1; k < m; k++) {
                double tx, tz;
                if (k == m - 1) {
                    tx = px[k - 1];
                    tz = pz[k - 1];
                } else {
                    double[] q = closestOnSegment(px[k - 1], pz[k - 1], px[k + 1], pz[k + 1], chain.get(k));
                    tx = q[0];
                    tz = q[1];
                }
                // Keep the string a little inside each footprint so that a run along it stays on the ground.
                Surface c = chain.get(k);
                double ix = Math.min(STRING_INSET, (c.maxX - c.minX) / 2), iz = Math.min(STRING_INSET, (c.maxZ - c.minZ) / 2);
                px[k] = clamp(tx, c.minX + ix, c.maxX - ix);
                pz[k] = clamp(tz, c.minZ + iz, c.maxZ - iz);
            }
        }
        suffix = new double[m];
        for (int k = m - 2; k >= 0; k--) suffix[k] = suffix[k + 1] + Math.hypot(px[k + 1] - px[k], pz[k + 1] - pz[k]);
    }

    /** The point of segment (x1,z1)-(x2,z2) nearest to the rectangle, by sampling. */
    private static double[] closestOnSegment(double x1, double z1, double x2, double z2, Surface r) {
        double best = Double.POSITIVE_INFINITY, bx = x1, bz = z1;
        for (int i = 0; i <= 32; i++) {
            double f = i / 32.0;
            double x = x1 + (x2 - x1) * f, z = z1 + (z2 - z1) * f;
            double d = r.dist(x, z);
            if (d < best - 1e-12) {
                best = d;
                bx = x;
                bz = z;
            }
        }
        return new double[] {bx, bz};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /**
     * Remaining horizontal distance from (x, z) to the goal when the next waypoint is
     * {@code stage}; {@code aim} receives the point to head for.
     */
    public double remaining(double x, double z, int stage, double[] aim) {
        Waypoint w = waypoints.get(stage);
        if (w.goal) {
            Region g = problem.goal;
            double ax = clamp(x, g.minX, g.maxX), az = clamp(z, g.minZ, g.maxZ);
            if (aim != null) {
                aim[0] = ax;
                aim[1] = az;
            }
            return Math.hypot(ax - x, az - z);
        }
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < w.cells.size(); i++) {
            int k = w.firstChainIndex + i;
            Surface c = w.cells.get(i);
            double qx = clamp(x, c.minX, c.maxX), qz = clamp(z, c.minZ, c.maxZ);
            // Head for the string's next point through this cell when it lies beyond the cell.
            double nx = px[k + 1], nz = pz[k + 1];
            double[] q = closestOnSegment(x, z, nx, nz, c);
            if (c.dist(q[0], q[1]) < 1e-9) {
                qx = q[0];
                qz = q[1];
            }
            double d = Math.hypot(qx - x, qz - z) + Math.hypot(nx - qx, nz - qz) + suffix[k + 1];
            if (d < best) {
                best = d;
                if (aim != null) {
                    aim[0] = qx;
                    aim[1] = qz;
                }
            }
        }
        return best;
    }

    /** Horizontal distance from (x, z) to the nearest place that counts as landing on waypoint {@code stage}; 0 inside. */
    public double missDistance(int stage, double x, double z) {
        Waypoint w = waypoints.get(stage);
        if (w.goal) {
            Region g = problem.goal;
            double dx = Math.max(0, Math.max(g.minX - x, x - g.maxX));
            double dz = Math.max(0, Math.max(g.minZ - z, z - g.maxZ));
            return Math.sqrt(dx * dx + dz * dz);
        }
        double best = Double.POSITIVE_INFINITY;
        for (Surface c : w.cells) best = Math.min(best, c.dist(x, z));
        return best;
    }

    /**
     * The highest surface whose top lies in [lowY, highY] and that supports a player centred at
     * (x, z), or null.
     */
    public Surface support(double x, double z, double lowY, double highY) {
        Surface best = null;
        int x0 = (int) Math.floor(x - 0.3), x1 = (int) Math.floor(x + 0.3);
        int z0 = (int) Math.floor(z - 0.3), z1 = (int) Math.floor(z + 0.3);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                List<Surface> list = allByColumn.get(col(bx, bz));
                if (list == null) continue;
                for (Surface c : list) {
                    if (c.top < lowY || c.top > highY) continue;
                    if (x < c.minX || x > c.maxX || z < c.minZ || z > c.maxZ) continue;
                    if (best == null || c.top > best.top) best = c;
                }
            }
        }
        return best;
    }

    /** The point of waypoint {@code stage}, shrunk by {@code inset} on each side, nearest to (x, z). */
    public void nearestInside(int stage, double x, double z, double inset, double[] out) {
        Waypoint w = waypoints.get(stage);
        if (w.goal) {
            Region g = problem.goal;
            double ix = Math.min(inset, (g.maxX - g.minX) / 2), iz = Math.min(inset, (g.maxZ - g.minZ) / 2);
            out[0] = clamp(x, g.minX + ix, g.maxX - ix);
            out[1] = clamp(z, g.minZ + iz, g.maxZ - iz);
            return;
        }
        double best = Double.POSITIVE_INFINITY;
        for (Surface c : w.cells) {
            double ix = Math.min(inset, (c.maxX - c.minX) / 2), iz = Math.min(inset, (c.maxZ - c.minZ) / 2);
            double qx = clamp(x, c.minX + ix, c.maxX - ix), qz = clamp(z, c.minZ + iz, c.maxZ - iz);
            double d = Math.hypot(qx - x, qz - z);
            if (d < best) {
                best = d;
                out[0] = qx;
                out[1] = qz;
            }
        }
    }

    /** True when a player standing at this position has landed on waypoint {@code stage}. */
    /**
     * The stage after standing at (x, y, z): one past the furthest of the next few waypoints the
     * player is standing on (a jump may skip a waypoint), or {@code stage} when on none of them.
     */
    public int after(int stage, double x, double y, double z, boolean onGround) {
        if (!onGround) return stage;
        for (int k = Math.min(stage + 3, waypoints.size() - 2); k >= stage; k--) {
            if (landed(k, x, y, z, true)) return k + 1;
        }
        return stage;
    }

    public boolean landed(int stage, double x, double y, double z, boolean onGround) {
        Waypoint w = waypoints.get(stage);
        if (w.goal) return false;
        if (!onGround || Math.abs(y - w.top) > 1e-7) return false;
        for (Surface c : w.cells) {
            if (x >= c.minX && x <= c.maxX && z >= c.minZ && z <= c.maxZ) return true;
        }
        return false;
    }

    /** Standable surfaces: the top of each block's highest collision boxes with 1.8 blocks of room above. */
    static List<Surface> surfaces(SparseBlockWorld world) {
        return surfaces(world, false);
    }

    /**
     * Standable surfaces; with {@code clearOfWalls}, a wall beside one above step height keeps the
     * centre 0.3 inside that edge instead of 0.3 outside it, so the route does not run the body into
     * a trunk or a post (but stays off the edges that a run along a wall could use).
     */
    static List<Surface> surfaces(SparseBlockWorld world, final boolean clearOfWalls) {
        final List<Surface> out = new ArrayList<Surface>();
        final Aabb everything = new Aabb(-3.0E7, -64, -3.0E7, 3.0E7, 512, 3.0E7);
        world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                List<Aabb> boxes = new ArrayList<Aabb>();
                type.addCollisionBoxes(world, x, y, z, everything, boxes);
                if (boxes.isEmpty()) return;
                double top = Double.NEGATIVE_INFINITY;
                for (Aabb b : boxes) top = Math.max(top, b.maxY);
                double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
                for (Aabb b : boxes) {
                    if (b.maxY < top - 1e-9) continue;
                    minX = Math.min(minX, b.minX);
                    minZ = Math.min(minZ, b.minZ);
                    maxX = Math.max(maxX, b.maxX);
                    maxZ = Math.max(maxZ, b.maxZ);
                }
                // Room for the player somewhere on the footprint: at its centre, a corner or an
                // edge (a fence under an open trapdoor has room only on one side).
                boolean room = false;
                for (int i = 0; i < 9 && !room; i++) {
                    double px = i % 3 == 0 ? minX : i % 3 == 1 ? (minX + maxX) / 2 : maxX;
                    double pz = i / 3 == 0 ? minZ : i / 3 == 1 ? (minZ + maxZ) / 2 : maxZ;
                    room = hasRoom(world, x, y, z, px, top, pz);
                }
                if (!room) return;
                if (clearOfWalls) {
                    double lo = top + 0.6, hi = top + 1.8;
                    if (blocks(world, minX - 0.3, lo, minZ, minX, hi, maxZ)) minX += 0.6;
                    if (blocks(world, maxX, lo, minZ, maxX + 0.3, hi, maxZ)) maxX -= 0.6;
                    if (blocks(world, minX, lo, minZ - 0.3, maxX, hi, minZ)) minZ += 0.6;
                    if (blocks(world, minX, lo, maxZ, maxX, hi, maxZ + 0.3)) maxZ -= 0.6;
                    if (minX > maxX + 0.6 - 1e-9 || minZ > maxZ + 0.6 - 1e-9) return;
                }
                out.add(new Surface(x, y, z, top, minX, minZ, maxX, maxZ));
            }
        });
        return out;
    }

    /** Something solid in a box of half width {@code half} around (x, z) between heights y0 and y1. */
    boolean solidAt(double x, double y0, double z, double y1, double half) {
        Aabb box = new Aabb(x - half, y0, z - half, x + half, y1, z + half);
        List<Aabb> hit = new ArrayList<Aabb>();
        for (int yy = (int) Math.floor(y0) - 1; yy <= (int) Math.floor(y1); yy++) {
            for (int xx = (int) Math.floor(x - half); xx <= (int) Math.floor(x + half); xx++) {
                for (int zz = (int) Math.floor(z - half); zz <= (int) Math.floor(z + half); zz++) {
                    problem.world.getBlock(xx, yy, zz).addCollisionBoxes(problem.world, xx, yy, zz, box, hit);
                }
            }
        }
        return !hit.isEmpty();
    }

    /** Any collision box in the given box (shrunk a hair so touching faces do not count). */
    private static boolean blocks(SparseBlockWorld world, double x0, double y0, double z0, double x1, double y1, double z1) {
        Aabb box = new Aabb(x0 + 1e-4, y0, z0 + 1e-4, x1 - 1e-4, y1, z1 - 1e-4);
        List<Aabb> hit = new ArrayList<Aabb>();
        for (int yy = (int) Math.floor(y0) - 1; yy <= (int) Math.floor(y1); yy++) {
            for (int xx = (int) Math.floor(x0); xx <= (int) Math.floor(x1); xx++) {
                for (int zz = (int) Math.floor(z0); zz <= (int) Math.floor(z1); zz++) {
                    world.getBlock(xx, yy, zz).addCollisionBoxes(world, xx, yy, zz, box, hit);
                }
            }
        }
        return !hit.isEmpty();
    }

    /** No collision box in the player's 0.6 x 1.8 box standing at (px, top, pz). */
    private static boolean hasRoom(SparseBlockWorld world, int x, int y, int z, double px, double top, double pz) {
        Aabb box = new Aabb(px - 0.299, top + 1e-6, pz - 0.299, px + 0.299, top + 1.8, pz + 0.299);
        List<Aabb> above = new ArrayList<Aabb>();
        for (int yy = y; yy <= y + 3; yy++) {
            for (int xx = x - 1; xx <= x + 1; xx++) {
                for (int zz = z - 1; zz <= z + 1; zz++) {
                    world.getBlock(xx, yy, zz).addCollisionBoxes(world, xx, yy, zz, box, above);
                }
            }
        }
        return above.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Waypoint w : waypoints) {
            if (sb.length() > 0) sb.append(" -> ");
            sb.append(w);
        }
        return sb.toString();
    }
}
