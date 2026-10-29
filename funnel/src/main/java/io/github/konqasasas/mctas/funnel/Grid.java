package io.github.konqasasas.mctas.funnel;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.DenseBlockWorld;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.judge.Hazards;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** Passable only off the cell centre (a slot barely wider than the player): see {@link #fit}. */
    static final byte SLOT = 64;

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
        if (System.getenv("FUNNEL_FREE") != null) {
            String[] p = System.getenv("FUNNEL_FREE").split(",");
            double fx0 = Double.parseDouble(p[0]), fx1 = Double.parseDouble(p[1]), fz0 = Double.parseDouble(p[2]), fz1 = Double.parseDouble(p[3]), fy = Double.parseDouble(p[4]);
            for (double z = fz0; z <= fz1 + 1e-9; z += 1 / 32.0) {
                StringBuilder sb = new StringBuilder(String.format("%8.3f ", z));
                for (double x = fx0; x <= fx1 + 1e-9; x += 1 / 32.0) sb.append(free(x, z, fy) ? '.' : '#');
                System.out.println(sb);
            }
        }
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

    /** Blocks that hurt while the box overlaps them, as the judge has them (D-005). */
    static boolean hazard(BlockType t) {
        return Hazards.hurtsInside(t);
    }

    /** Blocks that hurt while standing on them without sneaking (D-005). */
    static boolean magma(BlockType t) {
        return Hazards.hurtsUnderfoot(t);
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
        Column col = new Column();
        byte[] nf = new byte[ny];
        float[] nt = new float[ny];
        for (int ix = 0; ix < nx; ix++) {
            double cx = cx(ix);
            int bx0 = (int) Math.floor(cx - 0.3), bx1 = (int) Math.floor(cx + 0.3 - 1e-9);
            for (int iz = 0; iz < nz; iz++) {
                double cz = cz(iz);
                int bz0 = (int) Math.floor(cz - 0.3), bz1 = (int) Math.floor(cz + 0.3 - 1e-9);
                Arrays.fill(nf, (byte) 0);
                Arrays.fill(nt, Float.NaN);
                boolean slots = col.mark(cx, cz, flags, top, id(0, ix, iz), nx * nz, nf, nt);
                // A slot barely wider than the player (an open trapdoor in each side of a doorway, a
                // fence post next to a wall): the box fits off the cell centre. Passable, with the
                // place it fits kept for the beam's line moves (fit).
                for (int l = 0; slots && l < ny; l++) {
                    int id = id(l, ix, iz);
                    if ((nf[l] & PASS) == 0 || (flags[id] & PASS) != 0) continue;
                    // The narrow footprint is only a necessary condition: the box has to fit somewhere.
                    double feet = (nf[l] & STAND) != 0 ? nt[l] : y0 + l;
                    double[] at = fit(cx, cz, feet);
                    if (at == null && (nf[l] & STAND) == 0) at = fit(cx, cz, feet + 0.5);
                    if (at == null) continue;
                    fits.put(id, at);
                    flags[id] |= PASS | SLOT | (nf[l] & STAND);
                    if ((nf[l] & STAND) != 0) top[id] = nt[l];
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

    /** Half the footprint that finds slots: the player's half width less half a cell. */
    static final double NARROW = 0.3 - RES / 2;
    /** Where the player's box fits in the slot cells (passable only off their centre). */
    private final Map<Integer, double[]> fits = new HashMap<Integer, double[]>();

    /** The x, z of the cell where the player fits: its centre, or for a slot the middle of the places in the cell where the box fits. */
    double[] fit(int id) {
        double[] f = (flags[id] & SLOT) != 0 ? fits.get(id) : null;
        return f != null ? f : new double[] {cx(ix(id)), cz(iz(id))};
    }

    private final List<Aabb> fitBoxes = new ArrayList<Aabb>();

    /**
     * The middle of the places within the cell (on a 1/64 lattice) where the player's box fits with
     * the feet at this height; null when none. The two lines through the centre first (a slot or a
     * wall is across one of them), the whole cell only when neither has a place.
     */
    private double[] fit(double cx, double cz, double feet) {
        int steps = 8;
        double sx = 0, sz = 0;
        int n = 0;
        for (int a = -steps; a <= steps; a++) {
            double d = a * (RES / 2) / steps;
            if (free(cx + d, cz, feet)) {
                sx += cx + d;
                sz += cz;
                n++;
            }
            if (a != 0 && free(cx, cz + d, feet)) {
                sx += cx;
                sz += cz + d;
                n++;
            }
        }
        for (int a = -steps; n == 0 && a <= steps; a++) {
            for (int b = -steps; b <= steps; b++) {
                double x = cx + a * (RES / 2) / steps, z = cz + b * (RES / 2) / steps;
                if (!free(x, z, feet)) continue;
                sx += x;
                sz += z;
                n++;
            }
        }
        return n == 0 ? null : new double[] {sx / n, sz / n};
    }

    /** True when the player's standing box at this feet point touches no collision box. */
    private boolean free(double x, double z, double feet) {
        Aabb box = new Aabb(x - 0.3 + 1e-7, feet + 1e-7, z - 0.3 + 1e-7, x + 0.3 - 1e-7, feet + 1.8 - 1e-7, z + 0.3 - 1e-7);
        for (int bx = (int) Math.floor(box.minX); bx <= (int) Math.floor(box.maxX); bx++) {
            for (int by = (int) Math.floor(box.minY) - 1; by <= (int) Math.floor(box.maxY); by++) {
                for (int bz = (int) Math.floor(box.minZ); bz <= (int) Math.floor(box.maxZ); bz++) {
                    BlockType t = world.getBlock(bx, by, bz);
                    if (t.isAir()) continue;
                    if (hazard(t)) return false;
                    fitBoxes.clear();
                    t.addCollisionBoxes(world, bx, by, bz, box, fitBoxes);
                    for (Aabb c : fitBoxes) {
                        if (c.maxX > box.minX && c.minX < box.maxX && c.maxY > box.minY && c.minY < box.maxY && c.maxZ > box.minZ && c.minZ < box.maxZ) return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The free gaps of a column under the player's footprint, marked into a column of cells; with
     * {@link #NARROW} also those under the narrow footprint (for slots), when they differ.
     */
    private final class Column {
        final Aabb all = new Aabb(-3.0E7, -64, -3.0E7, 3.0E7, 512, 3.0E7);
        final List<Aabb> boxes = new ArrayList<Aabb>();
        final double[] lo = new double[8192], hi = new double[8192];
        final boolean[] magma = new boolean[8192];
        final double[] nlo = new double[8192], nhi = new double[8192];
        final boolean[] nmagma = new boolean[8192];

        /**
         * Marks passable and standable levels at x, z into flags/top at base + level * stride; with
         * nf, also the narrow footprint's levels into nf/nt (one cell per level). Returns false when
         * the narrow footprint was not marked because it has the same solids.
         */
        boolean mark(double cx, double cz, byte[] flags, float[] top, int base, int stride, byte[] nf, float[] nt) {
            double half = 0.3, nh = NARROW;
            int bx0 = (int) Math.floor(cx - half), bx1 = (int) Math.floor(cx + half - 1e-9);
            int bz0 = (int) Math.floor(cz - half), bz1 = (int) Math.floor(cz + half - 1e-9);
            // Solid intervals of the column under the footprint.
            int n = 0, m = 0;
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
                            if (bx < cx + nh && bx + 1 > cx - nh && bz < cz + nh && bz + 1 > cz - nh) {
                                nlo[m] = y;
                                nhi[m] = y + 1;
                                nmagma[m] = true;
                                m++;
                            }
                            continue;
                        }
                        boxes.clear();
                        t.addCollisionBoxes(world, bx, y, bz, all, boxes);
                        for (Aabb a : boxes) {
                            if (a.minX >= cx + half || a.maxX <= cx - half || a.minZ >= cz + half || a.maxZ <= cz - half) continue;
                            if (n == lo.length) break;
                            lo[n] = a.minY;
                            hi[n] = a.maxY;
                            magma[n] = magma(t);
                            n++;
                            if (a.minX >= cx + nh || a.maxX <= cx - nh || a.minZ >= cz + nh || a.maxZ <= cz - nh) continue;
                            nlo[m] = a.minY;
                            nhi[m] = a.maxY;
                            nmagma[m] = magma(t);
                            m++;
                        }
                    }
                }
            }
            gaps(lo, hi, magma, n, flags, top, base, stride);
            if (nf == null || m == n) return false;
            gaps(nlo, nhi, nmagma, m, nf, nt, 0, 1);
            return true;
        }
    }

    /** Free gaps between the merged solid intervals. */
    private void gaps(double[] lo, double[] hi, boolean[] magma, int n, byte[] flags, float[] top, int base, int stride) {
        sortIntervals(lo, hi, magma, n);
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
            gap(flags, top, base, stride, floor, ceil, !floorBad && floor != Double.NEGATIVE_INFINITY);
            if (ceil == Double.POSITIVE_INFINITY) break;
            floor = nextFloor;
            floorBad = nextBad;
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
    private void gap(byte[] flags, float[] top, int base, int stride, double floor, double ceil, boolean standable) {
        double feetLo = Math.max(floor, y0), feetHi = Math.min(ceil - 1.8, y0 + ny);
        if (feetHi < feetLo - 1e-9) return;
        int l0 = (int) Math.floor(feetLo - y0 + 1e-9), l1 = (int) Math.floor(feetHi - y0 + 1e-9);
        for (int l = Math.max(0, l0); l <= Math.min(ny - 1, l1); l++) flags[base + l * stride] |= PASS;
        if (standable && floor >= y0 && floor < y0 + ny) {
            int id = base + (int) Math.floor(floor - y0 + 1e-9) * stride;
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

    /** True when the tick from {@code before} to {@code after} hurts or tramples (D-005, {@link Hazards}). */
    boolean hurts(PlayerState before, PlayerState after) {
        return Hazards.check(world, before, after) != null;
    }
}
