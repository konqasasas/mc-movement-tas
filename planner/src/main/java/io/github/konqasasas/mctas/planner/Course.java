package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.DenseBlockWorld;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The top level of the plan: the course as a graph of footholds.
 *
 * <p>Every block top the player can stand on is a {@link Surface}. Surfaces at the same height whose
 * footprints touch form one platform. A backward Dijkstra from the goal over optimistic moves (walk,
 * or a jump whose gap and rise the best sprint jump could cover, see {@link Reach}) gives each surface
 * a lower bound on the ticks still needed, {@link #toGo}; it is the A* heuristic and also tells the
 * transitions which footholds are progress.
 */
final class Course {
    static final double EPS = 1e-6;
    /** Extra reach allowed on the optimistic graph, so that it stays a relaxation. */
    static final double REACH_MARGIN = 0.8;
    /** How far off a surface's centre the player may stand without the bound from the centre overestimating. */
    static final double CENTRE_SLACK = 0.5;

    static final class Surface {
        final int id, x, y, z;
        final double top;
        /** Footprint grown by the player's half width: the centre is supported anywhere inside. */
        final double minX, minZ, maxX, maxZ;
        int platform;
        /** Lower bound on the ticks from anywhere on this surface to the goal. */
        double toGo = Double.POSITIVE_INFINITY;
        /** Surfaces one optimistic move away (both directions of the relaxation). */
        final List<Surface> next = new ArrayList<Surface>();
        /** The goal is one optimistic move away. */
        boolean nearGoal;

        Surface(int id, int x, int y, int z, double top, double minX, double minZ, double maxX, double maxZ) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.top = top;
            this.minX = minX - 0.3;
            this.minZ = minZ - 0.3;
            this.maxX = maxX + 0.3;
            this.maxZ = maxZ + 0.3;
        }

        double dist(double px, double pz) {
            double dx = Math.max(0, Math.max(minX - px, px - maxX));
            double dz = Math.max(0, Math.max(minZ - pz, pz - maxZ));
            return Math.sqrt(dx * dx + dz * dz);
        }

        boolean contains(double px, double pz) {
            return px >= minX && px <= maxX && pz >= minZ && pz <= maxZ;
        }

        double cx() {
            return (minX + maxX) / 2;
        }

        double cz() {
            return (minZ + maxZ) / 2;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + "," + z + ")";
        }
    }

    final Problem problem;
    final BlockWorld world;
    final List<Surface> surfaces;
    final Reach reach;
    private final Map<Long, List<Surface>> byColumn = new HashMap<Long, List<Surface>>();
    /** Surfaces of each platform. */
    final List<List<Surface>> platforms = new ArrayList<List<Surface>>();

    Course(Problem problem) {
        this.problem = problem;
        this.world = DenseBlockWorld.of(problem.world, 4);
        this.reach = Reach.get();
        this.surfaces = findSurfaces(problem.world);
        for (Surface c : surfaces) byColumn.computeIfAbsent(col(c.x, c.z), k -> new ArrayList<Surface>()).add(c);
        groupPlatforms();
        link();
        costToGo();
    }

    static long col(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    List<Surface> column(int x, int z) {
        return byColumn.get(col(x, z));
    }

    /** The highest surface at height {@code y} (within 1e-6) that supports a player centred at (x, z), or null. */
    Surface support(double x, double y, double z) {
        Surface best = null;
        int x0 = (int) Math.floor(x - 0.3), x1 = (int) Math.floor(x + 0.3);
        int z0 = (int) Math.floor(z - 0.3), z1 = (int) Math.floor(z + 0.3);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                List<Surface> list = column(bx, bz);
                if (list == null) continue;
                for (Surface c : list) {
                    if (Math.abs(c.top - y) > 1e-6 || !c.contains(x, z)) continue;
                    if (best == null || c.toGo < best.toGo) best = c;
                }
            }
        }
        return best;
    }

    /** The platform the player stands on, or -1 in the air or on no known surface. */
    int platformAt(PlayerState s) {
        if (!s.onGround) return -1;
        Surface c = support(s.posX, s.posY, s.posZ);
        return c == null ? -1 : c.platform;
    }

    /** True when the player gets hurt in this state (D-005): standing on magma, touching cactus, fire or lava. */
    boolean hurts(PlayerState s) {
        if (s.onGround && !s.isSneaking()) {
            int x = (int) Math.floor(s.posX), y = (int) Math.floor(s.posY - 0.2), z = (int) Math.floor(s.posZ);
            if (isHazard(world.getBlock(x, y, z), true)) return true;
        }
        Aabb b = s.box;
        int x0 = (int) Math.floor(b.minX + 0.001), x1 = (int) Math.floor(b.maxX - 0.001);
        int y0 = (int) Math.floor(b.minY + 0.001), y1 = (int) Math.floor(b.maxY - 0.001);
        int z0 = (int) Math.floor(b.minZ + 0.001), z1 = (int) Math.floor(b.maxZ - 0.001);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (isHazard(world.getBlock(x, y, z), false)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isHazard(BlockType t, boolean walk) {
        String n = t.name();
        if (walk) return n.equals("minecraft:magma");
        return n.equals("minecraft:cactus") || n.equals("minecraft:fire") || n.equals("minecraft:lava")
            || n.equals("minecraft:flowing_lava");
    }

    private List<Surface> findSurfaces(final SparseBlockWorld sparse) {
        final List<Surface> out = new ArrayList<Surface>();
        final Aabb everything = new Aabb(-3.0E7, -64, -3.0E7, 3.0E7, 512, 3.0E7);
        sparse.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                if (isHazard(type, true)) return;
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
                // Room for the player somewhere on the footprint (a corner or an edge is enough).
                boolean room = false;
                for (int i = 0; i < 9 && !room; i++) {
                    double px = i % 3 == 0 ? minX : i % 3 == 1 ? (minX + maxX) / 2 : maxX;
                    double pz = i / 3 == 0 ? minZ : i / 3 == 1 ? (minZ + maxZ) / 2 : maxZ;
                    room = hasRoom(x, y, z, px, top, pz);
                }
                if (!room) return;
                out.add(new Surface(out.size(), x, y, z, top, minX, minZ, maxX, maxZ));
            }
        });
        return out;
    }

    /** No collision box in a standing (1.8) player box standing at (px, top, pz). */
    private boolean hasRoom(int x, int y, int z, double px, double top, double pz) {
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

    static double rectGap(Surface a, Surface b) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void groupPlatforms() {
        int n = surfaces.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (Surface a : surfaces) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Surface> list = column(a.x + dx, a.z + dz);
                    if (list == null) continue;
                    for (Surface b : list) {
                        if (b.id <= a.id || Math.abs(a.top - b.top) > EPS || rectGap(a, b) > EPS) continue;
                        int ra = find(parent, a.id), rb = find(parent, b.id);
                        if (ra != rb) parent[ra] = rb;
                    }
                }
            }
        }
        Map<Integer, Integer> ids = new HashMap<Integer, Integer>();
        for (Surface a : surfaces) {
            int r = find(parent, a.id);
            Integer p = ids.get(r);
            if (p == null) {
                p = platforms.size();
                ids.put(r, p);
                platforms.add(new ArrayList<Surface>());
            }
            a.platform = p;
            platforms.get(p).add(a);
        }
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** True when an optimistic move goes from a to b. */
    private boolean move(Surface a, Surface b) {
        double dy = b.top - a.top;
        double gap = rectGap(a, b);
        if (gap < EPS && dy <= 0.6 + EPS) return true;
        if (dy > reach.maxRise() + EPS) return false;
        return gap <= reach.reach(dy) + REACH_MARGIN;
    }

    private void link() {
        int r = (int) Math.ceil(reach.reach(-8) + REACH_MARGIN) + 1;
        for (Surface a : surfaces) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    List<Surface> list = column(a.x + dx, a.z + dz);
                    if (list == null) continue;
                    for (Surface b : list) if (b != a && move(a, b)) a.next.add(b);
                }
            }
            a.nearGoal = goalGap(a) >= 0;
        }
    }

    /** Horizontal gap from the surface to the goal box when the goal is one optimistic move away, else -1. */
    double goalGap(Surface a) {
        Region g = problem.goal;
        double dx = Math.max(0, Math.max(g.minX - a.maxX, a.minX - g.maxX));
        double dz = Math.max(0, Math.max(g.minZ - a.maxZ, a.minZ - g.maxZ));
        double gap = Math.sqrt(dx * dx + dz * dz);
        // The best height inside the goal box to aim for: as low as the box allows, but not below the surface.
        double dy = Math.max(g.minY - a.top, Math.min(0, g.maxY - a.top));
        if (dy > reach.maxRise() + EPS) return -1;
        if (gap < EPS) return 0;
        return gap <= reach.reach(dy) + REACH_MARGIN ? gap : -1;
    }

    /** Horizontal distance from (x, z) to the goal box. */
    double goalDist(double x, double z) {
        Region g = problem.goal;
        double dx = Math.max(0, Math.max(g.minX - x, x - g.maxX));
        double dz = Math.max(0, Math.max(g.minZ - z, z - g.maxZ));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Ticks from each surface's centre to the goal, travelling between centres at the top speed along
     * optimistic moves: a lower bound up to the slack of starting off-centre.
     */
    private void costToGo() {
        double v = reach.maxSpeed();
        PriorityQueue<double[]> pq = new PriorityQueue<double[]>(16, (p, q) -> Double.compare(p[0], q[0]));
        // Reverse edges.
        List<List<Surface>> into = new ArrayList<List<Surface>>();
        for (int i = 0; i < surfaces.size(); i++) into.add(new ArrayList<Surface>());
        for (Surface a : surfaces) for (Surface b : a.next) into.get(b.id).add(a);
        double[] dist = new double[surfaces.size()];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        for (Surface a : surfaces) {
            if (a.nearGoal) {
                dist[a.id] = goalDist(a.cx(), a.cz()) / v;
                pq.add(new double[] {dist[a.id], a.id});
            }
        }
        while (!pq.isEmpty()) {
            double[] e = pq.poll();
            int u = (int) e[1];
            if (e[0] > dist[u]) continue;
            Surface b = surfaces.get(u);
            for (Surface a : into.get(u)) {
                double c = dist[u] + Math.hypot(a.cx() - b.cx(), a.cz() - b.cz()) / v;
                if (c < dist[a.id]) {
                    dist[a.id] = c;
                    pq.add(new double[] {c, a.id});
                }
            }
        }
        for (Surface a : surfaces) a.toGo = dist[a.id];
    }

    /**
     * Estimate of the ticks from a player at (x, y, z) to the goal: straight to the goal box, or across
     * to a nearby surface's centre and on from there, at the best speed.
     */
    double toGo(double x, double y, double z) {
        double v = reach.maxSpeed();
        Region g = problem.goal;
        double dx = Math.max(0, Math.max(g.minX - x, x - g.maxX));
        double dz = Math.max(0, Math.max(g.minZ - z, z - g.maxZ));
        double direct = Math.sqrt(dx * dx + dz * dz) / v;
        double best = Double.POSITIVE_INFINITY;
        if (y >= g.minY - reach.maxRise() && Math.sqrt(dx * dx + dz * dz) <= reach.reach(g.minY - y) + REACH_MARGIN) best = direct;
        int r = (int) Math.ceil(reach.reach(-8) + REACH_MARGIN) + 1;
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        for (int ix = -r; ix <= r; ix++) {
            for (int iz = -r; iz <= r; iz++) {
                List<Surface> list = column(cx + ix, cz + iz);
                if (list == null) continue;
                for (Surface b : list) {
                    if (b.toGo == Double.POSITIVE_INFINITY) continue;
                    double dy = b.top - y;
                    if (dy > reach.maxRise() + EPS) continue;
                    double d = b.dist(x, z);
                    if (d > reach.reach(dy) + REACH_MARGIN) continue;
                    best = Math.min(best, Math.max(d, Math.hypot(b.cx() - x, b.cz() - z) - CENTRE_SLACK) / v + b.toGo);
                }
            }
        }
        return Math.max(best, direct);
    }
}
