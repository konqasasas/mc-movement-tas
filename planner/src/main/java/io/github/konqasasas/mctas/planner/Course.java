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
        /** Moves in {@link #next} that bounce off a slime foothold, with that foothold ({@link Course#linkBounces}). */
        final Map<Surface, Surface> bounceVia = new HashMap<Surface, Surface>();
        /**
         * Moves in {@link #next} that bounce off a slime foothold up into a body of water or ladders and
         * climb out of it ({@link Course#linkBounceBodies}), and how ({@link BounceClimbManeuver}).
         */
        final List<Surface> viaBounceBody = new ArrayList<Surface>();
        final List<BounceEntry> viaBounceEntry = new ArrayList<BounceEntry>();
        /** Moves in {@link #next} that only another maneuver makes (through a body, down a hole), not a step. */
        final Set<Surface> notByStep = new HashSet<Surface>();

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
        return gap <= reach.reach(dy) + REACH_MARGIN && !walled(a, b) && !roofed(a, b);
    }

    /**
     * A drop onto b with a roof over it: above both b's point nearest a and b's centre, something
     * solid stands between the player's head on b and a's top (a fall would have to pass through it).
     */
    private boolean roofed(Surface a, Surface b) {
        if (b.top > a.top - 1.8) return false;
        double[][] points = {
            {clamp(a.cx(), b.minX + 0.3, b.maxX - 0.3), clamp(a.cz(), b.minZ + 0.3, b.maxZ - 0.3)},
            {b.cx(), b.cz()},
        };
        for (double[] p : points) {
            if (!collides(new Aabb(p[0] - 0.05, b.top + 1.8, p[1] - 0.05, p[0] + 0.05, a.top - EPS, p[1] + 0.05))) return false;
        }
        return true;
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
    static final double WALL_PROBE = 0.25;

    private boolean wallToCell(int[] c, double x, double z, double base, Surface a) {
        double tx = c[0] + 0.5, tz = c[2] + 0.5;
        double len = Math.hypot(tx - x, tz - z);
        int steps = Math.max(1, (int) Math.ceil(len / 0.2));
        List<Aabb> hit = new ArrayList<Aabb>();
        for (int i = 1; i < steps; i++) {
            double px = x + (tx - x) * i / steps, pz = z + (tz - z) * i / steps;
            if (((int) Math.floor(px) == c[0] && (int) Math.floor(pz) == c[2]) || a.contains(px, pz)) continue;
            // Nearly the player's width: a thin line slips past the corner of a slot a body is set in.
            Aabb probe = new Aabb(px - WALL_PROBE, base + 0.6, pz - WALL_PROBE, px + WALL_PROBE, base + 1.3, pz + WALL_PROBE);
            hit.clear();
            for (int bx = (int) Math.floor(px - WALL_PROBE); bx <= (int) Math.floor(px + WALL_PROBE); bx++) {
                for (int bz = (int) Math.floor(pz - WALL_PROBE); bz <= (int) Math.floor(pz + WALL_PROBE); bz++) {
                    for (int y = (int) Math.floor(base + 0.6) - 1; y <= (int) Math.floor(base + 1.3); y++) {
                        world.getBlock(bx, y, bz).addCollisionBoxes(world, bx, y, bz, probe, hit);
                    }
                }
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

    /**
     * The way to push to climb the ladder in cell c: towards the block it hangs on (a ladder facing
     * west hangs on the block east of it), or {0, 0} for anything else.
     */
    double[] push(int[] c) {
        String n = world.getBlock(c[0], c[1], c[2]).name();
        if (!n.startsWith("minecraft:ladder")) return new double[] {0, 0};
        if (n.contains("facing=north")) return new double[] {0, 1};
        if (n.contains("facing=south")) return new double[] {0, -1};
        if (n.contains("facing=west")) return new double[] {1, 0};
        if (n.contains("facing=east")) return new double[] {-1, 0};
        return new double[] {0, 0};
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
                boolean wet = water(c[0], c[1], c[2]);
                for (int[] d : wet ? WATER_NEIGHBOURS : NEIGHBOURS) {
                    long k = key(c[0] + d[0], c[1] + d[1], c[2] + d[2]);
                    if (cells.containsKey(k) && (!wet || water(c[0] + d[0], c[1] + d[1], c[2] + d[2])) && done.add(k)) q.add(cells.get(k));
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
            bodyOut.add(out);
            for (Surface a : in) {
                for (Surface h : out) {
                    if (h == a || h.platform == a.platform) continue;
                    // Every body that leads there is a way there (the first one may not work).
                    if (!a.next.contains(h)) {
                        a.next.add(h);
                        a.notByStep.add(h);
                    }
                    a.viaBody.add(h);
                    a.viaBodyId.add(bodies.size() - 1);
                }
            }
        }
    }

    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /**
     * Water cells the player touches both of at once, so swims on from one to the other: the box that
     * feels water (feet + 0.4 to feet + 1.4, 0.6 wide) spans a block of air between two cells above
     * each other, and straddles the corner between diagonal ones (a staircase of single water blocks).
     */
    private static final int[][] WATER_NEIGHBOURS;

    static {
        List<int[]> l = new ArrayList<int[]>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dy != 0 || dz != 0) l.add(new int[] {dx, dy, dz});
        WATER_NEIGHBOURS = l.toArray(new int[0][]);
    }

    /**
     * The fewest cells of a body from one cell to another, each touching the next (the adjacency of
     * {@link #linkClimbs}), both ends included; null when they are not connected.
     */
    List<int[]> pathThrough(int body, int[] from, int[] to) {
        Map<Long, int[]> cells = new HashMap<Long, int[]>();
        for (int[] c : bodies.get(body)) cells.put(key(c[0], c[1], c[2]), c);
        Map<Long, int[]> prev = new HashMap<Long, int[]>();
        Deque<int[]> q = new ArrayDeque<int[]>();
        q.add(from);
        prev.put(key(from[0], from[1], from[2]), from);
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (Arrays.equals(c, to)) {
                List<int[]> path = new ArrayList<int[]>();
                for (int[] k = c; ; k = prev.get(key(k[0], k[1], k[2]))) {
                    path.add(k);
                    if (Arrays.equals(k, from)) break;
                }
                java.util.Collections.reverse(path);
                return path;
            }
            boolean wet = water(c[0], c[1], c[2]);
            for (int[] d : wet ? WATER_NEIGHBOURS : NEIGHBOURS) {
                long k = key(c[0] + d[0], c[1] + d[1], c[2] + d[2]);
                int[] n = cells.get(k);
                if (n == null || prev.containsKey(k) || (wet && !water(n[0], n[1], n[2]))) continue;
                prev.put(k, c);
                q.add(n);
            }
        }
        return null;
    }

    /** Connected groups of ladder, vine and water cells ({@link #linkClimbs}). */
    final List<List<int[]>> bodies = new ArrayList<List<int[]>>();
    private final Map<Integer, Set<Long>> bodyKeys = new java.util.concurrent.ConcurrentHashMap<Integer, Set<Long>>();

    /** The cells of a body as {@link #key}s. */
    Set<Long> bodyKeys(int body) {
        return bodyKeys.computeIfAbsent(body, b -> {
            Set<Long> k = new HashSet<Long>();
            for (int[] c : bodies.get(b)) k.add(key(c[0], c[1], c[2]));
            return k;
        });
    }

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
        // Room in the cell's column up to the height the player leaves from (a ceiling right over
        // the top of a ladder keeps the player below it).
        double feet = Math.max(c[1], h.top - CLIMB_EXIT - reach.maxRise());
        if (feet > c[1] && collides(new Aabb(c[0] + 0.2, c[1] + 0.01, c[2] + 0.2, c[0] + 0.8, feet + 1.8, c[2] + 0.8), true)) return false;
        return !wallToCell(c, clamp(c[0] + 0.5, h.minX, h.maxX), clamp(c[2] + 0.5, h.minZ, h.maxZ), Math.max(h.top, c[1]), h);
    }

    /**
     * How far above a climbable cell's top the player can still get onto a foothold, beyond a jump's
     * rise: climbing out of the top carries the player a little higher, and there may be something to
     * stand on there and jump from (the top of the ladder's own box).
     */
    static final double CLIMB_EXIT = 0.6;

    static long key(int x, int y, int z) {
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
        linkBounces(r);
        linkClimbs();
        linkBounceBodies();
        linkHoles();
    }

    /** A bounce into a body: off slime foothold {@code slime}, up into {@code cell} of body {@code body}. */
    static final class BounceEntry {
        final Surface slime;
        final int body;
        final int[] cell;

        BounceEntry(Surface slime, int body, int[] cell) {
            this.slime = slime;
            this.body = body;
            this.cell = cell;
        }
    }

    /**
     * Bounces up into a body: from each foothold a that falls onto a slime foothold s, the bounce
     * ({@link #bounceRise}) reaches the cells of a body over s (at most {@link #BOUNCE_ASIDE} aside) whose
     * bottom the part of the box that feels water gets to; a is linked to every foothold the body can
     * be left onto ({@link #canLeave}).
     */
    /** How far beside the slime a bounce still gets into a body: a few ticks of the run carried on. */
    static final double BOUNCE_ASIDE = 3.0;

    private void linkBounceBodies() {
        Map<Long, Integer> cellBody = new HashMap<Long, Integer>();
        for (int b = 0; b < bodies.size(); b++) for (int[] c : bodies.get(b)) cellBody.put(key(c[0], c[1], c[2]), b);
        for (Surface a : surfaces) {
            for (Surface s : new ArrayList<Surface>(a.next)) {
                if (a.top <= s.top + 1 || !slime(s)) continue;
                double rise = bounceRise(a.top + reach.maxRise() - s.top, water(s.x, s.y + 1, s.z));
                Map<Integer, int[]> best = new HashMap<Integer, int[]>();
                for (int x = (int) Math.floor(s.minX - BOUNCE_ASIDE); x <= (int) Math.floor(s.maxX + BOUNCE_ASIDE); x++) {
                    for (int z = (int) Math.floor(s.minZ - BOUNCE_ASIDE); z <= (int) Math.floor(s.maxZ + BOUNCE_ASIDE); z++) {
                        for (int y = (int) Math.floor(s.top) + 1; y <= (int) Math.floor(s.top + rise + 1.3); y++) {
                            Integer b = cellBody.get(key(x, y, z));
                            if (b == null) continue;
                            int[] old = best.get(b);
                            if (old == null || y < old[1]) best.put(b, new int[] {x, y, z});
                        }
                    }
                }
                for (Map.Entry<Integer, int[]> e : best.entrySet()) {
                    for (Surface h : bodyOut.get(e.getKey())) {
                        if (h.platform == a.platform || h.platform == s.platform) continue;
                        if (!a.next.contains(h)) {
                            a.next.add(h);
                            a.notByStep.add(h);
                        }
                        a.viaBounceBody.add(h);
                        a.viaBounceEntry.add(new BounceEntry(s, e.getKey(), e.getValue()));
                    }
                }
            }
        }
    }

    /** The footholds each body can be left onto ({@link #canLeave}), by body. */
    private final List<Set<Surface>> bodyOut = new ArrayList<Set<Surface>>();

    /**
     * Slime blocks: a player falling onto one (not sneaking) bounces back up with the speed it came
     * down at, so how high the bounce goes depends on where the fall started. Each foothold a that
     * falls onto a slime foothold s is linked through it to every foothold no higher above s than the
     * bounce from a jump off a goes ({@link #bounceRise}), within the longest reach of a fall from s,
     * and not walled off from s ({@link Surface#bounceVia} keeps s for the aims).
     */
    private void linkBounces(int r) {
        double far = reach.reach(-8) + REACH_MARGIN;
        List<Surface[]> add = new ArrayList<Surface[]>();
        for (Surface a : surfaces) {
            for (Surface s : a.next) {
                if (a.top <= s.top + 1 || !slime(s)) continue;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        List<Surface> list = column(s.x + dx, s.z + dz);
                        if (list == null) continue;
                        for (Surface b : list) {
                            if (b.platform == s.platform || b.platform == a.platform) continue;
                            if (b.top > s.top + bounceRise(a.top + reach.maxRise() - s.top, water(s.x, s.y + 1, s.z)) || rectGap(s, b) > far || walled(s, b)) continue;
                            add.add(new Surface[] {a, b, s});
                        }
                    }
                }
            }
        }
        for (Surface[] e : add) {
            Surface a = e[0], b = e[1], sl = e[2];
            if (a.next.contains(b) && !a.bounceVia.containsKey(b)) continue;
            if (!a.next.contains(b)) a.next.add(b);
            // By way of the slime foothold nearest the middle of the move.
            Surface old = a.bounceVia.get(b);
            double mx = (a.cx() + b.cx()) / 2, mz = (a.cz() + b.cz()) / 2;
            if (old == null || Math.hypot(sl.cx() - mx, sl.cz() - mz) < Math.hypot(old.cx() - mx, old.cz() - mz)) a.bounceVia.put(b, sl);
        }
    }

    /** The extent (minX, maxX, minZ, maxZ) of the slime footholds on s's platform. */
    double[] slimeExtent(Surface s) {
        double[] e = {s.minX, s.maxX, s.minZ, s.maxZ};
        for (Surface q : platforms.get(s.platform)) {
            if (!slime(q)) continue;
            e[0] = Math.min(e[0], q.minX);
            e[1] = Math.max(e[1], q.maxX);
            e[2] = Math.min(e[2], q.minZ);
            e[3] = Math.max(e[3], q.maxZ);
        }
        return e;
    }

    /** Feet below this height over a slime block under water are in the water. */
    private static final double WET = 0.5;

    /**
     * How high above a slime block a player bounces after falling {@code drop} onto it from rest: the
     * landing turns the vertical speed round, and air drag takes some of it on the way up (a fall of 4
     * comes back 2.55). With water over the slime, the water slows the fall and the bounce while the
     * player is in it.
     */
    static double bounceRise(double drop, boolean wet) {
        // The drag of a tick is the water's when the tick starts with the player in it.
        double y = drop, vy = 0;
        for (int i = 0; i < 400 && y + vy > 0; i++) {
            boolean in = wet && y < WET;
            y += vy;
            vy = in ? vy * 0.8 - 0.02 : (vy - 0.08) * 0.98;
        }
        if (y + vy > 0) return 0;
        boolean in = wet && y < WET;
        vy = in ? -vy * 0.8 - 0.02 : (-vy - 0.08) * 0.98;
        y = 0;
        double top = 0;
        for (int i = 0; i < 400 && vy > 0; i++) {
            in = wet && y < WET;
            y += vy;
            top = Math.max(top, y);
            vy = in ? vy * 0.8 - 0.02 : (vy - 0.08) * 0.98;
        }
        return top;
    }

    boolean slime(Surface a) {
        return world.getBlock(a.x, a.y, a.z).name().equals("minecraft:slime");
    }

    /**
     * A gap in a floor the player can drop through: the middle of a 2x2 group of iron bars, panes or
     * fences, whose arms leave a hole wider than the player. The player stands around it at
     * {@code top} and falls onto {@code below}.
     */
    static final class Hole {
        final double x, z, top;
        final Surface below;

        Hole(double x, double z, double top, Surface below) {
            this.x = x;
            this.z = z;
            this.top = top;
            this.below = below;
        }

        @Override
        public String toString() {
            return String.format("hole (%.2f, %.2f, %.2f) onto %s", x, top, z, below);
        }
    }

    /** The holes ({@link Hole}) each platform has. */
    final Map<Integer, List<Hole>> holes = new HashMap<Integer, List<Hole>>();

    /**
     * Holes: block corners and edge midpoints of every block that does not fill its top, where a player
     * box goes down through the block's layer without touching anything, with something solid half a
     * block off on every side (an open edge is not a hole: the steps already walk off it). Each is
     * linked from the platforms at the floor's top around it to the highest foothold below it.
     */
    private void linkHoles() {
        final Set<Long> tested = new HashSet<Long>();
        final Aabb everything = new Aabb(-3.0E7, -64, -3.0E7, 3.0E7, 512, 3.0E7);
        problem.world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                List<Aabb> boxes = new ArrayList<Aabb>();
                type.addCollisionBoxes(world, x, y, z, everything, boxes);
                if (boxes.isEmpty() || (boxes.size() == 1 && boxes.get(0).minX <= x + EPS && boxes.get(0).maxX >= x + 1 - EPS
                    && boxes.get(0).minZ <= z + EPS && boxes.get(0).maxZ >= z + 1 - EPS)) return;
                double top = Double.NEGATIVE_INFINITY;
                for (Aabb b : boxes) top = Math.max(top, b.maxY);
                for (int i = 0; i <= 2; i++) {
                    for (int k = 0; k <= 2; k++) {
                        double px = x + i * 0.5, pz = z + k * 0.5;
                        if (!tested.add(key((int) Math.round(px * 2), y, (int) Math.round(pz * 2)))) continue;
                        hole(px, y, pz, top);
                    }
                }
            }
        });
    }

    private void hole(double px, int y, double pz, double top) {
        if (collides(new Aabb(px - 0.3, y, pz - 0.3, px + 0.3, top + 1.8, pz + 0.3))) return;
        double[][] off = {{0.5, 0}, {-0.5, 0}, {0, 0.5}, {0, -0.5}};
        for (double[] o : off) {
            double qx = px + o[0], qz = pz + o[1];
            if (!collides(new Aabb(qx - 0.3, y, qz - 0.3, qx + 0.3, top - EPS, qz + 0.3))) return;
        }
        // The floor around it, and what the player lands on below.
        Set<Integer> above = new HashSet<Integer>();
        Surface below = null;
        for (int bx = (int) Math.floor(px - 0.3); bx <= (int) Math.floor(px + 0.3); bx++) {
            for (int bz = (int) Math.floor(pz - 0.3); bz <= (int) Math.floor(pz + 0.3); bz++) {
                List<Surface> list = column(bx, bz);
                if (list == null) continue;
                for (Surface c : list) {
                    if (Math.abs(c.top - top) < EPS && c.contains(px, pz)) above.add(c.platform);
                    if (c.top < y + EPS && c.contains(px, pz) && (below == null || c.top > below.top)) below = c;
                }
            }
        }
        if (above.isEmpty() || below == null || collides(new Aabb(px - 0.3, below.top + EPS, pz - 0.3, px + 0.3, y, pz + 0.3))) return;
        for (int p : above) {
            holes.computeIfAbsent(p, k -> new ArrayList<Hole>()).add(new Hole(px, pz, top, below));
            for (Surface a : platforms.get(p)) {
                if (a.next.contains(below)) continue;
                a.next.add(below);
                a.notByStep.add(below);
            }
        }
    }

    /** Some collision box of the world meets the box. */
    boolean collides(Aabb box) {
        return collides(box, false);
    }

    /** Some collision box of the world meets the box, not counting ladders and vines when asked. */
    boolean collides(Aabb box, boolean skipClimbable) {
        List<Aabb> hit = new ArrayList<Aabb>();
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - EPS); x++) {
            for (int y = (int) Math.floor(box.minY) - 1; y <= (int) Math.floor(box.maxY - EPS); y++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - EPS); z++) {
                    if (skipClimbable && climbable(x, y, z)) continue;
                    world.getBlock(x, y, z).addCollisionBoxes(world, x, y, z, box, hit);
                    if (!hit.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /** Horizontal gap from the surface to the goal box when the goal is one optimistic move away, else -1. */
    double goalGap(Surface a) {
        Region g = problem.goal;
        double dx = Math.max(0, Math.max(g.minX - a.maxX, a.minX - g.maxX));
        double dz = Math.max(0, Math.max(g.minZ - a.maxZ, a.minZ - g.maxZ));
        double gap = Math.sqrt(dx * dx + dz * dz);
        // A goal box too flat to pass through in the air: only standing on a top inside it reaches it.
        if (flatGoal()) return a.top >= g.minY - EPS && a.top <= g.maxY + EPS && gap < EPS ? 0 : -1;
        // The best height inside the goal box to aim for: as low as the box allows, but not below the surface.
        double dy = Math.max(g.minY - a.top, Math.min(0, g.maxY - a.top));
        if (dy > reach.maxRise() + EPS) return -1;
        if (gap < EPS) return 0;
        return gap <= reach.reach(dy) + REACH_MARGIN ? gap : -1;
    }

    /**
     * The goal box is lower than a tick's fall or rise at the top of a jump (0.0784 at least), so a
     * player in the air almost never has its feet inside: the goal is a top to stand on.
     */
    boolean flatGoal() {
        return problem.goal.maxY - problem.goal.minY < 0.05;
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
        if (!flatGoal() && y >= g.minY - reach.maxRise() && Math.sqrt(dx * dx + dz * dz) <= reach.reach(g.minY - y) + REACH_MARGIN) best = direct;
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
