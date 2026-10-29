package io.github.konqasasas.mctas.funnel;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The landings the backward search verifies: one per block top the player can stand on, at the
 * centre of the block's top boxes, standing still.
 *
 * <p>An anchor is verified once a beam from standing still on it reached the goal, or a landing on
 * an anchor verified before it; its time {@link Anchor#v} is that beam's ticks plus the time of the
 * anchor it landed on. A landing on any part of the block top counts as landing on the anchor.
 */
final class Anchors {
    static final double ARRIVAL = 0.30;
    static final int MAX_ARRIVALS = 6;
    static final class Anchor {
        final int id, bx, by, bz;
        final double top, cx, cz;
        /** The block's top boxes grown by the player's half width: where the feet point stands on it. */
        final double minX, minZ, maxX, maxZ;
        /** Stand cells of the grid on this block top. */
        final List<Integer> cells = new ArrayList<Integer>();
        /** Verified ticks to the goal from standing still at the centre; infinite until verified. */
        volatile float v = Field.INF;
        /** The verified beam's inputs and the anchor it lands on (-1 for the goal). */
        List<PlayerInput> witness;
        int via = -2;
        int attempts;
        /** Verified only when arriving at speed ({@link #arriving}). */
        boolean moving;
        /** Real landings on it at speed, from other anchors' beams: roots for its beam at speed. */
        final List<Beam.Node> arrivals = new ArrayList<Beam.Node>();
        /** Its time is a guess past a stretch no beam got across ({@link FunnelSolver}'s bridge), not a beam's. */
        boolean provisional;
        /** An anchor near it was verified since its last attempt. */
        boolean retry;

        Anchor(int id, int bx, int by, int bz, double top, double cx, double cz, double minX, double minZ, double maxX, double maxZ) {
            this.id = id;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.top = top;
            this.cx = cx;
            this.cz = cz;
            this.minX = minX - 0.3;
            this.minZ = minZ - 0.3;
            this.maxX = maxX + 0.3;
            this.maxZ = maxZ + 0.3;
        }

        /** Keeps the fastest few distinct landings. */
        synchronized void offer(Beam.Node n) {
            PlayerState s = n.s;
            double sp = Beam.speed(s);
            for (int i = 0; i < arrivals.size(); i++) {
                PlayerState o = arrivals.get(i).s;
                if (Math.abs(o.posX - s.posX) < 0.15 && Math.abs(o.posZ - s.posZ) < 0.15 && Math.abs(o.motionX - s.motionX) < 0.03
                    && Math.abs(o.motionZ - s.motionZ) < 0.03) {
                    if (sp > Beam.speed(o)) arrivals.set(i, n);
                    return;
                }
            }
            arrivals.add(n);
            if (arrivals.size() > MAX_ARRIVALS) {
                int slow = 0;
                for (int i = 1; i < arrivals.size(); i++) if (Beam.speed(arrivals.get(i).s) < Beam.speed(arrivals.get(slow).s)) slow = i;
                arrivals.remove(slow);
            }
        }

        synchronized List<Beam.Node> arrivals() {
            return new ArrayList<Beam.Node>(arrivals);
        }

        boolean verified() {
            return v < Field.INF;
        }

        boolean holds(double x, double y, double z) {
            return Math.abs(y - top) < 1e-6 && x >= minX - 1e-7 && x <= maxX + 1e-7 && z >= minZ - 1e-7 && z <= maxZ + 1e-7;
        }

        PlayerState settled(float yaw) {
            return PlayerState.standing(cx, top, cz, yaw);
        }

        /**
         * Arriving at speed: sprinting along {@code yaw} at {@link #ARRIVAL} blocks per tick, as after
         * a sprint jump's landing, at the back of the top (along {@code yaw}) so that the run on it is
         * as long as it can be. For anchors that only a jump carrying the speed of the one before
         * leaves (a 1x1 block before a long gap).
         */
        PlayerState arriving(float yaw) {
            double r = Math.toRadians(yaw);
            double dx = -Math.sin(r), dz = Math.cos(r);
            // From the centre back along the heading to the edge of the feet area, less a margin.
            double t = Double.POSITIVE_INFINITY;
            if (Math.abs(dx) > 1e-9) t = Math.min(t, ((dx > 0 ? cx - minX : maxX - cx)) / Math.abs(dx));
            if (Math.abs(dz) > 1e-9) t = Math.min(t, ((dz > 0 ? cz - minZ : maxZ - cz)) / Math.abs(dz));
            t = Math.max(0, t - 0.05);
            PlayerState s = PlayerState.standing(cx - dx * t, top, cz - dz * t, yaw);
            s.motionX = dx * ARRIVAL;
            s.motionZ = dz * ARRIVAL;
            s.sprinting = true;
            return s;
        }

        @Override
        public String toString() {
            return "(" + bx + "," + by + "," + bz + ")";
        }
    }

    final List<Anchor> list = new ArrayList<Anchor>();
    private final Map<Long, Anchor> byBlock = new HashMap<Long, Anchor>();

    private final BlockWorld world;

    Anchors(final Grid g) {
        world = g.world;
        final Aabb all = new Aabb(-3.0E7, -64, -3.0E7, 3.0E7, 512, 3.0E7);
        final List<Aabb> boxes = new ArrayList<Aabb>();
        final List<int[]> blocks = new ArrayList<int[]>();
        g.problem.world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                blocks.add(new int[] {x, y, z});
            }
        });
        // A stable order (the sparse world iterates a hash map).
        blocks.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[2], b[2]));
        for (int[] p : blocks) {
            int x = p[0], y = p[1], z = p[2];
            BlockType type = g.world.getBlock(x, y, z);
            // Slime sends a landing back up by its fall, so a landing there has no time of its own.
            if (Grid.hazard(type) || Grid.magma(type) || Grid.slime(type)) continue;
            boxes.clear();
            type.addCollisionBoxes(g.world, x, y, z, all, boxes);
            if (boxes.isEmpty()) continue;
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
            double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
            if (!free(g, cx, top, cz)) continue;
            Anchor a = new Anchor(list.size(), x, y, z, top, cx, cz, minX, minZ, maxX, maxZ);
            int l = (int) Math.floor(top - g.y0 + 1e-9);
            if (l < 0 || l >= g.ny) continue;
            int ix0 = Math.max(0, (int) Math.ceil((a.minX - g.x0) / Grid.RES - 1e-9));
            int ix1 = Math.min(g.nx - 1, (int) Math.floor((a.maxX - g.x0) / Grid.RES + 1e-9));
            int iz0 = Math.max(0, (int) Math.ceil((a.minZ - g.z0) / Grid.RES - 1e-9));
            int iz1 = Math.min(g.nz - 1, (int) Math.floor((a.maxZ - g.z0) / Grid.RES + 1e-9));
            for (int ix = ix0; ix <= ix1; ix++) {
                for (int iz = iz0; iz <= iz1; iz++) {
                    int id = g.id(l, ix, iz);
                    if ((g.flags[id] & Grid.STAND) != 0 && Math.abs(g.top[id] - top) < 1e-4) a.cells.add(id);
                }
            }
            if (a.cells.isEmpty()) {
                int id = g.cellOf(cx, top, cz);
                if (id < 0) continue;
                a.cells.add(id);
            }
            list.add(a);
            byBlock.put(key(x, y, z), a);
        }
    }

    /** True when the player's box fits standing at the point. */
    private static boolean free(Grid g, double x, double y, double z) {
        Aabb body = new Aabb(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        List<Aabb> boxes = new ArrayList<Aabb>();
        Aabb all = new Aabb(-3.0E7, -64, -3.0E7, 3.0E7, 512, 3.0E7);
        for (int bx = (int) Math.floor(body.minX); bx <= (int) Math.floor(body.maxX); bx++) {
            for (int by = (int) Math.floor(body.minY) - 1; by <= (int) Math.floor(body.maxY); by++) {
                for (int bz = (int) Math.floor(body.minZ); bz <= (int) Math.floor(body.maxZ); bz++) {
                    BlockType t = g.world.getBlock(bx, by, bz);
                    if (t.isAir()) continue;
                    if (Grid.hazard(t)) return false;
                    boxes.clear();
                    t.addCollisionBoxes(g.world, bx, by, bz, all, boxes);
                    for (Aabb b : boxes) {
                        if (b.minX < body.maxX - 1e-7 && b.maxX > body.minX + 1e-7 && b.minY < body.maxY - 1e-7
                            && b.maxY > body.minY + 1e-7 && b.minZ < body.maxZ - 1e-7 && b.maxZ > body.minZ + 1e-7) return false;
                    }
                }
            }
        }
        return true;
    }

    private static long key(int x, int y, int z) {
        long h = (long) x * 0x9E3779B97F4A7C15L ^ (long) y * 0xC2B2AE3D27D4EB4FL ^ (long) z * 0x165667B19E3779F9L;
        return h ^ (h >>> 31);
    }

    /**
     * The anchor whose block top holds a standing player at this feet point with the lowest time
     * (only those verified at rest with {@code restOnly}), or null.
     */
    Anchor under(PlayerState s, boolean restOnly) {
        if (!s.onGround) return null;
        // The game lands the player on the block under the middle of the feet: on slime, the player
        // bounces even with the feet on the block beside it.
        if (Grid.slime(world.getBlock((int) Math.floor(s.posX), (int) Math.floor(s.posY - 0.2), (int) Math.floor(s.posZ)))) return null;
        Anchor best = null;
        int x0 = (int) Math.floor(s.posX - 0.3), x1 = (int) Math.floor(s.posX + 0.3);
        int z0 = (int) Math.floor(s.posZ - 0.3), z1 = (int) Math.floor(s.posZ + 0.3);
        int yb = (int) Math.floor(s.posY - 1e-6);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = yb - 1; y <= yb; y++) {
                    Anchor a = byBlock.get(key(x, y, z));
                    if (a == null || !a.holds(s.posX, s.posY, s.posZ) || restOnly && a.moving) continue;
                    if (best == null || a.v < best.v) best = a;
                }
            }
        }
        return best;
    }
}
