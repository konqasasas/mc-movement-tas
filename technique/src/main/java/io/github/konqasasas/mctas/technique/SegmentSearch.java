package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds fast input sequences through one local obstacle (a step, a ceiling, a corner) with the exact
 * physics: a tick-by-tick beam from one entry state to an {@link Exit}.
 *
 * <p>The beam keeps the states that are furthest along the heading (plus a few ticks of their
 * velocity), but at most {@link Options#bucketCap} per bucket of (standing, feet height in 1/8
 * blocks, sideways offset in 1/4 blocks), so states that are behind but higher, or off to the side,
 * survive long enough to use a step or a corner. States that are equal after rounding (position to
 * 1e-4, velocity to 1e-5) are merged. Sprint and sneak changes keep the 3-tick gap (D-004); states
 * with {@code unsupportedAction} (D-010, D-011) are dropped.
 *
 * <p>The search runs until {@link Options#extraTicks} ticks after the first exit, so it returns the
 * fastest exits and some slightly slower ones that may leave faster or better placed.
 */
public final class SegmentSearch {
    /** Toggle gap of the judge ({@code ToggleRule.GAP}). */
    public static final int TOGGLE_GAP = 3;

    public static final class Options {
        public int width = 16000;
        public int bucketCap = 300;
        /** States every bucket keeps before the rest of the beam is filled by score. */
        public int bucketMin = 8;
        public int maxTicks = 60;
        public int extraTicks = 2;
        /** Ticks of velocity added to the distance along the heading when ranking. */
        public double lookahead = 3;
        public float[] offsets = Actions.DEFAULT_OFFSETS;
        public boolean sneak = false;
        public double voidY = -64;
        /** Results kept, fastest first. */
        public int results = 32;
        /** For debugging: inputs whose states are tracked through the beam ({@link #guideLost}). */
        public List<PlayerInput> guide;
        /**
         * Merge states by coarse cells (along 0.05, side 0.1, height 0.05, speed along 0.01, vertical speed
         * 0.04) instead of rounding, keeping the furthest state of each cell. Keeps the beam diverse.
         */
        public boolean cells = true;
        /** {@code System.nanoTime()} after which the search stops and returns what it has; 0 for none. */
        public long deadlineNanos;
        /**
         * Inputs tried every tick on top of the {@link Actions} around the heading, or null. A caller that
         * rebuilds part of a known run passes that part's own inputs (sneaking, walking, turning), so the
         * beam can do what the run did even where the default actions cannot.
         */
        public List<PlayerInput> extraActions;

        /**
         * A light setting for planners that call many segments: 5 directions (0, +-10, +-45 degrees), a
         * beam of 200. About a fifth of a second for a 40-tick segment; {@link Technique#solve} runs it
         * once and stops.
         */
        public static Options fast() {
            Options o = new Options();
            o.width = 200;
            o.bucketCap = 40;
            o.offsets = new float[] {0, 10, -10, 45, -45};
            return o;
        }
    }

    /** With a guide: the first tick whose guide state the beam dropped, or -1. */
    public int guideLost = -1;

    /** One way through: the inputs from the entry state and the state at the exit tick. */
    public static final class Result {
        public final List<PlayerInput> inputs;
        public final PlayerState end;
        /** Tick of the last sprint or sneak change, counted from the entry state (tick 0). */
        public final int lastToggle;

        Result(List<PlayerInput> inputs, PlayerState end, int lastToggle) {
            this.inputs = inputs;
            this.end = end;
            this.lastToggle = lastToggle;
        }

        public int ticks() {
            return inputs.size();
        }

        /** Speed along the heading at the exit, in blocks per tick. */
        public double speedAlong(float headingYaw) {
            double r = Math.toRadians(headingYaw);
            return -Math.sin(r) * end.motionX + Math.cos(r) * end.motionZ;
        }
    }

    /** Statistics of the last run. */
    public long expanded;
    /** True when the last run stopped at {@link Options#deadlineNanos}. */
    public boolean timedOut;

    private static final class Layer {
        PlayerState[] states;
        int[] parent;
        PlayerInput[] input;
        int[] lastToggle;
        int size;

        Layer(int n) {
            states = new PlayerState[n];
            parent = new int[n];
            input = new PlayerInput[n];
            lastToggle = new int[n];
        }

        void add(PlayerState s, int p, PlayerInput a, int lt) {
            states[size] = s;
            parent[size] = p;
            input[size] = a;
            lastToggle[size] = lt;
            size++;
        }
    }

    private static final class Cand {
        final PlayerState s;
        final int parent;
        final PlayerInput a;
        final int lastToggle;
        final double score;
        final long bucket;

        Cand(PlayerState s, int parent, PlayerInput a, int lastToggle, double score, long bucket) {
            this.s = s;
            this.parent = parent;
            this.a = a;
            this.lastToggle = lastToggle;
            this.score = score;
            this.bucket = bucket;
        }
    }

    /**
     * Searches from {@code start}, whose last sprint or sneak change was at tick {@code lastToggle}
     * (0 is the entry state, use a large negative value for "long ago"). Results are sorted by exit
     * tick, then by speed along the heading.
     */
    public List<Result> search(BlockWorld world, PlayerState start, int lastToggle, Exit exit, float headingYaw,
                               Options o) {
        List<PlayerInput> actions = Actions.around(headingYaw, o.offsets, o.sneak);
        if (o.extraActions != null) {
            Set<PlayerInput> seen = new HashSet<PlayerInput>(actions);
            for (PlayerInput a : o.extraActions) if (seen.add(a)) actions.add(a);
        }
        double r = Math.toRadians(headingYaw);
        double dx = -Math.sin(r), dz = Math.cos(r);
        double ox = start.posX, oz = start.posZ;
        List<Layer> layers = new ArrayList<Layer>();
        Layer first = new Layer(1);
        first.add(start, -1, null, lastToggle);
        layers.add(first);
        List<Result> results = new ArrayList<Result>();
        Set<Long> seenEnds = new HashSet<Long>();
        int firstExit = -1;
        expanded = 0;
        timedOut = false;
        guideLost = -1;
        PlayerState g = start;
        int glt = lastToggle;
        for (int t = 1; t <= o.maxTicks; t++) {
            if (firstExit >= 0 && t > firstExit + o.extraTicks) break;
            Layer cur = layers.get(t - 1);
            if (cur.size == 0) break;
            if (o.deadlineNanos != 0 && System.nanoTime() > o.deadlineNanos) {
                timedOut = true;
                break;
            }
            Map<Long, Cand> merged = new HashMap<Long, Cand>();
            for (int i = 0; i < cur.size; i++) {
                PlayerState s = cur.states[i];
                for (PlayerInput a : actions) {
                    PlayerState ns = Physics.tick(world, s, a);
                    expanded++;
                    if (ns.unsupportedAction || ns.posY < o.voidY) continue;
                    int lt = cur.lastToggle[i];
                    if (s.sprinting != ns.sprinting || s.prevSneak != ns.prevSneak) {
                        if (t - lt < TOGGLE_GAP) continue;
                        lt = t;
                    }
                    if (exit.reached(s, ns)) {
                        if (seenEnds.add(roundedKey(ns, t - lt))) {
                            List<PlayerInput> path = backtrack(layers, t - 1, i);
                            path.add(a);
                            results.add(new Result(path, ns, lt));
                            if (firstExit < 0) firstExit = t;
                        }
                        continue;
                    }
                    double along = (ns.posX - ox) * dx + (ns.posZ - oz) * dz;
                    double side = (ns.posX - ox) * dz - (ns.posZ - oz) * dx;
                    double v = ns.motionX * dx + ns.motionZ * dz;
                    double score = exit.progress(ns, along) + o.lookahead * v;
                    long key = o.cells ? cellKey(ns, along, side, v, t - lt) : roundedKey(ns, t - lt);
                    Cand old = merged.get(key);
                    if (old == null || old.score < score) {
                        long bucket = ((long) Math.floor(ns.posY * 8) << 24) ^ ((long) Math.floor(side * 4) << 2)
                                ^ (ns.onGround ? 1 : 0);
                        merged.put(key, new Cand(ns, i, a, lt, score, bucket));
                    }
                }
            }
            List<Cand> cands = new ArrayList<Cand>(merged.values());
            Collections.sort(cands, (p, q) -> Double.compare(q.score, p.score));
            Layer next = new Layer(Math.min(o.width, cands.size()));
            // First the best few of every bucket, so a bucket that ranks low as a whole (climbing a
            // ladder while others stand closer at the wall) keeps going; then the rest by score.
            Map<Long, int[]> counts = new HashMap<Long, int[]>();
            boolean[] taken = new boolean[cands.size()];
            for (int pass = 0; pass < 2; pass++) {
                int cap = pass == 0 ? o.bucketMin : o.bucketCap;
                for (int i = 0; i < cands.size() && next.size < o.width; i++) {
                    if (taken[i]) continue;
                    Cand c = cands.get(i);
                    int[] n = counts.get(c.bucket);
                    if (n == null) counts.put(c.bucket, n = new int[1]);
                    if (n[0] >= cap) continue;
                    n[0]++;
                    taken[i] = true;
                    next.add(c.s, c.parent, c.a, c.lastToggle);
                }
            }
            layers.add(next);
            if (o.guide != null && guideLost < 0 && t <= o.guide.size()) {
                PlayerState ng = Physics.tick(world, g, o.guide.get(t - 1));
                if (g.sprinting != ng.sprinting || g.prevSneak != ng.prevSneak) glt = t;
                g = ng;
                long gk = key(g, t - glt, o.cells, ox, oz, dx, dz);
                boolean found = exit.reached(g, g);
                for (int i = 0; i < next.size && !found; i++) {
                    found = key(next.states[i], t - next.lastToggle[i], o.cells, ox, oz, dx, dz) == gk;
                }
                if (!found) guideLost = t;
            }
        }
        Collections.sort(results, (p, q) -> p.ticks() != q.ticks() ? Integer.compare(p.ticks(), q.ticks())
                : Double.compare(q.speedAlong(headingYaw), p.speedAlong(headingYaw)));
        return results.size() > o.results ? new ArrayList<Result>(results.subList(0, o.results)) : results;
    }

    private static List<PlayerInput> backtrack(List<Layer> layers, int layer, int index) {
        PlayerInput[] path = new PlayerInput[layer];
        for (int l = layer; l > 0; l--) {
            Layer L = layers.get(l);
            path[l - 1] = L.input[index];
            index = L.parent[index];
        }
        return new ArrayList<PlayerInput>(Arrays.asList(path));
    }

    /** A hash of the state after rounding, for merging near-identical states. */
    static long roundedKey(PlayerState s, int sinceToggle) {
        long h = 0xcbf29ce484222325L;
        h = mix(h, Math.round(s.posX * 1e4));
        h = mix(h, Math.round(s.posY * 1e4));
        h = mix(h, Math.round(s.posZ * 1e4));
        h = mix(h, Math.round(s.motionX * 1e5));
        h = mix(h, Math.round(s.motionY * 1e5));
        h = mix(h, Math.round(s.motionZ * 1e5));
        h = mix(h, (s.onGround ? 1 : 0) | (s.sprinting ? 2 : 0) | (s.prevSneak ? 4 : 0)
                | (s.collidedHorizontally ? 8 : 0) | Math.min(sinceToggle, TOGGLE_GAP) << 4);
        h = mix(h, s.jumpTicks);
        h = mix(h, Float.floatToIntBits(s.height));
        return h;
    }

    private static long key(PlayerState s, int sinceToggle, boolean cells, double ox, double oz, double dx, double dz) {
        if (!cells) return roundedKey(s, sinceToggle);
        double along = (s.posX - ox) * dx + (s.posZ - oz) * dz;
        double side = (s.posX - ox) * dz - (s.posZ - oz) * dx;
        return cellKey(s, along, side, s.motionX * dx + s.motionZ * dz, sinceToggle);
    }

    static long cellKey(PlayerState s, double along, double side, double v, int sinceToggle) {
        long h = 0xcbf29ce484222325L;
        h = mix(h, (long) Math.floor(along / 0.05));
        h = mix(h, (long) Math.floor(side / 0.1));
        h = mix(h, (long) Math.floor(s.posY / 0.05));
        h = mix(h, (long) Math.floor(v / 0.01));
        h = mix(h, (long) Math.floor(s.motionY / 0.04));
        h = mix(h, (s.onGround ? 1 : 0) | (s.sprinting ? 2 : 0) | (s.prevSneak ? 4 : 0)
                | Math.min(sinceToggle, TOGGLE_GAP) << 4);
        h = mix(h, Float.floatToIntBits(s.height));
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v;
        h *= 0x100000001b3L;
        return h ^ (h >>> 29);
    }
}
