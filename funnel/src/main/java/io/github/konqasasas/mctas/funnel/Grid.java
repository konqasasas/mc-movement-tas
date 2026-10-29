package io.github.konqasasas.mctas.funnel;

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
import java.util.List;

/**
 * The course as a grid of places the player's feet point can be, for the guidance field.
 *
 * <p>Horizontally the cells are the player's centre on a {@link #RES} lattice; vertically a level is
 * one block of feet height. A cell is passable when the player's box fits with the feet somewhere in
 * that level, standable when a collision box top under the 0.6 footprint holds the feet in that level
 * with 1.8 of room above (the stand height is kept), climbable when the feet block is a ladder or a
 * vine (L-504), wet when the body touches water. A passable cell counts as reachable in the air only
 * within {@link #AIR_REACH} blocks of a stand from which a jump (or a fall) gets the feet that high.
 *
 * <p>{@link Field} runs Dijkstra over these cells with optimistic moves.
 */
final class Grid {
    static final double RES = 0.25;
    /**
     * Blocks around a stand where the player may be in the air at a level a jump from it reaches: half
     * the longest flat jump, so that the air of two stands meets over a gap a jump clears.
     */
    static final double AIR_REACH = 2.4;
    static final double DROP_REACH = 0.6, MAX_REACH = 6.0;
    static final double JUMP_RISE = 1.25;

    /** How high a stand on slime may send the player, optimistically (a bounce, or a jump off one). */
    static final double SLIME_RISE = 4.0;

    static final byte PASS = 1, STAND = 2, CLIMB = 4, WATER = 8, AIR = 16, SLIME = 32;

    final Problem problem;
    final BlockWorld world;
    /** World position of cell (0, 0, 0): centre x, centre z, bottom of level 0. */
    final double x0, z0;
    final int y0;
    final int nx, nz, ny;
    final byte[] flags;
    /** Feet height of the stand in the cell, NaN when not standable. */
    final float[] top;

    Grid(Problem problem) {
        this(problem, DenseBlockWorld.of(problem.world, 4));
    }

    Grid(Problem problem, BlockWorld world) {
        this.problem = problem;
        this.world = world;
        int[] b = bounds(problem.world);
        int margin = 3;
        int bx0 = Math.min(b[0], (int) Math.floor(Math.min(problem.start.minX, problem.goal.minX))) - margin;
        int bx1 = Math.max(b[3], (int) Math.floor(Math.max(problem.start.maxX, problem.goal.maxX))) + margin;
        int bz0 = Math.min(b[2], (int) Math.floor(Math.min(problem.start.minZ, problem.goal.minZ))) - margin;
        int bz1 = Math.max(b[5], (int) Math.floor(Math.max(problem.start.maxZ, problem.goal.maxZ))) + margin;
        int by0 = Math.max(b[1], (int) Math.floor(problem.voidY)) - 1;
        int by1 = Math.max(b[4], (int) Math.floor(Math.max(problem.start.maxY, problem.goal.maxY))) + 3;
        x0 = bx0;
        z0 = bz0;
        y0 = by0;
        nx = (int) Math.round((bx1 + 1 - bx0) / RES) + 1;
        nz = (int) Math.round((bz1 + 1 - bz0) / RES) + 1;
        ny = by1 - by0 + 1;
        flags = new byte[nx * nz * ny];
        top = new float[nx * nz * ny];
        Arrays.fill(top, Float.NaN);
        build();
        slimes();
        airReach();
    }

    /** Marks the stands on slime: the block under the middle of the feet. */
    private void slimes() {
        for (int id = 0; id < flags.length; id++) {
            if ((flags[id] & STAND) == 0) continue;
            int bx = (int) Math.floor(cx(ix(id))), bz = (int) Math.floor(cz(iz(id))), by = (int) Math.floor(top[id] - 1e-3);
            if (slime(world.getBlock(bx, by, bz))) flags[id] |= SLIME;
        }
    }

    int id(int l, int ix, int iz) {
        return (l * nx + ix) * nz + iz;
    }

    int level(int id) {
        return id / (nx * nz);
    }

    int ix(int id) {
        return (id / nz) % nx;
    }

    int iz(int id) {
        return id % nz;
    }

    double cx(int ix) {
        return x0 + ix * RES;
    }

    double cz(int iz) {
        return z0 + iz * RES;
    }

    boolean in(int l, int ix, int iz) {
        return l >= 0 && l < ny && ix >= 0 && ix < nx && iz >= 0 && iz < nz;
    }

    boolean has(int id, byte f) {
        return (flags[id] & f) != 0;
    }

    /** The cell of a feet point, or -1 outside the grid. */
    int cellOf(double x, double y, double z) {
        int l = (int) Math.floor(y - y0 + 1e-7);
        int ix = (int) Math.round((x - x0) / RES), iz = (int) Math.round((z - z0) / RES);
        return in(l, ix, iz) ? id(l, ix, iz) : -1;
    }

    private static int[] bounds(SparseBlockWorld w) {
        final int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        w.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                b[0] = Math.min(b[0], x);
                b[1] = Math.min(b[1], y);
                b[2] = Math.min(b[2], z);
                b[3] = Math.max(b[3], x);
                b[4] = Math.max(b[4], y);
                b[5] = Math.max(b[5], z);
            }
        });
        if (b[0] == Integer.MAX_VALUE) return new int[] {0, 0, 0, 0, 0, 0};
        return b;
    }

    /** Block names carry their properties ({@code minecraft:ladder[facing=north]}). */
    private static boolean is(BlockType t, String id) {
        String n = t.name();
        return n.startsWith(id) && (n.length() == id.length() || n.charAt(id.length()) == '[');
    }

    static boolean hazard(BlockType t) {
        return is(t, "minecraft:cactus") || is(t, "minecraft:fire") || is(t, "minecraft:lava") || is(t, "minecraft:flowing_lava");
    }

    static boolean magma(BlockType t) {
        return is(t, "minecraft:magma");
    }

    static boolean slime(BlockType t) {
        return is(t, "minecraft:slime");
    }

    /** The rise a stand allows: a jump, or a bounce off slime. */
    double rise(int id) {
        return (flags[id] & SLIME) != 0 ? SLIME_RISE : JUMP_RISE;
    }

    static boolean climbable(BlockType t) {
        return is(t, "minecraft:ladder") || is(t, "minecraft:vine");
    }

    static boolean water(BlockType t) {
        return is(t, "minecraft:water") || is(t, "minecraft:flowing_water");
    }

    private void build() {
        Aabb all = new Aabb(-3.0E7, -64, -3.0E7, 3.0E7, 512, 3.0E7);
        List<Aabb> boxes = new ArrayList<Aabb>();
        double[] lo = new double[8192], hi = new double[8192];
        boolean[] magma = new boolean[8192];
        for (int ix = 0; ix < nx; ix++) {
            double cx = cx(ix);
            int bx0 = (int) Math.floor(cx - 0.3), bx1 = (int) Math.floor(cx + 0.3 - 1e-9);
            for (int iz = 0; iz < nz; iz++) {
                double cz = cz(iz);
                int bz0 = (int) Math.floor(cz - 0.3), bz1 = (int) Math.floor(cz + 0.3 - 1e-9);
                // Solid intervals of the column under the 0.6 footprint.
                int n = 0;
                for (int y = y0 - 1; y < y0 + ny + 2; y++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        for (int bz = bz0; bz <= bz1; bz++) {
                            BlockType t = world.getBlock(bx, y, bz);
                            if (t.isAir()) continue;
                            if (hazard(t)) {
                                if (n == lo.length) break;
                                lo[n] = y;
                                hi[n] = y + 1;
                                magma[n] = true;
                                n++;
                                continue;
                            }
                            boxes.clear();
                            t.addCollisionBoxes(world, bx, y, bz, all, boxes);
                            for (Aabb a : boxes) {
                                if (a.minX >= cx + 0.3 || a.maxX <= cx - 0.3 || a.minZ >= cz + 0.3 || a.maxZ <= cz - 0.3) continue;
                                if (n == lo.length) break;
                                lo[n] = a.minY;
                                hi[n] = a.maxY;
                                magma[n] = magma(t);
                                n++;
                            }
                        }
                    }
                }
                sortIntervals(lo, hi, magma, n);
                // Free gaps between the merged solid intervals.
                double floor = Double.NEGATIVE_INFINITY;
                boolean floorBad = true;
                int i = 0;
                while (true) {
                    double ceil = Double.POSITIVE_INFINITY;
                    double nextFloor = 0;
                    boolean nextBad = false;
                    if (i < n) {
                        ceil = lo[i];
                        double h = hi[i];
                        boolean bad = magma[i];
                        int j = i + 1;
                        while (j < n && lo[j] <= h + 1e-9) {
                            if (hi[j] > h + 1e-9) {
                                h = hi[j];
                                bad = magma[j];
                            } else if (Math.abs(hi[j] - h) < 1e-9) {
                                bad |= magma[j];
                            }
                            j++;
                        }
                        nextFloor = h;
                        nextBad = bad;
                        i = j;
                    }
                    gap(ix, iz, floor, ceil, !floorBad && floor != Double.NEGATIVE_INFINITY);
                    if (ceil == Double.POSITIVE_INFINITY) break;
                    floor = nextFloor;
                    floorBad = nextBad;
                }
                // Climbing and water: by the feet block (ladders) and the body (water).
                for (int l = 0; l < ny; l++) {
                    int id = id(l, ix, iz);
                    if ((flags[id] & PASS) == 0) continue;
                    if (climbable(world.getBlock((int) Math.floor(cx), y0 + l, (int) Math.floor(cz)))) flags[id] |= CLIMB;
                    boolean wet = false;
                    for (int bx = bx0; bx <= bx1 && !wet; bx++) {
                        for (int bz = bz0; bz <= bz1 && !wet; bz++) {
                            wet = water(world.getBlock(bx, y0 + l, bz)) || water(world.getBlock(bx, y0 + l + 1, bz));
                        }
                    }
                    if (wet) flags[id] |= WATER;
                }
            }
        }
    }

    private static void sortIntervals(double[] lo, double[] hi, boolean[] m, int n) {
        for (int i = 1; i < n; i++) {
            double a = lo[i], b = hi[i];
            boolean c = m[i];
            int j = i - 1;
            while (j >= 0 && lo[j] > a) {
                lo[j + 1] = lo[j];
                hi[j + 1] = hi[j];
                m[j + 1] = m[j];
                j--;
            }
            lo[j + 1] = a;
            hi[j + 1] = b;
            m[j + 1] = c;
        }
    }

    /** Marks the levels of a free gap: passable where the feet fit, standable at the floor. */
    private void gap(int ix, int iz, double floor, double ceil, boolean standable) {
        double feetLo = Math.max(floor, y0), feetHi = Math.min(ceil - 1.8, y0 + ny);
        if (feetHi < feetLo - 1e-9) return;
        int l0 = (int) Math.floor(feetLo - y0 + 1e-9), l1 = (int) Math.floor(feetHi - y0 + 1e-9);
        for (int l = Math.max(0, l0); l <= Math.min(ny - 1, l1); l++) flags[id(l, ix, iz)] |= PASS;
        if (standable && floor >= y0 && floor < y0 + ny) {
            int l = (int) Math.floor(floor - y0 + 1e-9);
            int id = id(l, ix, iz);
            flags[id] |= STAND | PASS;
            if (Float.isNaN(top[id]) || floor > top[id]) top[id] = (float) floor;
        }
    }

    /**
     * Marks the passable cells a jump or a fall could put the feet in: within {@link #AIR_REACH}
     * blocks of a stand whose height plus the jump's rise reaches the level, plus
     * {@link #DROP_REACH} blocks for every block the stand is above the level (a jump that comes down
     * lower carries further), at most {@link #MAX_REACH}. Two stands' air touches only across a gap a
     * jump could clear.
     */
    private void airReach() {
        int unit = (int) Math.round(3 / RES);
        int[] dist = new int[nx * nz];
        for (int l = 0; l < ny; l++) {
            Arrays.fill(dist, Integer.MAX_VALUE / 2);
            for (int lv = Math.max(0, l - (int) Math.ceil(SLIME_RISE) - 1); lv < ny; lv++) {
                for (int ix = 0; ix < nx; ix++) {
                    for (int iz = 0; iz < nz; iz++) {
                        int id = id(lv, ix, iz);
                        if ((flags[id] & (STAND | CLIMB | WATER)) == 0) continue;
                        double t = (flags[id] & STAND) != 0 ? top[id] : y0 + lv + 1;
                        if (t + rise(id) < y0 + l - 1e-9) continue;
                        double bonus = Math.min(MAX_REACH - AIR_REACH, DROP_REACH * Math.max(0, t - (y0 + l)));
                        int seed = -(int) Math.round(bonus * unit);
                        int k = ix * nz + iz;
                        if (seed < dist[k]) dist[k] = seed;
                    }
                }
            }
            chamfer(dist);
            int limit = (int) Math.round(AIR_REACH * unit);
            for (int ix = 0; ix < nx; ix++) {
                for (int iz = 0; iz < nz; iz++) {
                    int id = id(l, ix, iz);
                    if ((flags[id] & PASS) != 0 && dist[ix * nz + iz] <= limit) flags[id] |= AIR;
                }
            }
        }
    }

    /** Two-pass 3-4 chamfer distance (in thirds of a cell). */
    private void chamfer(int[] d) {
        for (int ix = 0; ix < nx; ix++) {
            for (int iz = 0; iz < nz; iz++) {
                int k = ix * nz + iz, v = d[k];
                if (ix > 0) {
                    v = Math.min(v, d[k - nz] + 3);
                    if (iz > 0) v = Math.min(v, d[k - nz - 1] + 4);
                    if (iz < nz - 1) v = Math.min(v, d[k - nz + 1] + 4);
                }
                if (iz > 0) v = Math.min(v, d[k - 1] + 3);
                d[k] = v;
            }
        }
        for (int ix = nx - 1; ix >= 0; ix--) {
            for (int iz = nz - 1; iz >= 0; iz--) {
                int k = ix * nz + iz, v = d[k];
                if (ix < nx - 1) {
                    v = Math.min(v, d[k + nz] + 3);
                    if (iz > 0) v = Math.min(v, d[k + nz - 1] + 4);
                    if (iz < nz - 1) v = Math.min(v, d[k + nz + 1] + 4);
                }
                if (iz < nz - 1) v = Math.min(v, d[k + 1] + 3);
                d[k] = v;
            }
        }
    }

    /** The cells whose feet point lies in a region (a flat region: the stands at its height). */
    List<Integer> cellsIn(Region g) {
        List<Integer> out = new ArrayList<Integer>();
        boolean flat = g.maxY - g.minY < 1e-6;
        for (int l = 0; l < ny; l++) {
            double ylo = y0 + l, yhi = y0 + l + 1;
            if (g.maxY < ylo - 1e-9 || g.minY >= yhi) continue;
            for (int ix = 0; ix < nx; ix++) {
                double x = cx(ix);
                if (x < g.minX - RES / 2 || x > g.maxX + RES / 2) continue;
                for (int iz = 0; iz < nz; iz++) {
                    double z = cz(iz);
                    if (z < g.minZ - RES / 2 || z > g.maxZ + RES / 2) continue;
                    int id = id(l, ix, iz);
                    if ((flags[id] & PASS) == 0) continue;
                    if (flat && ((flags[id] & STAND) == 0 || Math.abs(top[id] - g.minY) > 1e-4)) continue;
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** True when the player gets hurt in this state (D-005): on magma, or touching cactus, fire or lava. */
    boolean hurts(PlayerState s) {
        if (s.onGround && !s.isSneaking()) {
            int x = (int) Math.floor(s.posX), y = (int) Math.floor(s.posY - 0.2), z = (int) Math.floor(s.posZ);
            if (magma(world.getBlock(x, y, z))) return true;
        }
        Aabb b = s.box;
        int x0 = (int) Math.floor(b.minX + 0.001), x1 = (int) Math.floor(b.maxX - 0.001);
        int y0 = (int) Math.floor(b.minY + 0.001), y1 = (int) Math.floor(b.maxY - 0.001);
        int z0 = (int) Math.floor(b.minZ + 0.001), z1 = (int) Math.floor(b.maxZ - 0.001);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (hazard(world.getBlock(x, y, z))) return true;
                }
            }
        }
        return false;
    }
}
