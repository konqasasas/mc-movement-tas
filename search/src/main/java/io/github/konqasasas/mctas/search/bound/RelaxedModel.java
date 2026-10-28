package io.github.konqasasas.mctas.search.bound;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Hotbar;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One tick of 1.12.2 player movement applied to sets of states instead of single states.
 *
 * <p>The vertical state is exact. The horizontal position and velocity are each kept as a convex
 * set ({@link Poly}), and every input the player could give in a tick is folded into one disk of
 * possible accelerations. The successor sets contain every state the real game can reach from any
 * state in the given sets, under any keys and any (even unreachable by mouse) float yaw:
 *
 * <ul>
 *   <li>Each tick adds a horizontal vector of length at most {@code R} to the velocity, where
 *       {@code R} is the sprinting acceleration on the ground (plus the sprint-jump boost on a jump
 *       tick) or in the air. Keys, sprint state, sneaking and jump cooldown only ever pick a vector
 *       inside that disk.</li>
 *   <li>The disk radius includes the worst case of the 1.12.2 sine table, whose sin and cos entries
 *       for the same float angle can be off by up to 0.31% in length ({@link #ROT}).</li>
 *   <li>Velocity components below 0.003 may be zeroed; collisions and sneaking at an edge may clip
 *       the move, which is covered by separate, coarser branches.</li>
 * </ul>
 *
 * <p>The model only accepts worlds of full cubes with slipperiness 0.6. It refuses (throws
 * {@link Unsupported}) when a case it does not cover could happen: a ceiling within reach.
 */
final class RelaxedModel {

    static final class Unsupported extends RuntimeException {
        Unsupported(String m) {
            super(m);
        }
    }

    /** Max of |(sin, cos)| over every float angle, from the 1.12.2 sine table (see {@code SineTableBound}). */
    static final double ROT = 1.0030633;
    private static final double MARGIN = 1 + 1e-6;

    static final double HALF_WIDTH = (double) 0.3F;
    private static final double W = HALF_WIDTH + 1e-9;

    static final double FRICTION_GROUND;
    static final double FRICTION_AIR = (double) 0.91F;
    /** Longest acceleration vector on the ground, sprinting, diagonal input. */
    static final double ACCEL_GROUND;
    /** Longest acceleration vector on the ground while sneaking (sprint is off). */
    static final double ACCEL_SNEAK;
    static final double ACCEL_AIR;
    static final double JUMP_BOOST = (double) 0.2F;

    static {
        float f6 = 0.6F * 0.91F;
        float f7 = 0.16277136F / (f6 * f6 * f6);
        float sprint = (float) (0.10000000149011612D * (1.0D + 0.30000001192092896D));
        float walk = (float) 0.10000000149011612D;
        FRICTION_GROUND = (double) f6;
        ACCEL_GROUND = (double) (sprint * f7) * ROT * MARGIN;
        float sneakInput = (float) (1.0D * 0.3D) * 0.98F;
        ACCEL_SNEAK = (double) (walk * f7) * Math.sqrt(2.0) * sneakInput * ROT * MARGIN;
        float air = (float) ((double) 0.02F + (double) 0.02F * 0.3D);
        ACCEL_AIR = (double) air * ROT * MARGIN;
    }

    /** Footprint rectangle of a group of blocks, in player-centre coordinates. */
    static final class Rect {
        final int id;
        final int top;
        final double x0, x1, z0, z1;

        Rect(int id, int top, double x0, double x1, double z0, double z1) {
            this.id = id;
            this.top = top;
            this.x0 = x0;
            this.x1 = x1;
            this.z0 = z0;
            this.z1 = z1;
        }

        boolean touches(Poly p) {
            return p.intersectsRect(x0, x1, z0, z1);
        }

        boolean holds(Poly p) {
            return p.insideRect(x0, x1, z0, z1);
        }

        @Override
        public String toString() {
            return "rect" + id + "(top " + top + ")";
        }
    }

    static final class Next {
        final VState v;
        final int rect;
        final HState h;
        /** Which case of the move produced this set, for traces. */
        final String how;

        Next(VState v, int rect, HState h, String how) {
            this.v = v;
            this.rect = rect;
            this.h = h;
            this.how = how;
        }
    }

    private final List<int[]> blocks = new ArrayList<int[]>();
    /** Landing surfaces, merged into rectangles per top height. */
    final List<Rect> surfaces = new ArrayList<Rect>();
    private final double voidY;

    RelaxedModel(Problem problem) {
        this.voidY = problem.voidY;
        if (!problem.inventory.equals(Hotbar.EMPTY)) throw new Unsupported("block placement is not supported yet");
        final SparseBlockWorld world = problem.world;
        world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                if (type.isAir()) return;
                if (!fullCube(type)) throw new Unsupported("only full cubes are supported: " + type.name());
                if (type.slipperiness() != 0.6F) throw new Unsupported("slippery blocks are not supported yet: " + type.name());
                if (type.isExcept(BlockType.UNSTABLE)) throw new Unsupported("changing blocks are not supported: " + type.name());
                blocks.add(new int[] {x, y, z});
            }
        });
        buildSurfaces();
    }

    private static boolean fullCube(BlockType type) {
        if (!type.blocksMovement() || type.localBoxes().size() != 1) return false;
        Aabb b = type.localBoxes().get(0);
        return b.minX == 0 && b.minY == 0 && b.minZ == 0 && b.maxX == 1 && b.maxY == 1 && b.maxZ == 1;
    }

    /** Merges the tops of blocks at one height into rectangles: runs along z, then equal runs along x. */
    private void buildSurfaces() {
        Map<Integer, Map<Integer, List<Integer>>> byTop = new TreeMap<Integer, Map<Integer, List<Integer>>>();
        for (int[] b : blocks) {
            Map<Integer, List<Integer>> rows = byTop.get(b[1] + 1);
            if (rows == null) byTop.put(b[1] + 1, rows = new TreeMap<Integer, List<Integer>>());
            List<Integer> zs = rows.get(b[0]);
            if (zs == null) rows.put(b[0], zs = new ArrayList<Integer>());
            zs.add(b[2]);
        }
        for (Map.Entry<Integer, Map<Integer, List<Integer>>> e : byTop.entrySet()) {
            int top = e.getKey();
            List<int[]> runs = new ArrayList<int[]>();
            for (Map.Entry<Integer, List<Integer>> row : e.getValue().entrySet()) {
                List<Integer> zs = row.getValue();
                Collections.sort(zs);
                int start = zs.get(0);
                int prev = start;
                for (int i = 1; i <= zs.size(); i++) {
                    if (i < zs.size() && zs.get(i) == prev + 1) {
                        prev = zs.get(i);
                        continue;
                    }
                    runs.add(new int[] {row.getKey(), row.getKey(), start, prev});
                    if (i < zs.size()) start = prev = zs.get(i);
                }
            }
            List<int[]> merged = new ArrayList<int[]>();
            for (int[] r : runs) {
                boolean joined = false;
                for (int[] m : merged) {
                    if (m[1] + 1 == r[0] && m[2] == r[2] && m[3] == r[3]) {
                        m[1] = r[1];
                        joined = true;
                        break;
                    }
                }
                if (!joined) merged.add(r.clone());
            }
            for (int[] m : merged) {
                surfaces.add(new Rect(surfaces.size(), top, m[0] - W, m[1] + 1 + W, m[2] - W, m[3] + 1 + W));
            }
        }
    }

    /**
     * All successor sets of the states with vertical state {@code v} and horizontal states
     * {@code start}, after one tick. {@code rect} is the surface the player last stood on.
     */
    List<Next> step(VState v, int rect, HState start) {
        List<Next> out = new ArrayList<Next>();
        if (start.isEmpty()) return out;

        // EntityLivingBase.onLivingUpdate: tiny velocities are zeroed before the jump and travel.
        double my = Math.abs(v.motionY) < 0.003D ? 0.0D : v.motionY;
        HState cut = start.cutoff();

        double friction = v.onGround ? FRICTION_GROUND : FRICTION_AIR;
        for (int jump = 0; jump < (v.onGround ? 2 : 1); jump++) {
            double dy = jump == 1 ? (double) 0.42F : my;
            double radius = v.onGround ? ACCEL_GROUND + (jump == 1 ? JUMP_BOOST * ROT * MARGIN : 0) : ACCEL_AIR;

            for (Outcome o : vertical(v, rect, cut, dy)) {
                HState from = o.from;
                HState to = from.move(radius, friction);
                if (!to.isEmpty()) out.add(new Next(o.v, o.rect, to, (jump == 1 ? "jump " : "") + (o.landed ? "land" : "move")));

                // Moves cut short by a block (see blockedBy). Only starts close enough to the
                // block can be blocked by it.
                double reach = from.displacement(radius).maxNorm();
                Poly sweep = from.pos.hull(from.pos.plus(from.displacement(radius)));
                for (Rect r : obstacles(o.v)) {
                    if (!r.touches(sweep)) continue;
                    HState near = from.clipPos(r.x0 - reach, r.x1 + reach, r.z0 - reach, r.z1 + reach);
                    if (near.isEmpty()) continue;
                    Blocked b = blockedBy(r, near, radius, friction);
                    for (HState h : b.ends) out.add(new Next(o.v, o.rect, h, "blocked by " + r));
                    if (v.onGround || o.landed) {
                        Poly nearSweep = near.pos.hull(near.pos.plus(near.displacement(radius)));
                        List<VState> ups = stepUps(v, dy, nearSweep);
                        if (ups.isEmpty()) continue;
                        // The retry moves at the raised height. With nothing there to block it, it
                        // ends at the wanted end of a move that the block stopped at the normal height.
                        List<HState> retry = raisedObstacle(v, nearSweep) ? b.coarse : b.wanted;
                        for (VState sv : ups) {
                            for (HState h : retry) out.add(new Next(sv, o.rect, h, "step-up near " + r));
                        }
                    }
                }
                if (v.onGround) {
                    // Sneaking at an edge shortens the move but keeps the velocity.
                    HState sneak = from.move(ACCEL_SNEAK, friction);
                    if (!sneak.isEmpty() && !supported(v.minY, sneak.pos)) {
                        // Entity.move cuts the move until the box lowered by the step height
                        // touches a block, or down to nothing on both axes: the end is over a
                        // block or it is the start.
                        HState box = HState.shortened(from, sneak);
                        for (Rect r : surfaces) {
                            if (r.top <= v.minY - 0.6D || r.top - 1 >= v.maxY) continue;
                            HState piece = box.clipPos(r.x0, r.x1, r.z0, r.z1);
                            if (!piece.isEmpty()) out.add(new Next(o.v, o.rect, piece, "sneak at edge over " + r));
                        }
                        HState stay = HState.stayed(from, sneak);
                        if (!stay.isEmpty()) out.add(new Next(o.v, o.rect, stay, "sneak at edge, no move"));
                    }
                }
            }
        }
        return withHeights(out);
    }

    /**
     * {@code EntityPlayer.updateSize} at the end of the tick: sneaking sets the box height to 1.65,
     * not sneaking to 1.8. A change rebuilds the box from its bottom ({@code minY + height}); no
     * change keeps the top the move left. Either can follow any move, so both are listed.
     */
    private static List<Next> withHeights(List<Next> in) {
        List<Next> out = new ArrayList<Next>(in.size() * 2);
        for (Next n : in) {
            out.add(n);
            boolean tall = n.v.maxY - n.v.minY > 1.7;
            double h = (double) (tall ? 1.65F : 1.8F);
            VState alt = new VState(n.v.minY, n.v.minY + h, n.v.motionY, n.v.onGround);
            out.add(new Next(alt, n.rect, n.h, n.how + (tall ? ", sneak" : ", unsneak")));
        }
        return out;
    }

    private static final double FACE = 1e-6;

    private static final class Blocked {
        /** Where the blocked moves end. */
        final List<HState> ends = new ArrayList<HState>();
        /** The wanted ends (and velocities) of those moves, as if nothing had blocked them. */
        final List<HState> wanted = new ArrayList<HState>();
        /** A coarse cover of every blocked move, for cases not worked out exactly. */
        final List<HState> coarse = new ArrayList<HState>();
    }

    /**
     * Moves from {@code near} that the block group {@code r} stops. {@code Entity.move} resolves x
     * before z, each against the box moved so far, and a block stops an axis by leaving the box
     * touching its face. So, for a move the block stops:
     * <ul>
     *   <li>on x: the start is level with the block in z, the wanted x end is inside the block's x
     *       range, and the move ends with x at the face it came from and x velocity 0; z moves
     *       freely (or is stopped too, see the last case);</li>
     *   <li>on z, with x free: the wanted end is inside the block, and the move ends with z at the
     *       face and z velocity 0;</li>
     *   <li>on both axes (possibly by different blocks): anywhere in the box spanned by the start
     *       and the wanted end, at rest.</li>
     * </ul>
     * Positions are in player-centre coordinates, where the block group is {@code r}.
     */
    private static Blocked blockedBy(Rect r, HState near, double radius, double friction) {
        Blocked b = new Blocked();
        double inf = Double.POSITIVE_INFINITY;
        // x stopped at the -x face (moving +x) or the +x face (moving -x).
        for (int side = 0; side < 2; side++) {
            double face = side == 0 ? r.x0 : r.x1;
            HState start = near.clipPos(side == 0 ? -inf : r.x1, side == 0 ? r.x0 : inf, r.z0, r.z1);
            HState want = start.move(radius, friction).clipPos(r.x0, r.x1, -inf, inf);
            if (want.isEmpty()) continue;
            b.wanted.add(want);
            add(b.ends, want.pinX(face - FACE, face + FACE));
        }
        // z stopped at the -z face or the +z face, x free.
        for (int side = 0; side < 2; side++) {
            double face = side == 0 ? r.z0 : r.z1;
            HState start = near.clipPos(-inf, inf, side == 0 ? -inf : r.z1, side == 0 ? r.z0 : inf);
            HState want = start.move(radius, friction).clipPos(r.x0, r.x1, r.z0, r.z1);
            if (want.isEmpty()) continue;
            b.wanted.add(want);
            add(b.ends, want.pinZ(face - FACE, face + FACE));
        }
        HState moved = near.move(radius, friction);
        List<HState> all = HState.blocked(near, moved);
        if (!all.isEmpty()) b.ends.add(all.get(all.size() - 1));
        b.coarse.addAll(all);
        if (!moved.isEmpty()) b.coarse.add(moved);
        return b;
    }

    private static void add(List<HState> out, HState h) {
        if (!h.isEmpty()) out.add(h);
    }

    /** True if a block overlaps the body raised by 0.6 (the step-up retry) near the swept positions. */
    private boolean raisedObstacle(VState v, Poly sweep) {
        double rise = (double) 0.6F;
        for (Rect s : surfaces) {
            if ((double) (s.top - 1) < v.maxY + rise && (double) s.top > v.minY + rise && s.touches(sweep)) return true;
        }
        return false;
    }

    private static final class Outcome {
        final VState v;
        final int rect;
        final HState from;
        final boolean landed;

        Outcome(VState v, int rect, HState from, boolean landed) {
            this.v = v;
            this.rect = rect;
            this.from = from;
            this.landed = landed;
        }
    }

    /**
     * The vertical half of {@code Entity.move}: the y offset is resolved first, against blocks under
     * or over the box at its position before the move.
     */
    private List<Outcome> vertical(VState v, int rect, HState start, double dy) {
        List<Outcome> out = new ArrayList<Outcome>();
        boolean freeFall = true;
        if (dy < 0) {
            for (Rect s : surfaces) {
                double d = (double) s.top - v.minY;
                if (!(v.minY >= (double) s.top && d > dy)) continue;
                if (!s.touches(start.pos)) continue;
                double nMin = v.minY + d;
                double nMax = v.maxY + d;
                VState n = new VState(nMin, nMax, (0.0D - 0.08D) * 0.9800000190734863D, true);
                HState on = start.clipPos(s.x0, s.x1, s.z0, s.z1);
                if (on.isEmpty()) continue;
                out.add(new Outcome(n, s.id, on, true));
                if (s.holds(start.pos)) freeFall = false;
            }
        } else if (dy > 0) {
            for (int[] b : blocks) {
                double bottom = (double) b[1];
                if (bottom >= v.maxY && bottom - v.maxY < dy && footprint(b).touches(start.pos)) {
                    throw new Unsupported("a ceiling is within reach at block " + b[0] + "," + b[1] + "," + b[2]);
                }
            }
        }
        if (freeFall) {
            double nMin = v.minY + dy;
            if (nMin >= voidY) {
                VState n = new VState(nMin, v.maxY + dy, (dy - 0.08D) * 0.9800000190734863D, false);
                out.add(new Outcome(n, rect, start, false));
            }
        }
        return out;
    }

    private Rect footprint(int[] b) {
        return new Rect(-1, b[1] + 1, b[0] - W, b[0] + 1 + W, b[2] - W, b[2] + 1 + W);
    }

    /** The block groups that overlap the body after the vertical move, so can block a sideways move. */
    private List<Rect> obstacles(VState n) {
        List<Rect> out = new ArrayList<Rect>();
        for (Rect s : surfaces) {
            if ((double) (s.top - 1) < n.maxY && (double) s.top > n.minY) out.add(s);
        }
        return out;
    }

    /**
     * The vertical results of the step-up retry in {@code Entity.move}. When a move on the ground (or
     * one that lands) is blocked sideways, the game retries it from the box before the move raised by
     * 0.6, then lowers the box by 0.6 onto whatever is under it, and keeps the retry if it got
     * further sideways. This is how a player who walks off an edge can still step back onto it, and
     * how a fast fall can end on top of a block next to the landing spot. The sideways result of the
     * retry is a clipped version of the wanted move, so it is covered by the collision branch; only
     * the new vertical state needs to be listed.
     */
    private List<VState> stepUps(VState v, double dy, Poly sweep) {
        double rise = (double) 0.6F;
        for (int[] b : blocks) {
            double bottom = (double) b[1];
            if (bottom >= v.maxY && bottom - v.maxY < rise && footprint(b).touches(sweep)) {
                throw new Unsupported("a ceiling is within reach of a step-up at block " + b[0] + "," + b[1] + "," + b[2]);
            }
        }
        double raisedMin = v.minY + rise;
        double raisedMax = v.maxY + rise;
        List<Double> drops = new ArrayList<Double>();
        drops.add(-rise);
        for (Rect s : surfaces) {
            double top = (double) s.top;
            // Only blocks that intersect the start box stretched by the move are in the list, so a
            // top level with the feet is not one of them.
            if (top > v.minY && raisedMin >= top && top - raisedMin > -rise && s.touches(sweep)) {
                drops.add(top - raisedMin);
            }
        }
        List<VState> out = new ArrayList<VState>();
        for (double drop : drops) {
            boolean collided = dy != drop;
            double my = collided ? 0.0D : dy;
            out.add(new VState(raisedMin + drop, raisedMax + drop, (my - 0.08D) * 0.9800000190734863D, collided && dy < 0));
        }
        return out;
    }

    /** True if every position in {@code p} stands over a block with its top at {@code y}. */
    private boolean supported(double y, Poly p) {
        for (Rect s : surfaces) if ((double) s.top == y && s.holds(p)) return true;
        return false;
    }
}
