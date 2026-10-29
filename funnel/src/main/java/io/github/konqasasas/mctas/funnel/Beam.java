package io.github.konqasasas.mctas.funnel;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The local search: a tick-by-tick beam with the exact physics from one state, steered by a backward
 * {@link Field}, that ends on verified landings.
 *
 * <p>Each state tries moves around its own heading (the field's way to the seeds from where it is),
 * in three strafe modes, with and without jump, plus letting go and sneaking. States are ranked by
 * clock plus the field's value a little ahead of them (their position plus {@link #LOOK} ticks of
 * velocity), merged by coarse cells (keeping the best rank) and capped per block of position so that
 * the beam keeps going around corners.
 *
 * <p>A hit is a state standing on a verified anchor whose time is below the caller's cap, or a state
 * in the goal; its value is the clock plus the anchor's time. The beam keeps the best hit per anchor
 * and stops when no state can beat the best hit.
 */
final class Beam {
    static final double LOOK = 2.0;
    static final float FAST_SLACK = 4;
    static final float[] OFFSETS = {0, 20, -20, 45, -45, 90, -90, 180};

    /** A state of a run under the judge's clock. */
    static final class Node {
        final PlayerState s;
        final int tick;
        /** Tick of the last start entry, or -1; with {@code judgeStart} off, 0. */
        final int entry;
        final boolean inStart;
        final int lastToggle;

        Node(PlayerState s, int tick, int entry, boolean inStart, int lastToggle) {
            this.s = s;
            this.tick = tick;
            this.entry = entry;
            this.inStart = inStart;
            this.lastToggle = lastToggle;
        }

        /** Ticks on the judge's clock: since the last start entry; 0 before one (the run-up is free). */
        int clock() {
            return entry < 0 ? 0 : tick - entry;
        }
    }

    static final class Hit {
        final List<PlayerInput> inputs;
        final Node end;
        /** The anchor landed on, or null for the goal. */
        final Anchors.Anchor anchor;
        final float value;

        Hit(List<PlayerInput> inputs, Node end, Anchors.Anchor anchor, float value) {
            this.inputs = inputs;
            this.end = end;
            this.anchor = anchor;
            this.value = value;
        }

        boolean goal() {
            return anchor == null;
        }
    }

    static final class Options {
        int width = 200;
        int maxTicks = 80;
        /** Keep this many hits (best per anchor). */
        int hits = 1;
        /** Hits must land on anchors with a time below this. */
        float maxV = Field.INF;
        /**
         * Hits only on anchors verified from standing still: an anchor verified only when arriving at
         * speed guides the beam through the field but does not end it, since the beam's own landing
         * may be slower.
         */
        boolean restOnly;
        /** Offer the landings on unverified anchors to their arrival pools ({@link Anchors.Anchor#offer}). */
        boolean collect;
        /** The anchor the beam starts on (not offered its own landings). */
        Anchors.Anchor self;
        /** Count start entries (the forward run); off in the backward search, where the clock starts at 0. */
        boolean judgeStart;
        long deadlineNanos;
        float[] offsets = OFFSETS;
        /**
         * The run-up only: the hits are the entries into the start box (value: the field's ticks left
         * from there, as the run-up's ticks are free), the states ranked by the way to the box; the
         * best per position and speed.
         */
        boolean entries;
        /** States whose clock plus this share of the field's value is past the best hit are dropped. */
        double prune = 0.6;
    }

    private final Problem problem;
    private final Grid grid;
    private final Field field;
    private final Anchors anchors;
    private final Landing landing;
    long expanded;
    /** Prints the best state of every tick. */
    boolean trace;
    static final float FREE = 0.6F;
    /** The run-up beam ({@link Options#entries}) goes on this many ticks past its first entry. */
    static final int ENTRY_TICKS = 12;
    /** With the judge's start ({@link Options#judgeStart}), the beam goes on this many ticks past its first hit. */
    static final int HIT_TICKS = 40;
    /** Towards the start box, for states before their first entry (judgeStart), and the field's best value in the box. */
    private Field toStart;
    private float startValue;

    Beam(Grid grid, Field field, Landing landing, Anchors anchors) {
        this.problem = grid.problem;
        this.grid = grid;
        this.field = field;
        this.landing = landing;
        this.anchors = anchors;
    }

    /**
     * Before the first entry into the start box the run has not started: such states head for the box
     * (the run-up is free) and land on no anchor, since a run that skips the box never counts.
     */
    Beam toStart(Field toStart, float startValue) {
        this.toStart = toStart;
        this.startValue = startValue;
        return this;
    }

    private boolean before(Node n, Options o) {
        return o.judgeStart && toStart != null && n.entry < 0;
    }

    private static final class Layer {
        Node[] nodes;
        int[] parent;
        PlayerInput[] input;
        int size;

        Layer(int n) {
            nodes = new Node[n];
            parent = new int[n];
            input = new PlayerInput[n];
        }

        void add(Node s, int p, PlayerInput a) {
            nodes[size] = s;
            parent[size] = p;
            input[size] = a;
            size++;
        }
    }

    private static final class Cand {
        final Node n;
        final int parent;
        final PlayerInput a;
        final float f;
        final long bucket;

        Cand(Node n, int parent, PlayerInput a, float f, long bucket) {
            this.n = n;
            this.parent = parent;
            this.a = a;
            this.f = f;
            this.bucket = bucket;
        }
    }

    /** One tick of the judge: the next node, or null when the run dies (void, damage, toggle rule, unsupported). */
    Node step(Node n, PlayerInput a, boolean judgeStart) {
        PlayerState ns = Physics.tick(grid.world, n.s, a);
        int tick = n.tick + 1;
        int lt = n.lastToggle;
        if (n.s.sprinting != ns.sprinting || n.s.prevSneak != ns.prevSneak) {
            if (!ToggleRule.allowed(lt, tick)) return null;
            lt = tick;
        }
        if (ns.unsupportedAction || ns.posY < problem.voidY || grid.hurts(ns)) return null;
        int entry = n.entry;
        boolean in = n.inStart;
        if (judgeStart) {
            boolean now = problem.start.contains(ns.posX, ns.posY, ns.posZ);
            if (now && !in) entry = tick;
            in = now;
        }
        return new Node(ns, tick, entry, in, lt);
    }

    boolean inGoal(Node n) {
        return n.entry >= 0 && problem.goal.contains(n.s.posX, n.s.posY, n.s.posZ);
    }

    /**
     * The moves to try. W+A at a yaw 45 to the right of W moves the same way, and faster (the input is
     * scaled to length 1 only when it is longer), so off a jump tick only the strafe is tried. On a
     * jump tick the sprint jump's push follows the yaw, so W and both strafes are tried. A jump only
     * acts on the ground or in water, so in the air there is no jump.
     */
    List<PlayerInput> actions(float heading, float[] offsets, boolean canJump) {
        List<PlayerInput> out = new ArrayList<PlayerInput>(offsets.length * 4 + 4);
        for (float off : offsets) {
            float dir = heading + off;
            out.add(PlayerInput.of(PlayerInput.SPRINT | PlayerInput.W | PlayerInput.A, dir + 45));
            if (!canJump) continue;
            int base = PlayerInput.SPRINT | PlayerInput.JUMP;
            out.add(PlayerInput.of(base | PlayerInput.W, dir));
            out.add(PlayerInput.of(base | PlayerInput.W | PlayerInput.A, dir + 45));
            out.add(PlayerInput.of(base | PlayerInput.W | PlayerInput.D, dir - 45));
        }
        out.add(PlayerInput.of(PlayerInput.SPRINT, heading));
        if (canJump) out.add(PlayerInput.of(PlayerInput.SPRINT | PlayerInput.JUMP, heading));
        out.add(PlayerInput.of(PlayerInput.SNEAK | PlayerInput.W, heading));
        out.add(PlayerInput.of(PlayerInput.SNEAK, heading));
        return out;
    }

    /**
     * Sprinting along the line through the cell centres ahead: the yaw whose tick puts the player's
     * side coordinate (across the way to the cell) exactly on the cell's, so that the beam can thread
     * slots barely wider than the player (an open trapdoor in each side of a doorway leaves 0.025).
     * One for the cell about 0.6 blocks ahead, one for 1.2; only when the player is already within half
     * a block of the line.
     */
    private void lines(Node n, Field f, List<PlayerInput> out) {
        for (double reach : LINE_REACH) {
            double[] t = f.aim(n.s.posX, n.s.posY, n.s.posZ, reach);
            if (t == null) continue;
            double dx = t[0] - n.s.posX, dz = t[1] - n.s.posZ;
            boolean alongZ = Math.abs(dz) >= Math.abs(dx);
            double off = alongZ ? dx : dz;
            if (Math.abs(off) > 0.5 || Math.abs(off) < 1e-4) continue;
            float base = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // Secant on the yaw for the side coordinate after one tick.
            float y0 = base - 20, y1 = base + 20;
            double e0 = side(n, y0, alongZ, t), e1 = side(n, y1, alongZ, t);
            for (int k = 0; k < 4 && Math.abs(e1) > 1e-5 && Math.abs(e1 - e0) > 1e-9; k++) {
                float y2 = (float) (y1 - e1 * (y1 - y0) / (e1 - e0));
                if (Math.abs(y2 - base) > 60) break;
                y0 = y1;
                e0 = e1;
                y1 = y2;
                e1 = side(n, y1, alongZ, t);
            }
            if (Math.abs(e1) < 0.005) out.add(PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT, y1));
        }
    }

    private static final double[] LINE_REACH = {0.6, 1.2};

    private double side(Node n, float yaw, boolean alongZ, double[] t) {
        PlayerState s = Physics.tick(grid.world, n.s, PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT, yaw));
        return alongZ ? s.posX - t[0] : s.posZ - t[1];
    }

    /**
     * Ticks to go by the field: on the ground (or on a ladder, in water) the field a little ahead; in
     * the air the best landing of the flight ({@link Landing}), or the field plus a penalty when the
     * flight comes down nowhere (it may yet hit a wall or be steered).
     */
    float toGo(Node n) {
        PlayerState s = n.s;
        int id = grid.cellOf(s.posX, s.posY, s.posZ);
        boolean held = id >= 0 && (grid.flags[id] & (Grid.CLIMB | Grid.WATER)) != 0;
        if (s.onGround || held) {
            float h = field.at(s.posX + LOOK * s.motionX, s.posY, s.posZ + LOOK * s.motionZ);
            if (h == Field.INF) h = field.at(s.posX, s.posY, s.posZ) + 2;
            return h;
        }
        float h = landing.value(s);
        if (h == Field.INF) h = field.at(s.posX, s.posY, s.posZ) + 20;
        return h;
    }

    List<Hit> search(Node root, Options o) {
        return search(Collections.singletonList(root), o);
    }

    /** A beam from several roots at once (each its own clock). */
    List<Hit> search(List<Node> roots, Options o) {
        expanded = 0;
        List<Layer> layers = new ArrayList<Layer>();
        Layer first = new Layer(roots.size());
        for (Node r : roots) {
            first.add(r, -1, null);
        }
        Node root = roots.get(0);
        layers.add(first);
        Map<Integer, Hit> best = new HashMap<Integer, Hit>();
        // Per anchor also the fastest landing within a few ticks of its best: the run after it may need the speed.
        Map<Integer, Hit> fast = new HashMap<Integer, Hit>();
        float bestValue = Field.INF;
        Map<Long, Hit> entered = new HashMap<Long, Hit>();
        int firstEntry = -1, firstHit = -1;
        float lastHeading = field.heading(root.s.posX, root.s.posY, root.s.posZ);
        if (Float.isNaN(lastHeading)) lastHeading = root.s.rotationYaw;
        for (int t = 1; t <= o.maxTicks; t++) {
            Layer cur = layers.get(t - 1);
            if (cur.size == 0) break;
            if (o.deadlineNanos != 0 && System.nanoTime() > o.deadlineNanos) break;
            int least = Integer.MAX_VALUE;
            for (int i = 0; i < cur.size; i++) least = Math.min(least, cur.nodes[i].clock());
            if (least >= bestValue) break;
            if (firstEntry >= 0 && t > firstEntry + ENTRY_TICKS) break;
            // States that step back into the start box restart the clock, so the clock alone never
            // rules them out: the beam goes on only so long past its first hit.
            if (o.judgeStart && firstHit >= 0 && t > firstHit + HIT_TICKS) break;
            Map<Long, Cand> merged = new HashMap<Long, Cand>();
            for (int i = 0; i < cur.size; i++) {
                Node n = cur.nodes[i];
                float heading = before(n, o) ? toStart.heading(n.s.posX, n.s.posY, n.s.posZ) : field.heading(n.s.posX, n.s.posY, n.s.posZ);
                if (Float.isNaN(heading)) heading = lastHeading;
                int cell = grid.cellOf(n.s.posX, n.s.posY, n.s.posZ);
                boolean canJump = n.s.onGround || cell >= 0 && (grid.flags[cell] & Grid.WATER) != 0;
                List<PlayerInput> acts = actions(heading, o.offsets, canJump);
                lines(n, before(n, o) ? toStart : field, acts);
                for (PlayerInput a : acts) {
                    Node m = step(n, a, o.judgeStart);
                    expanded++;
                    if (m == null) continue;
                    if (o.entries) {
                        if (m.entry < 0) {
                            float h = toStart.at(m.s.posX, m.s.posY, m.s.posZ);
                            if (h == Field.INF) continue;
                            long key = cellKey(m);
                            Cand old = merged.get(key);
                            if (old == null || m.tick + h < old.f) merged.put(key, new Cand(m, i, a, m.tick + h, bucket(m)));
                            continue;
                        }
                        if (firstEntry < 0) firstEntry = t;
                        long key = ((long) Math.floor(speed(m.s) / 0.04) * 64 + (long) Math.floor(m.s.posX * 2)) * 1000003L
                            + (long) Math.floor(m.s.posZ * 2) * 4 + (long) Math.floor(m.s.posY * 2) * 2 + (m.s.onGround ? 1 : 0);
                        // By what is left after the entry: the run-up's ticks are free.
                        float left = toGo(m);
                        Hit old = entered.get(key);
                        if (left < Field.INF && (old == null || left < old.value)) entered.put(key, new Hit(path(layers, t - 1, i, a), m, null, left));
                        continue;
                    }
                    if (inGoal(m)) {
                        float v = m.clock();
                        Hit old = best.get(-1);
                        if (old == null || v < old.value) best.put(-1, new Hit(path(layers, t - 1, i, a), m, null, v));
                        bestValue = Math.min(bestValue, v);
                        if (firstHit < 0) firstHit = t;
                        continue;
                    }
                    if (o.collect && m.s.onGround && !n.s.onGround) {
                        // A real landing at speed on an anchor not verified yet: a root for its own beam later.
                        Anchors.Anchor to = anchors.under(m.s, false);
                        if (to != null && to != o.self && !to.verified()) to.offer(new Node(m.s, 0, 0, false, m.lastToggle - m.tick));
                    }
                    if (m.s.onGround && !before(m, o)) {
                        Anchors.Anchor an = anchors.under(m.s, o.restOnly);
                        if (an != null && an.v < o.maxV && an != o.self) {
                            float v = m.clock() + an.v;
                            Hit old = best.get(an.id);
                            Hit hit = null;
                            if (old == null || v < old.value || v == old.value && speed(m.s) > speed(old.end.s)) best.put(an.id, hit = new Hit(path(layers, t - 1, i, a), m, an, v));
                            Hit f = fast.get(an.id);
                            if (v <= best.get(an.id).value + FAST_SLACK && (f == null || speed(m.s) > speed(f.end.s))) {
                                fast.put(an.id, hit != null ? hit : new Hit(path(layers, t - 1, i, a), m, an, v));
                            }
                            if (v < bestValue) bestValue = v;
                            if (firstHit < 0) firstHit = t;
                        }
                    }
                    float h = toGo(m);
                    if (before(m, o)) h = FREE * toStart.at(m.s.posX, m.s.posY, m.s.posZ) + Math.max(startValue, h);
                    if (h == Field.INF) continue;
                    // Free ticks (the run-up, before the clock starts) still cost a little in the order, or
                    // states that step in and out of the start box, restarting the clock, fill the beam.
                    float f = m.clock() + h + FREE * (m.tick - m.clock());
                    if (m.clock() + o.prune * h >= bestValue) continue;
                    long key = cellKey(m);
                    Cand old = merged.get(key);
                    if (old == null || f < old.f) merged.put(key, new Cand(m, i, a, f, bucket(m)));
                }
            }
            List<Cand> cands = new ArrayList<Cand>(merged.values());
            Collections.sort(cands, (p, q) -> Float.compare(p.f, q.f));
            Layer next = new Layer(Math.min(o.width, cands.size()));
            Map<Long, int[]> counts = new HashMap<Long, int[]>();
            int cap = Math.max(4, o.width / 6);
            for (int i = 0; i < cands.size() && next.size < o.width; i++) {
                Cand c = cands.get(i);
                int[] k = counts.get(c.bucket);
                if (k == null) counts.put(c.bucket, k = new int[1]);
                if (k[0] >= cap) continue;
                k[0]++;
                next.add(c.n, c.parent, c.a);
            }
            layers.add(next);
            if (trace && next.size > 0) {
                Node b = next.nodes[0];
                System.err.printf("    t %d: %d states, best f %.1f at (%.3f, %.3f, %.3f) v (%.3f, %.3f, %.3f) ground %b h %.1f heading %.0f, best hit %.1f%n", t, next.size,
                    cands.get(0).f, b.s.posX, b.s.posY, b.s.posZ, b.s.motionX, b.s.motionY, b.s.motionZ, b.s.onGround,
                    field.at(b.s.posX, b.s.posY, b.s.posZ), field.heading(b.s.posX, b.s.posY, b.s.posZ), bestValue);
            }
        }
        if (o.entries) {
            List<Hit> out = new ArrayList<Hit>(entered.values());
            Collections.sort(out, (p, q) -> Float.compare(p.value, q.value));
            return out.size() > o.hits ? new ArrayList<Hit>(out.subList(0, o.hits)) : out;
        }
        List<Hit> out = new ArrayList<Hit>(best.values());
        for (Hit h : fast.values()) if (!out.contains(h)) out.add(h);
        Collections.sort(out, (p, q) -> Float.compare(p.value, q.value));
        return out.size() > o.hits ? new ArrayList<Hit>(out.subList(0, o.hits)) : out;
    }

    /** The block of position (and on the ground or not) that caps how many states the beam keeps there. */
    private static long bucket(Node m) {
        return ((long) Math.floor(m.s.posY * 2) * 31 + (long) Math.floor(m.s.posX)) * 1000003L
            + (long) Math.floor(m.s.posZ) * 2 + (m.s.onGround ? 1 : 0);
    }

    private static List<PlayerInput> path(List<Layer> layers, int layer, int index, PlayerInput last) {
        PlayerInput[] p = new PlayerInput[layer + 1];
        p[layer] = last;
        for (int l = layer; l > 0; l--) {
            Layer L = layers.get(l);
            p[l - 1] = L.input[index];
            index = L.parent[index];
        }
        return new ArrayList<PlayerInput>(Arrays.asList(p));
    }

    static double speed(PlayerState s) {
        return Math.sqrt(s.motionX * s.motionX + s.motionZ * s.motionZ);
    }

    static long cellKey(Node n) {
        PlayerState s = n.s;
        long h = 0xcbf29ce484222325L;
        h = mix(h, (long) Math.floor(s.posX / 0.04));
        h = mix(h, (long) Math.floor(s.posZ / 0.04));
        h = mix(h, (long) Math.floor(s.posY / 0.05));
        h = mix(h, (long) Math.floor(s.motionX / 0.02));
        h = mix(h, (long) Math.floor(s.motionZ / 0.02));
        h = mix(h, (long) Math.floor(s.motionY / 0.04));
        int since = Math.min(n.tick - n.lastToggle, ToggleRule.GAP);
        h = mix(h, (s.onGround ? 1 : 0) | (s.sprinting ? 2 : 0) | (s.prevSneak ? 4 : 0) | (n.inStart ? 8 : 0) | (n.entry >= 0 ? 16 : 0) | since << 5);
        h = mix(h, Float.floatToIntBits(s.height));
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v;
        h *= 0x100000001b3L;
        return h ^ (h >>> 29);
    }
}
