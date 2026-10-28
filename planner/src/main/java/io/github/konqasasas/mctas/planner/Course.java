package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.DenseBlockWorld;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

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
        /** Moves in {@link #next} that only go through ladders, vines or water ({@link ClimbManeuver}). */
        final List<Surface> viaBody = new ArrayList<Surface>();
        /** The body ({@link #bodies}) each move of {@link #viaBody} goes through. */
        final List<Integer> viaBodyId = new ArrayList<Integer>();

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
                int rooms = 0;
                for (int i = 0; i < 9; i++) {
                    double px = i % 3 == 0 ? minX : i % 3 == 1 ? (minX + maxX) / 2 : maxX;
                    double pz = i / 3 == 0 ? minZ : i / 3 == 1 ? (minZ + maxZ) / 2 : maxZ;
                    if (hasRoom(x, y, z, px, top, pz)) rooms++;
                }
                if (rooms == 0) return;
                if (rooms == 9) {
                    out.add(new Surface(out.size(), x, y, z, top, minX, minZ, maxX, maxZ));
                    return;
                }
                // Room on part of it only (a fence or a wall on the block, a low ceiling over one side):
                // one foothold for each connected part of the feet area with room, so that the parts
                // a wall separates are not one platform.
                int n = ROOM_GRID;
                boolean[][] ok = new boolean[n][n];
                double fx0 = minX - 0.3, fz0 = minZ - 0.3, fdx = (maxX - minX + 0.6) / (n - 1), fdz = (maxZ - minZ + 0.6) / (n - 1);
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < n; k++) {
                        double px = fx0 + i * fdx, pz = fz0 + k * fdz;
                        ok[i][k] = hasRoom(x, y, z, Math.max(minX - 0.29, Math.min(maxX + 0.29, px)), top, Math.max(minZ - 0.29, Math.min(maxZ + 0.29, pz)));
                    }
                }
                boolean[][] seen = new boolean[n][n];
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < n; k++) {
                        if (!ok[i][k] || seen[i][k]) continue;
                        int i0 = i, i1 = i, k0 = k, k1 = k;
                        Deque<int[]> q = new ArrayDeque<int[]>();
                        q.add(new int[] {i, k});
                        seen[i][k] = true;
                        while (!q.isEmpty()) {
                            int[] c = q.poll();
                            i0 = Math.min(i0, c[0]);
                            i1 = Math.max(i1, c[0]);
                            k0 = Math.min(k0, c[1]);
                            k1 = Math.max(k1, c[1]);
                            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                            for (int[] d : nb) {
                                int a = c[0] + d[0], b = c[1] + d[1];
                                if (a >= 0 && a < n && b >= 0 && b < n && ok[a][b] && !seen[a][b]) {
                                    seen[a][b] = true;
                                    q.add(new int[] {a, b});
                                }
                            }
                        }
                        // Feet rectangle of the part, given to the constructor as a footprint (it grows it by the half width).
                        out.add(new Surface(out.size(), x, y, z, top, fx0 + i0 * fdx + 0.3, fz0 + k0 * fdz + 0.3, fx0 + i1 * fdx - 0.3, fz0 + k1 * fdz - 0.3));
                    }
                }
            }
        });
        return out;
    }

    /** Feet points per side tested on a foothold with room on part of it only. */
    static final int ROOM_GRID = 9;

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
        return gap <= reach.reach(dy) + REACH_MARGIN && !walled(a, b);
    }

    /**
     * A wall between two footholds: on every straight line between their footprints (sampled between
     * the near points and between the centres) something solid stands at chest height above the
     * higher top, where a jump could not pass. Low obstacles (a fence to hop over) do not count.
     */
    private boolean walled(Surface a, Surface b) {
        double base = Math.max(a.top, b.top);
        double[][] lines = {
            {a.cx(), a.cz(), b.cx(), b.cz()},
            {clamp(b.cx(), a.minX + 0.3, a.maxX - 0.3), clamp(b.cz(), a.minZ + 0.3, a.maxZ - 0.3),
                clamp(a.cx(), b.minX + 0.3, b.maxX - 0.3), clamp(a.cz(), b.minZ + 0.3, b.maxZ - 0.3)},
        };
        List<Aabb> hit = new ArrayList<Aabb>();
        for (double[] l : lines) {
            double len = Math.hypot(l[2] - l[0], l[3] - l[1]);
            int steps = Math.max(1, (int) Math.ceil(len / 0.25));
            boolean blocked = false;
            for (int i = 1; i < steps && !blocked; i++) {
                double px = l[0] + (l[2] - l[0]) * i / steps, pz = l[1] + (l[3] - l[1]) * i / steps;
                if (a.contains(px, pz) || b.contains(px, pz)) continue;
                Aabb probe = new Aabb(px - 0.05, base + 0.6, pz - 0.05, px + 0.05, base + 1.3, pz + 0.05);
                int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
                hit.clear();
                for (int y = (int) Math.floor(base + 0.6) - 1; y <= (int) Math.floor(base + 1.3); y++) {
                    world.getBlock(bx, y, bz).addCollisionBoxes(world, bx, y, bz, probe, hit);
                }
                blocked = !hit.isEmpty();
            }
            if (!blocked) return false;
        }
        return true;
    }

    /** Something solid at chest height above {@code base} on the straight line (x1,z1)-(x2,z2). */
    boolean blockedLine(double x1, double z1, double x2, double z2, double base) {
        double len = Math.hypot(x2 - x1, z2 - z1);
        int steps = Math.max(1, (int) Math.ceil(len / 0.2));
        List<Aabb> hit = new ArrayList<Aabb>();
        for (int i = 1; i < steps; i++) {
            double px = x1 + (x2 - x1) * i / steps, pz = z1 + (z2 - z1) * i / steps;
            Aabb probe = new Aabb(px - 0.3, base + 0.6, pz - 0.3, px + 0.3, base + 1.3, pz + 0.3);
            hit.clear();
            for (int bx = (int) Math.floor(px - 0.3); bx <= (int) Math.floor(px + 0.3); bx++) {
                for (int bz = (int) Math.floor(pz - 0.3); bz <= (int) Math.floor(pz + 0.3); bz++) {
                    for (int y = (int) Math.floor(base + 0.6) - 1; y <= (int) Math.floor(base + 1.3); y++) {
                        world.getBlock(bx, y, bz).addCollisionBoxes(world, bx, y, bz, probe, hit);
                    }
                }
            }
            if (!hit.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Something solid at chest height above {@code base} on the straight line from (x, z) on foothold
     * a to the centre of cell c, outside both (a thin probe, so a ladder's own box or the wall it hangs
     * on does not count).
     */
    private boolean wallToCell(int[] c, double x, double z, double base, Surface a) {
        double tx = c[0] + 0.5, tz = c[2] + 0.5;
        double len = Math.hypot(tx - x, tz - z);
        int steps = Math.max(1, (int) Math.ceil(len / 0.2));
        List<Aabb> hit = new ArrayList<Aabb>();
        for (int i = 1; i < steps; i++) {
            double px = x + (tx - x) * i / steps, pz = z + (tz - z) * i / steps;
            int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
            if ((bx == c[0] && bz == c[2]) || a.contains(px, pz)) continue;
            Aabb probe = new Aabb(px - 0.05, base + 0.6, pz - 0.05, px + 0.05, base + 1.3, pz + 0.05);
            hit.clear();
            for (int y = (int) Math.floor(base + 0.6) - 1; y <= (int) Math.floor(base + 1.3); y++) {
                world.getBlock(bx, y, bz).addCollisionBoxes(world, bx, y, bz, probe, hit);
            }
            if (!hit.isEmpty()) return true;
        }
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** Ladders and vines: the player climbs them by pushing into them. */
    boolean climbable(int x, int y, int z) {
        String n = world.getBlock(x, y, z).name();
        return n.startsWith("minecraft:ladder") || n.equals("minecraft:vine");
    }

    /** The block at the player's feet is climbable ({@code EntityLivingBase.isOnLadder}, without trapdoors). */
    boolean onLadder(PlayerState s) {
        return climbable((int) Math.floor(s.posX), (int) Math.floor(s.posY), (int) Math.floor(s.posZ));
    }

    /** Water, which the player swims up through like a ladder (for the optimistic graph only). */
    boolean water(int x, int y, int z) {
        String n = world.getBlock(x, y, z).name();
        return n.startsWith("minecraft:water") || n.startsWith("minecraft:flowing_water");
    }

    /**
     * Optimistic moves through ladders, vines and water: the cells are grouped into connected bodies,
     * and every foothold the player can get into a body from is linked to every foothold the player
     * can get out of it onto, as if any way through the body were possible (climbing up wherever
     * there is something to push against, sliding along vines, swimming up). The cost of such a move
     * is bounded by the rise at the climbing speed ({@link #edgeCost}).
     */
    private void linkClimbs() {
        final Map<Long, int[]> cells = new HashMap<Long, int[]>();
        problem.world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                if (climbable(x, y, z) || water(x, y, z)) cells.put(key(x, y, z), new int[] {x, y, z});
            }
        });
        // Bodies: 6-connected groups of cells.
        Set<Long> done = new HashSet<Long>();
        int r = (int) Math.ceil(reach.reach(-8) + REACH_MARGIN) + 1;
        for (Map.Entry<Long, int[]> e0 : cells.entrySet()) {
            if (!done.add(e0.getKey())) continue;
            List<int[]> body = new ArrayList<int[]>();
            Deque<int[]> q = new ArrayDeque<int[]>();
            q.add(e0.getValue());
            while (!q.isEmpty()) {
                int[] c = q.poll();
                body.add(c);
                int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                for (int[] d : nb) {
                    long k = key(c[0] + d[0], c[1] + d[1], c[2] + d[2]);
                    if (cells.containsKey(k) && done.add(k)) q.add(cells.get(k));
                }
            }
            bodies.add(body);
            Set<Surface> in = new HashSet<Surface>(), out = new HashSet<Surface>();
            for (int[] c : body) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        List<Surface> list = column(c[0] + dx, c[2] + dz);
                        if (list == null) continue;
                        for (Surface a : list) {
                            // In: the feet get into the cell, by a jump up to it or by falling into it.
                            if (canEnter(a, c)) in.add(a);
                            // Out: from the cell onto a foothold at most a little above the cell's top,
                            // stepping off sideways when it is higher than the cell, else as a slow jump.
                            if (canLeave(c, a)) out.add(a);
                        }
                    }
                }
            }
            for (Surface a : in) {
                for (Surface h : out) {
                    if (h != a && h.platform != a.platform && !a.next.contains(h)) {
                        a.next.add(h);
                        a.viaBody.add(h);
                        a.viaBodyId.add(bodies.size() - 1);
                    }
                }
            }
        }
    }

    /** Connected groups of ladder, vine and water cells ({@link #linkClimbs}). */
    final List<List<int[]>> bodies = new ArrayList<List<int[]>>();

    /**
     * The player can get into cell c of a body from foothold a (the optimistic test of {@link #linkClimbs}):
     * in reach, and no wall at chest height on the way (an enclosed shaft is entered from its open side).
     */
    boolean canEnter(Surface a, int[] c) {
        double up = c[1] - a.top;
        if (up > reach.maxRise() + EPS || squareDist(a, c[0], c[2]) > reach.reach(Math.max(up, -8)) + REACH_MARGIN) return false;
        return !wallToCell(c, clamp(c[0] + 0.5, a.minX, a.maxX), clamp(c[2] + 0.5, a.minZ, a.maxZ), Math.max(a.top, c[1]), a);
    }

    /** The player can get out of cell c of a body onto foothold h. */
    boolean canLeave(int[] c, Surface h) {
        double rise = h.top - (c[1] + 1), d = squareDist(h, c[0], c[2]);
        if (rise > CLIMB_EXIT + reach.maxRise() + EPS || d > (rise > 0 ? 1.0 : reach.reach(Math.max(rise, -8)) + REACH_MARGIN)) return false;
        return !wallToCell(c, clamp(c[0] + 0.5, h.minX, h.maxX), clamp(c[2] + 0.5, h.minZ, h.maxZ), Math.max(h.top, c[1]), h);
    }

    /**
     * How far above a climbable cell's top the player can still get onto a foothold, beyond a jump's
     * rise: climbing out of the top carries the player a little higher, and there may be something to
     * stand on there and jump from (the top of the ladder's own box).
     */
    static final double CLIMB_EXIT = 0.6;

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }

    /** Horizontal distance from a surface's feet area to the block column (x, z). */
    private static double squareDist(Surface a, int x, int z) {
        double dx = Math.max(0, Math.max(x - a.maxX, a.minX - (x + 1)));
        double dz = Math.max(0, Math.max(z - a.maxZ, a.minZ - (z + 1)));
        return Math.sqrt(dx * dx + dz * dz);
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
        linkClimbs();
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
    /** Blocks per tick climbing a ladder while pushing into it: (0.2 - 0.08) * 0.98. */
    static final double CLIMB_SPEED = 0.1176;

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
                double c = dist[u] + edgeCost(a, b, v);
                if (c < dist[a.id]) {
                    dist[a.id] = c;
                    pq.add(new double[] {c, a.id});
                }
            }
        }
        for (Surface a : surfaces) a.toGo = dist[a.id];
    }

    /**
     * Ticks for the optimistic move a to b: centre to centre at the best speed, or, up a ladder, the
     * rise beyond a jump's at the steady climbing speed when that is slower.
     */
    private double edgeCost(Surface a, Surface b, double v) {
        double flat = Math.hypot(a.cx() - b.cx(), a.cz() - b.cz()) / v;
        double rise = b.top - a.top - reach.maxRise();
        return rise > 0 ? Math.max(flat, rise / CLIMB_SPEED) : flat;
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
