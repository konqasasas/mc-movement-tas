package io.github.konqasasas.mctas.funnel;

import java.util.Arrays;
import java.util.List;

/**
 * Ticks to go, roughly, from every cell of a {@link Grid}: Dijkstra over optimistic moves, either
 * backward to a set of seeds with known times (the goal, and the landings the backward search has
 * verified) or forward from the start.
 *
 * <p>The moves are: across to a neighbouring cell of the same level (at {@link #SPEED} blocks per
 * tick), down one level (a fall), up from a stand as far as a jump rises, up a ladder or through
 * water, and a step up of at most 0.6 onto a neighbouring stand. In the air the player may cross only
 * the cells the grid marks as reachable by a jump. Momentum is ignored, so the field guides the beams
 * ({@link Beam}) and orders the backward search; it bounds nothing.
 *
 * <p>Seeds only ever lower the values, so new seeds continue the same Dijkstra.
 */
final class Field {
    static final double SPEED = 0.33;
    static final float RISE = 3.0F, DROP = 1.5F, LADDER = 8.5F, SWIM = 6.0F;
    static final float INF = Float.POSITIVE_INFINITY;
    /**
     * Levels a rise from a stand may cover: a jump. Slime sends a fall back up as high as it came
     * from, which the cells do not know: the flight's bounce is {@link Landing}'s.
     */
    private static final int RISE_LEVELS = (int) Math.ceil(Grid.JUMP_RISE) + 1;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final float STRAIGHT = (float) (Grid.RES / SPEED), DIAGONAL = (float) (Grid.RES * Math.sqrt(2) / SPEED);

    final Grid g;
    /** True: values are times from the seeds along the moves (from the start); false: times to the seeds. */
    final boolean forward;
    final float[] d;
    /** The next cell towards the seeds (backward fields), or -1. */
    final int[] next;
    /** The value of the seed each cell's value comes from (backward fields). */
    final float[] seedValue;
    /** Cells the field may use, or null for all (a backward field keeps to the cells the start reaches). */
    private final Field within;
    /** Blocks of flight left at each cell ({@link #AIR_RUN}): how much further the way may go across the air. */
    private final float[] left;
    private long[] heap = new long[1024];
    private int heapSize;

    Field(Grid g, boolean forward) {
        this(g, forward, null);
    }

    /** A field over the cells where {@code within} has a value (null: all cells). */
    Field(Grid g, boolean forward, Field within) {
        this.g = g;
        this.forward = forward;
        this.within = within;
        d = new float[g.flags.length];
        Arrays.fill(d, INF);
        next = forward ? null : new int[g.flags.length];
        if (next != null) Arrays.fill(next, -1);
        seedValue = forward ? null : new float[g.flags.length];
        left = AIR_RUN > 0 ? new float[g.flags.length] : null;
    }

    float value(int id) {
        return id < 0 ? INF : d[id];
    }

    /** Seeds cells with a time (kept if lower than what they have) and runs Dijkstra to the end. */
    void seed(List<Integer> cells, float v) {
        for (int id : cells) {
            if (v < d[id] && (within == null || within.d[id] < INF)) {
                d[id] = v;
                if (next != null) {
                    next[id] = -1;
                    seedValue[id] = v;
                }
                if (left != null) left[id] = AIR_RUN;
                push(v, id);
            }
        }
        run();
    }

    private void run() {
        while (heapSize > 0) {
            long top = pop();
            float v = Float.intBitsToFloat((int) (top >>> 32));
            int id = (int) top;
            if (v > d[id]) continue;
            if (forward) out(id, v);
            else in(id, v);
        }
    }

    private void offer(int id, float v, int via) {
        offer(id, v, via, AIR_RUN);
    }

    /**
     * Across the air a flight goes only so far: {@link #AIR_RUN} blocks from the stand (ladder, water)
     * it leaves or lands on, and {@link #AIR_DROP} more for every level it comes down. Without the
     * limit, the air of stands next to each other joins into a way that floats at one height.
     */
    static final float AIR_RUN = System.getenv("FUNNEL_AIRRUN") != null ? Float.parseFloat(System.getenv("FUNNEL_AIRRUN")) : 0;
    static final float AIR_DROP = System.getenv("FUNNEL_AIRDROP") != null ? Float.parseFloat(System.getenv("FUNNEL_AIRDROP")) : 1.0F;

    private boolean held(int id) {
        return (g.flags[id] & (Grid.STAND | Grid.CLIMB | Grid.WATER)) != 0;
    }

    /** The flight left after a move from {@code from} that adds {@code b} to it (negative: across). */
    private float flight(int from, float b) {
        return left == null ? AIR_RUN : (held(from) ? AIR_RUN : left[from]) + b;
    }

    private void offer(int id, float v, int via, float b) {
        if (left != null) {
            if (held(id)) b = AIR_RUN;
            else if (b < 0) return;
        }
        if (v < d[id] && (within == null || within.d[id] < INF)) {
            if (left != null) left[id] = b;
            d[id] = v;
            if (next != null) {
                next[id] = via;
                seedValue[id] = seedValue[via];
            }
            push(v, id);
        }
    }

    /** How much of a cell's value is the moves to its seed (backward fields); infinite without a value. */
    float gap(int id) {
        return id < 0 || d[id] == INF ? INF : d[id] - seedValue[id];
    }

    /**
     * Slot cells ({@link Grid#SLOT}: the player fits only off the centre) cost as much again: a way
     * through a slot when there is no other, not a short cut along every wall.
     */
    static final float SLOT_COST = STRAIGHT;

    private float slot(int a, int b) {
        return ((g.flags[a] | g.flags[b]) & Grid.SLOT) != 0 ? SLOT_COST : 0;
    }

    private boolean movable(int id) {
        return (g.flags[id] & (Grid.STAND | Grid.AIR | Grid.CLIMB | Grid.WATER)) != 0;
    }

    /** Backward: relaxes every cell with a move into {@code v}. */
    private void in(int v, float dv) {
        byte[] f = g.flags;
        int l = g.level(v), ix = g.ix(v), iz = g.iz(v);
        if (movable(v)) {
            for (int k = 0; k < 8; k++) {
                int jx = ix + DX[k], jz = iz + DZ[k];
                if (!g.in(l, jx, jz)) continue;
                int u = g.id(l, jx, jz);
                if (!movable(u)) continue;
                if (k >= 4 && ((f[g.id(l, jx, iz)] & Grid.PASS) == 0 || (f[g.id(l, ix, jz)] & Grid.PASS) == 0)) continue;
                offer(u, dv + (k < 4 ? STRAIGHT : DIAGONAL) + slot(u, v), v, flight(v, (float) -(k < 4 ? Grid.RES : Grid.RES * Math.sqrt(2))));
            }
        }
        // A fall from the cell above.
        if (l + 1 < g.ny) {
            int u = g.id(l + 1, ix, iz);
            if (movable(u)) offer(u, dv + DROP, v, flight(v, AIR_DROP));
        }
        // A jump from a stand below, a climb or a swim up.
        for (int k = 1; k <= RISE_LEVELS && l - k >= 0; k++) {
            int u = g.id(l - k, ix, iz);
            if ((f[u] & Grid.PASS) == 0) break;
            if ((f[u] & Grid.STAND) != 0 && g.y0 + l <= g.top[u] + Grid.JUMP_RISE + 1e-6) offer(u, dv + RISE * k, v);
            if (k == 1 && (f[u] & Grid.CLIMB) != 0) offer(u, dv + LADDER, v);
            if (k == 1 && (f[u] & Grid.WATER) != 0) offer(u, dv + SWIM, v);
        }
        // A step up onto this stand from a neighbour stand one level below.
        if ((f[v] & Grid.STAND) != 0 && l > 0) {
            for (int k = 0; k < 8; k++) {
                int jx = ix + DX[k], jz = iz + DZ[k];
                if (!g.in(l - 1, jx, jz)) continue;
                int u = g.id(l - 1, jx, jz);
                if ((f[u] & Grid.STAND) != 0 && g.top[u] >= g.top[v] - 0.6 - 1e-6) offer(u, dv + (k < 4 ? STRAIGHT : DIAGONAL) + slot(u, v), v);
            }
        }
    }

    /** Forward: relaxes every cell {@code u} moves into. */
    private void out(int u, float du) {
        byte[] f = g.flags;
        int l = g.level(u), ix = g.ix(u), iz = g.iz(u);
        if (movable(u)) {
            for (int k = 0; k < 8; k++) {
                int jx = ix + DX[k], jz = iz + DZ[k];
                if (!g.in(l, jx, jz)) continue;
                int v = g.id(l, jx, jz);
                if (!movable(v)) continue;
                if (k >= 4 && ((f[g.id(l, jx, iz)] & Grid.PASS) == 0 || (f[g.id(l, ix, jz)] & Grid.PASS) == 0)) continue;
                offer(v, du + (k < 4 ? STRAIGHT : DIAGONAL) + slot(u, v), u, flight(u, (float) -(k < 4 ? Grid.RES : Grid.RES * Math.sqrt(2))));
            }
            if (l > 0) {
                int v = g.id(l - 1, ix, iz);
                if (movable(v)) offer(v, du + DROP, u, flight(u, AIR_DROP));
            }
        }
        for (int k = 1; k <= RISE_LEVELS && l + k < g.ny; k++) {
            int v = g.id(l + k, ix, iz);
            if ((f[v] & Grid.PASS) == 0) break;
            if ((f[u] & Grid.STAND) != 0 && g.y0 + l + k <= g.top[u] + Grid.JUMP_RISE + 1e-6) offer(v, du + RISE * k, u);
            if (k == 1 && (f[u] & Grid.CLIMB) != 0) offer(v, du + LADDER, u);
            if (k == 1 && (f[u] & Grid.WATER) != 0) offer(v, du + SWIM, u);
        }
        if ((f[u] & Grid.STAND) != 0 && l + 1 < g.ny) {
            for (int k = 0; k < 8; k++) {
                int jx = ix + DX[k], jz = iz + DZ[k];
                if (!g.in(l + 1, jx, jz)) continue;
                int v = g.id(l + 1, jx, jz);
                if ((f[v] & Grid.STAND) != 0 && g.top[v] <= g.top[u] + 0.6 + 1e-6) offer(v, du + (k < 4 ? STRAIGHT : DIAGONAL) + slot(u, v), u);
            }
        }
    }

    /**
     * The value at a feet point: its cell, or failing that the best neighbour in its level (and the
     * level below, for feet exactly on a block top) plus the step to it.
     */
    float at(double x, double y, double z) {
        int id = g.cellOf(x, y, z);
        if (id < 0) return INF;
        float v = d[id];
        if (v < INF) return v;
        int l = g.level(id), ix = g.ix(id), iz = g.iz(id);
        float best = INF;
        for (int dl = 0; dl >= -1; dl--) {
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (!g.in(l + dl, ix + a, iz + b)) continue;
                    float w = d[g.id(l + dl, ix + a, iz + b)];
                    if (w < INF) best = Math.min(best, w + DIAGONAL + (dl != 0 ? DROP : 0));
                }
            }
        }
        return best;
    }

    /**
     * The yaw towards the seeds from a feet point, following {@link #next} about a block along the
     * moves; NaN when the point has no value.
     */
    float heading(double x, double y, double z) {
        double[] t = aim(x, y, z, 1.2);
        if (t == null) return Float.NaN;
        double dx = t[0] - x, dz = t[1] - z;
        if (dx * dx + dz * dz < 1e-6) return Float.NaN;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** The centre of the cell about {@code reach} blocks along the moves towards the seeds, or null. */
    double[] aim(double x, double y, double z, double reach) {
        int id = g.cellOf(x, y, z);
        if (id < 0 || next == null) return null;
        if (d[id] == INF) {
            int l = g.level(id), ix = g.ix(id), iz = g.iz(id);
            float best = INF;
            int bi = -1;
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (!g.in(l, ix + a, iz + b)) continue;
                    int j = g.id(l, ix + a, iz + b);
                    if (d[j] < best) {
                        best = d[j];
                        bi = j;
                    }
                }
            }
            if (bi < 0) return null;
            id = bi;
        }
        double[] t = g.fit(id);
        int cur = id;
        for (int step = 0; step < 24; step++) {
            int n = next[cur];
            if (n < 0) break;
            cur = n;
            t = g.fit(cur);
            if ((t[0] - x) * (t[0] - x) + (t[1] - z) * (t[1] - z) >= reach * reach) break;
        }
        return t;
    }

    private void push(float v, int id) {
        if (heapSize == heap.length) heap = Arrays.copyOf(heap, heap.length * 2);
        long k = ((long) Float.floatToIntBits(v) << 32) | (id & 0xFFFFFFFFL);
        int i = heapSize++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heap[p] <= k) break;
            heap[i] = heap[p];
            i = p;
        }
        heap[i] = k;
    }

    private long pop() {
        long top = heap[0];
        long last = heap[--heapSize];
        int i = 0;
        while (true) {
            int c = 2 * i + 1;
            if (c >= heapSize) break;
            if (c + 1 < heapSize && heap[c + 1] < heap[c]) c++;
            if (heap[c] >= last) break;
            heap[i] = heap[c];
            i = c;
        }
        if (heapSize > 0) heap[i] = last;
        return top;
    }
}
