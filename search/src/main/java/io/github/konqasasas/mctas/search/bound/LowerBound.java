package io.github.konqasasas.mctas.search.bound;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Proves a lower bound on the time of a problem by over-approximating every reachable state.
 *
 * <p>Two phases, both on {@link RelaxedModel}:
 * <ol>
 *   <li><b>Before the start.</b> From the spawn, the sets of states the player can be in before the
 *       last start entry are grown to a fixed point over unbounded time (the run-up is free). The
 *       result covers every state at every tick before the start, whatever the inputs were.</li>
 *   <li><b>After the start.</b> The states one tick after a state outside the start box, that are
 *       inside it, are the possible states at the start entry. They are stepped tick by tick; the
 *       first tick {@code k} at which some set touches the goal is the bound. No real input sequence
 *       can finish in fewer than {@code k} ticks, because its states at every tick lie in the sets.</li>
 * </ol>
 */
public final class LowerBound {

    public static final class Result {
        /** No run finishes in fewer ticks than this. */
        public final int ticks;
        public final int preStates;
        public final int entryStates;
        public final long work;

        Result(int ticks, int preStates, int entryStates, long work) {
            this.ticks = ticks;
            this.preStates = preStates;
            this.entryStates = entryStates;
            this.work = work;
        }
    }

    /**
     * Sets are joined only within one key: the vertical state, the surface last stood on, and the
     * face of the start box crossed at the entry. Joining across surfaces or faces would let the
     * hull fill the gaps between them.
     */
    private static final class Key {
        final VState v;
        final int rect;
        final int face;
        final long cell;

        Key(VState v, int rect, int face) {
            this(v, rect, face, 0);
        }

        Key(VState v, int rect, int face, long cell) {
            this.v = v;
            this.rect = rect;
            this.face = face;
            this.cell = cell;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return k.v.equals(v) && k.rect == rect && k.face == face && k.cell == cell;
        }

        @Override
        public int hashCode() {
            return Objects.hash(v, rect, face, cell);
        }
    }

    static int WIDEN_AFTER = System.getenv("MCTAS_WIDEN") != null ? Integer.parseInt(System.getenv("MCTAS_WIDEN")) : 6;

    private static final class Sets {
        HState h;
        int updates;
        int lastUpdates;
        /** For traces: the set this one was first reached from, and how. */
        Key parent;
        Sets parentSets;
        String how;

        Sets(HState h) {
            this.h = h;
        }

        /** Joins {@code o} into this; returns true if this grew. */
        boolean join(HState o) {
            if (h.covers(o)) return false;
            if (updates < WIDEN_AFTER) {
                h = h.hull(o);
            } else {
                HState n = HState.widenJoin(h, o);
                if (n.sameAs(h)) return false;
                h = n;
            }
            updates++;
            return true;
        }
    }

    private final Problem problem;
    private final RelaxedModel model;
    private long work;

    public LowerBound(Problem problem) {
        this.problem = problem;
        this.model = new RelaxedModel(problem);
    }

    /**
     * How many separate sets to keep per key after the start. Sets beyond this are joined pairwise,
     * closest first; more sets give a tighter bound and cost more time.
     */
    public int cap = 1;

    /** For tests: the sets before the start, and after it per tick, when {@link #keepLayers} is set. */
    Map<Key, Sets> preSets;
    List<Map<Key, List<Sets>>> layers;
    boolean keepLayers;
    boolean ignoreGoal;

    /** For tests: true if some kept set for tick {@code k} after an entry (or before the start, k < 0) holds the state. */
    boolean holds(int k, VState v, double x, double z, double vx, double vz, double tol) {
        if (k < 0) {
            for (Map.Entry<Key, Sets> e : preSets.entrySet()) {
                if (e.getKey().v.equals(v) && e.getValue().h.contains(x, z, vx, vz, tol)) return true;
            }
            return false;
        }
        for (Map.Entry<Key, List<Sets>> e : layers.get(k).entrySet()) {
            if (!e.getKey().v.equals(v)) continue;
            for (Sets s : e.getValue()) if (s.h.contains(x, z, vx, vz, tol)) return true;
        }
        return false;
    }

    /** Returns the bound, or null if no set reaches the goal within {@code maxTicks} after the start. */
    public Result run(int maxTicks) {
        Map<Key, Sets> pre = beforeStart();
        Map<Key, List<Sets>> layer = entries(pre);
        if (keepLayers) {
            preSets = pre;
            layers = new ArrayList<Map<Key, List<Sets>>>();
            layers.add(layer);
        }
        int entryStates = count(layer);
        for (int k = 1; k <= maxTicks && !layer.isEmpty(); k++) {
            Map<Key, List<Sets>> next = new LinkedHashMap<Key, List<Sets>>();
            for (Map.Entry<Key, List<Sets>> e : layer.entrySet()) {
                Key key = e.getKey();
                for (Sets s : e.getValue()) {
                    for (RelaxedModel.Next n : model.step(key.v, key.rect, s.h)) {
                        work++;
                        if (!ignoreGoal && touchesGoal(n)) {
                            if (trace != null) trace(k, key, s, n);
                            return new Result(k, pre.size(), entryStates, work);
                        }
                        Sets c = new Sets(n.h);
                        c.how = n.how;
                        c.parent = key;
                        c.parentSets = s;
                        add(next, new Key(n.v, n.rect, key.face), c);
                    }
                }
            }
            for (List<Sets> l : next.values()) shrink(l);
            layer = next;
            if (keepLayers) layers.add(layer);
        }
        return null;
    }

    private void trace(int k, Key key, Sets s, RelaxedModel.Next n) {
        trace.append("goal at k=").append(k).append(": ").append(n.v).append(' ').append(n.how)
            .append(' ').append(n.h).append('\n');
        Sets c = s;
        Key ck = key;
        for (int j = k - 1; c != null; j--) {
            trace.append("  k=").append(j).append(": ").append(ck.v).append(" rect").append(ck.rect).append(' ')
                .append(c.how).append(' ').append(c.h).append(" xv ").append(c.h.xv).append(" zv ").append(c.h.zv).append('\n');
            ck = c.parent;
            c = c.parentSets;
        }
    }

    private static int count(Map<Key, List<Sets>> m) {
        int n = 0;
        for (List<Sets> l : m.values()) n += l.size();
        return n;
    }

    /** Adds {@code c} unless a kept set already covers it, and drops kept sets it covers. */
    private static void add(Map<Key, List<Sets>> map, Key k, Sets c) {
        List<Sets> l = map.get(k);
        if (l == null) map.put(k, l = new ArrayList<Sets>());
        for (Sets o : l) if (o.h.covers(c.h)) return;
        for (int i = l.size() - 1; i >= 0; i--) {
            if (c.h.covers(l.get(i).h)) l.remove(i);
        }
        l.add(c);
    }

    /** Joins the closest pairs until at most {@link #cap} sets are left. */
    private void shrink(List<Sets> l) {
        while (l.size() > cap) {
            int bi = 0, bj = 1;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < l.size(); i++) {
                for (int j = i + 1; j < l.size(); j++) {
                    double d = l.get(i).h.distance(l.get(j).h);
                    if (d < best) {
                        best = d;
                        bi = i;
                        bj = j;
                    }
                }
            }
            Sets a = l.get(bi), b = l.remove(bj);
            Sets m = new Sets(a.h.hull(b.h));
            m.how = a.how;
            m.parent = a.parent;
            m.parentSets = a.parentSets;
            l.set(bi, m);
        }
    }

    private boolean touchesGoal(RelaxedModel.Next n) {
        Region g = problem.goal;
        return n.v.minY >= g.minY && n.v.minY <= g.maxY && !n.h.clipPos(g.minX, g.maxX, g.minZ, g.maxZ).isEmpty();
    }

    private Map<Key, Sets> beforeStart() {
        buildCuts();
        Map<Key, Sets> sets = new HashMap<Key, Sets>();
        Deque<Key> work = new ArrayDeque<Key>();
        java.util.Set<Key> queued = new java.util.HashSet<Key>();
        double x = problem.spawn.pos.x, y = problem.spawn.pos.y, z = problem.spawn.pos.z;
        // The spawn state of the problem format: standing, motion (0, -0.0784, 0).
        VState v0 = new VState(y, y + (double) 1.8F, -0.0784000015258789D, true);
        Key k0 = new Key(v0, surfaceUnder(x, y, z), -2);
        sets.put(k0, new Sets(HState.point(x, z, 0, 0)));
        work.add(k0);
        queued.add(k0);
        while (!work.isEmpty()) {
            Key key = work.poll();
            queued.remove(key);
            Sets s = sets.get(key);
            if (verbose && System.nanoTime() > nextLog) {
                nextLog = System.nanoTime() + 5000000000L;
                System.err.println("pre: " + sets.size() + " sets, " + work.size() + " queued, " + this.work + " steps");
                List<Map.Entry<Key, Sets>> es = new ArrayList<Map.Entry<Key, Sets>>(sets.entrySet());
                java.util.Collections.sort(es, new java.util.Comparator<Map.Entry<Key, Sets>>() {
                    public int compare(Map.Entry<Key, Sets> a, Map.Entry<Key, Sets> b) { return (b.getValue().updates - b.getValue().lastUpdates) - (a.getValue().updates - a.getValue().lastUpdates); }
                });
                for (int q = 0; q < 4 && q < es.size(); q++) System.err.println("  " + es.get(q).getKey().v + " cell " + es.get(q).getKey().cell + " upd " + es.get(q).getValue().updates + " " + es.get(q).getValue().h + " xv " + es.get(q).getValue().h.xv + " zv " + es.get(q).getValue().h.zv + " recent " + (es.get(q).getValue().updates - es.get(q).getValue().lastUpdates));
                for (Sets zz : sets.values()) zz.lastUpdates = zz.updates;
            }
            if (sets.size() > 200000 || this.work > 20000000) {
                StringBuilder sb = new StringBuilder("pre-start fixed point does not converge; sample:");
                int c = 0;
                for (Map.Entry<Key, Sets> e : sets.entrySet()) {
                    if (c++ > 15) break;
                    sb.append("\n  ").append(e.getKey().v).append(" rect ").append(e.getKey().rect).append(' ').append(e.getValue().h).append(" upd ").append(e.getValue().updates);
                }
                throw new IllegalStateException(sb.toString());
            }
            for (RelaxedModel.Next n : model.step(key.v, key.rect, s.h)) {
                this.work++;
                for (Map.Entry<Long, HState> piece : cells(n.h).entrySet()) {
                    Key nk = new Key(n.v, n.rect, -2, piece.getKey());
                    Sets old = sets.get(nk);
                    if (old == null) {
                        sets.put(nk, new Sets(piece.getValue()));
                        if (queued.add(nk)) work.add(nk);
                    } else if (old.join(piece.getValue())) {
                        if (queued.add(nk)) work.add(nk);
                    }
                }
            }
        }
        if (System.getenv("MCTAS_DUMPPRE") != null) {
            for (Map.Entry<Key, Sets> e : sets.entrySet()) {
                if (e.getKey().v.toString().contains(System.getenv("MCTAS_DUMPPRE"))) {
                    System.err.println("PRE " + e.getKey().v + " cell " + e.getKey().cell + " " + e.getValue().h + " xv " + e.getValue().h.xv + " zv " + e.getValue().h.zv + " XV" + e.getValue().h.xv.vertices());
                }
            }
        }
        return sets;
    }

    /** Split sets before the start into position cells around the start box (see {@link #cells}). */
    public boolean split = true;

    /**
     * Splits a set near the start box into cells bounded by the start box faces and the edges of
     * the blocks under it, so that joins do not mix, say, a player far out to the side with one on
     * the blocks. Away from the start box the set is kept whole.
     */
    private Map<Long, HState> cells(HState h) {
        Map<Long, HState> out = new LinkedHashMap<Long, HState>();
        Region st = problem.start;
        double margin = 3.0;
        if (!split || (System.getenv("MCTAS_FAR") != null && (h.pos.maxX() < st.minX - margin || h.pos.minX() > st.maxX + margin
            || h.pos.maxZ() < st.minZ - margin || h.pos.minZ() > st.maxZ + margin))) {
            out.put(Long.MIN_VALUE, h);
            return out;
        }
        double inf = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < xCuts.length; i++) {
            if (h.pos.maxX() < xCuts[i] || h.pos.minX() > xCuts[i + 1]) continue;
            for (int j = 0; j + 1 < zCuts.length; j++) {
                if (h.pos.maxZ() < zCuts[j] || h.pos.minZ() > zCuts[j + 1]) continue;
                HState piece = h.clipPos(xCuts[i], xCuts[i + 1], zCuts[j], zCuts[j + 1]);
                if (!piece.isEmpty()) out.put((long) i * 1000L + j, piece);
            }
        }
        return out;
    }

    private double[] xCuts;
    private double[] zCuts;

    private void buildCuts() {
        Region st = problem.start;
        java.util.TreeSet<Double> xs = new java.util.TreeSet<Double>(), zs = new java.util.TreeSet<Double>();
        xs.add(Double.NEGATIVE_INFINITY);
        xs.add(Double.POSITIVE_INFINITY);
        zs.add(Double.NEGATIVE_INFINITY);
        zs.add(Double.POSITIVE_INFINITY);
        xs.add(st.minX);
        xs.add(st.maxX);
        zs.add(st.minZ);
        zs.add(st.maxZ);
        for (RelaxedModel.Rect r : model.surfaces) {
            if (r.z1 < st.minZ - 3 || r.z0 > st.maxZ + 3 || r.x1 < st.minX - 3 || r.x0 > st.maxX + 3) continue;
            xs.add(r.x0);
            xs.add(r.x1);
        }
        if (System.getenv("MCTAS_XSTEP") != null) {
            double step = Double.parseDouble(System.getenv("MCTAS_XSTEP"));
            double[] base = toArray(xs);
            for (int i = 0; i + 1 < base.length; i++) {
                double a = Math.max(base[i], st.minX - 2), b = Math.min(base[i + 1], st.maxX + 2);
                for (double c = a + step; c < b - step / 2; c += step) xs.add(c);
            }
        }
        xCuts = toArray(xs);
        if (System.getenv("MCTAS_ZSTEP") != null) {
            double step = Double.parseDouble(System.getenv("MCTAS_ZSTEP"));
            double[] base = toArray(zs);
            for (int i = 0; i + 1 < base.length; i++) {
                double a = Math.max(base[i], st.minZ - 2), b = Math.min(base[i + 1], st.maxZ + 2);
                for (double c = a + step; c < b - step / 2; c += step) zs.add(c);
            }
        }
        zCuts = toArray(zs);
    }

    private static double[] toArray(java.util.Set<Double> s) {
        double[] a = new double[s.size()];
        int i = 0;
        for (double d : s) a[i++] = d;
        return a;
    }

    private int surfaceUnder(double x, double y, double z) {
        for (RelaxedModel.Rect r : model.surfaces) {
            if ((double) r.top == y && x >= r.x0 && x <= r.x1 && z >= r.z0 && z <= r.z1) return r.id;
        }
        throw new IllegalArgumentException("the spawn does not stand on a block");
    }

    /**
     * The possible states at a start entry: the state one tick after a state outside the start box,
     * inside it. The displacement into the box has the sign of the face crossed, and so has the new
     * velocity (it is the displacement times the friction, or zero after a collision).
     */
    private Map<Key, List<Sets>> entries(Map<Key, Sets> pre) {
        Region st = problem.start;
        Map<Key, List<Sets>> out = new LinkedHashMap<Key, List<Sets>>();
        if (st.contains(problem.spawn.pos)) {
            throw new RelaxedModel.Unsupported("spawn inside the start box");
        }
        for (Map.Entry<Key, Sets> e : pre.entrySet()) {
            Key key = e.getKey();
            Sets s = e.getValue();
            boolean yInside = key.v.minY >= st.minY && key.v.minY <= st.maxY;
            List<HState> pieces = new ArrayList<HState>();
            List<Integer> faces = new ArrayList<Integer>();
            double inf = Double.POSITIVE_INFINITY;
            if (!yInside) {
                pieces.add(s.h);
                faces.add(-1);
            } else {
                pieces.add(s.h.clipPos(-inf, st.minX, -inf, inf));
                faces.add(0);
                pieces.add(s.h.clipPos(st.maxX, inf, -inf, inf));
                faces.add(1);
                pieces.add(s.h.clipPos(-inf, inf, -inf, st.minZ));
                faces.add(2);
                pieces.add(s.h.clipPos(-inf, inf, st.maxZ, inf));
                faces.add(3);
            }
            for (int i = 0; i < pieces.size(); i++) {
                HState piece = pieces.get(i);
                if (piece.isEmpty()) continue;
                if (System.getenv("MCTAS_ONLYFACE") != null && faces.get(i) != Integer.parseInt(System.getenv("MCTAS_ONLYFACE"))) continue;
                for (RelaxedModel.Next n : model.step(key.v, key.rect, piece)) {
                    work++;
                    if (n.v.minY < st.minY || n.v.minY > st.maxY) continue;
                    HState in = n.h.clipPos(st.minX, st.maxX, st.minZ, st.maxZ);
                    if (faces.get(i) >= 0) in = in.velSign(faces.get(i));
                    if (in.isEmpty()) continue;
                    Sets c = new Sets(in);
                    c.how = "entry (" + n.how + ") from " + key.v + " face " + faces.get(i);
                    c.parent = key;
                    c.parentSets = s;
                    add(out, new Key(n.v, n.rect, faces.get(i)), c);
                }
            }
        }
        for (List<Sets> l : out.values()) shrink(l);
        return out;
    }

    public boolean verbose;
    private long nextLog;

    /** Collects a trace of the sets that first reach the goal, when not null. */
    public StringBuilder trace;
}
