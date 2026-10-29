package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Training data for the learned guide (WS13): the moves between platforms that solutions made, and the
 * moves the A* aimed at and did or did not make ({@link Recorder}).
 */
public final class GuideData {
    /** One move between platforms, with its features ({@link GuideFeatures}). */
    static final class Sample {
        final String problem;
        final double[] f;
        /** 1: made (by a solution or by the search), 0: aimed at often and never made. */
        final int made;
        /** Ticks from the takeoff to the landing (solutions only, else -1). */
        final int ticks;
        /** Ticks the solution still took after the landing, and the bound there (solutions only). */
        final int left;
        final double bound;
        /** How often the search aimed at the move, and how often it made it (search records only). */
        final int tries, hits;

        Sample(String problem, double[] f, int made, int ticks, int left, double bound, int tries, int hits) {
            this.problem = problem;
            this.f = f;
            this.made = made;
            this.ticks = ticks;
            this.left = left;
            this.bound = bound;
            this.tries = tries;
            this.hits = hits;
        }

        String csv() {
            StringBuilder b = new StringBuilder(problem);
            for (double v : f) b.append(',').append(String.format(Locale.ROOT, "%.4f", v));
            b.append(',').append(made).append(',').append(ticks).append(',').append(left).append(',')
                .append(String.format(Locale.ROOT, "%.2f", bound)).append(',').append(tries).append(',').append(hits);
            return b.toString();
        }
    }

    static String header() {
        return "problem," + String.join(",", GuideFeatures.NAMES) + ",made,ticks,left,bound,tries,hits";
    }

    private GuideData() {
    }

    /**
     * The moves between platforms of a solution: replays it, and at each landing on another platform
     * takes the move from the last surface stood on before the takeoff. Returns null when the replay
     * does not finish (the solution is from another engine build that moves differently).
     */
    static List<Sample> fromSolution(Problem problem, List<TickInput> inputs) {
        Course c = new Course(problem);
        Run r = Run.spawn(c);
        List<Sample> out = new ArrayList<Sample>();
        List<int[]> landings = new ArrayList<int[]>();
        List<Course.Surface[]> moves = new ArrayList<Course.Surface[]>();
        List<Double> bounds = new ArrayList<Double>();
        Course.Surface last = null;
        int lastTick = 0;
        int platform = -1;
        for (TickInput t : inputs) {
            r.step(PhysicsModel.toPhysics(t));
            if (r.dead) return null;
            if (r.done()) break;
            if (!r.s.onGround) continue;
            Course.Surface s = c.support(r.s.posX, r.s.posY, r.s.posZ);
            if (s == null) continue;
            if (last != null && s.platform != platform && platform >= 0) {
                landings.add(new int[] {r.tick - lastTick, r.clock(), r.entry < 0 ? 0 : 1});
                moves.add(new Course.Surface[] {from(c, last, s), s});
                bounds.add(c.toGo(r.s.posX, r.s.posY, r.s.posZ));
            }
            platform = s.platform;
            last = s;
            lastTick = r.tick;
        }
        if (!r.done()) return null;
        int total = r.time();
        for (int i = 0; i < moves.size(); i++) {
            int[] l = landings.get(i);
            int left = l[2] == 1 ? total - l[1] : -1;
            out.add(new Sample(problem.id, GuideFeatures.of(c, moves.get(i)[0], moves.get(i)[1]), 1, l[0], left, bounds.get(i), 0, 0));
        }
        return out;
    }

    /**
     * Where the solution's next platform ranks among the planner's aims at each landing: the rank of the
     * first aim on it (0 first), or -1 when no aim is on it. With {@code targets} aims, as the A* has.
     */
    static List<int[]> aimRanks(Problem problem, List<TickInput> inputs, int targets) {
        Course c = new Course(problem);
        Planner pl = new Planner(c);
        pl.targets = targets;
        Run r = Run.spawn(c);
        List<Run> at = new ArrayList<Run>();
        List<Integer> next = new ArrayList<Integer>();
        int platform = -1;
        for (TickInput t : inputs) {
            r.step(PhysicsModel.toPhysics(t));
            if (r.dead) return null;
            if (r.done()) break;
            int q = c.platformAt(r.s);
            if (q < 0 || q == platform) continue;
            if (!at.isEmpty()) next.add(q);
            at.add(r.copy());
            platform = q;
        }
        List<int[]> out = new ArrayList<int[]>();
        for (int i = 0; i < next.size(); i++) {
            List<double[]> aims = pl.aims(at.get(i));
            int rank = -1, k = 0;
            java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
            for (double[] a : aims) {
                Course.Surface s = c.support(a[0], a[2], a[1]);
                if (s == null) continue;
                if (!seen.add(s.platform)) continue;
                if (s.platform == next.get(i)) {
                    rank = k;
                    break;
                }
                k++;
            }
            out.add(new int[] {rank, aims.size()});
        }
        return out;
    }

    /** The surface of {@code last}'s platform the move to {@code b} is best taken from: on the optimistic graph if it has the move. */
    private static Course.Surface from(Course c, Course.Surface last, Course.Surface b) {
        if (last.next.contains(b)) return last;
        Course.Surface best = last;
        double gap = Double.POSITIVE_INFINITY;
        for (Course.Surface a : c.platforms.get(last.platform)) {
            if (!a.next.contains(b)) continue;
            double g = Course.rectGap(a, b);
            if (g < gap) {
                gap = g;
                best = a;
            }
        }
        return best;
    }

    /**
     * What a search aimed at and made: per pair of platforms, how many expansions on the first aimed at
     * a surface of the second, and how many of them landed there.
     */
    static final class Recorder {
        final Map<Long, int[]> pairs = new HashMap<Long, int[]>();

        synchronized void expanded(Course c, Run from, List<double[]> aims, List<Maneuver.Successor> succ) {
            if (!from.s.onGround) return;
            int p = c.platformAt(from.s);
            if (p < 0) return;
            java.util.Set<Integer> aimed = new java.util.HashSet<Integer>();
            for (double[] a : aims) {
                Course.Surface s = c.support(a[0], a[2], a[1]);
                if (s != null && s.platform != p) aimed.add(s.platform);
            }
            java.util.Set<Integer> made = new java.util.HashSet<Integer>();
            for (Maneuver.Successor x : succ) {
                int q = c.platformAt(x.run.s);
                if (q >= 0 && q != p) made.add(q);
            }
            for (int q : aimed) {
                int[] e = pairs.computeIfAbsent(key(p, q), k -> new int[2]);
                e[0]++;
                if (made.contains(q)) e[1]++;
            }
        }

        /** The {@link GuideFeatures#node} features of each successor the current search generated. */
        private final java.util.IdentityHashMap<Planner.Node, double[]> feats = new java.util.IdentityHashMap<Planner.Node, double[]>();
        /** The nodes the current search generated (true) and expanded (false), in order. */
        private final List<Planner.Node> events = new ArrayList<Planner.Node>();
        private final List<Boolean> generatedAt = new ArrayList<Boolean>();
        /** Only searches with this heuristic weight are kept (the greedy first round's). */
        double keepWeight = 6;
        /** At most this many pairs per search. */
        int maxPairs = 20000;
        /**
         * Pairs from the searches that found a plan: the node of the plan that was waiting in the open
         * list, and a node off the plan that the search expanded meanwhile (which it should not have
         * taken first), or one generated meanwhile and never expanded (which it rightly left). Each is
         * {clock g, bound h, features...} for the plan's node, then the same for the other.
         */
        final List<double[][]> nodePairs = new ArrayList<double[][]>();
        private final Random rnd = new Random(5);

        synchronized void child(Planner.Node child, double[] f) {
            feats.put(child, f);
            events.add(child);
            generatedAt.add(true);
        }

        synchronized void popped(Planner.Node n) {
            events.add(n);
            generatedAt.add(false);
        }

        /** The search with heuristic weight {@code weight} ended with plan {@code goal} (or none). */
        synchronized void found(Planner.Node goal, double weight, Course c) {
            if (goal != null && weight == keepWeight) {
                List<Planner.Node> path = new ArrayList<Planner.Node>();
                for (Planner.Node k = goal; k != null; k = k.parent) path.add(k);
                java.util.Collections.reverse(path);
                java.util.IdentityHashMap<Planner.Node, Integer> onPath = new java.util.IdentityHashMap<Planner.Node, Integer>();
                for (int i = 0; i < path.size(); i++) onPath.put(path.get(i), i);
                // The plan's node waiting at each event: the one after the deepest plan node expanded so far.
                int pending = -1;
                java.util.IdentityHashMap<Planner.Node, Integer> bornWith = new java.util.IdentityHashMap<Planner.Node, Integer>();
                java.util.Set<Planner.Node> expanded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Planner.Node, Boolean>());
                List<double[][]> mine = new ArrayList<double[][]>(), left = new ArrayList<double[][]>();
                for (int e = 0; e < events.size(); e++) {
                    Planner.Node q = events.get(e);
                    Integer i = onPath.get(q);
                    if (generatedAt.get(e)) {
                        if (i == null) bornWith.put(q, pending);
                        continue;
                    }
                    expanded.add(q);
                    if (i != null) {
                        pending = Math.max(pending, i + 1);
                        continue;
                    }
                    add(mine, path, pending, q);
                }
                for (Map.Entry<Planner.Node, Integer> e : bornWith.entrySet()) if (!expanded.contains(e.getKey())) add(left, path, e.getValue(), e.getKey());
                java.util.Collections.shuffle(left, rnd);
                mine.addAll(left.subList(0, Math.min(left.size(), mine.size())));
                java.util.Collections.shuffle(mine, rnd);
                nodePairs.addAll(mine.subList(0, Math.min(maxPairs, mine.size())));
            }
            feats.clear();
            events.clear();
            generatedAt.clear();
        }

        private void add(List<double[][]> to, List<Planner.Node> path, int pending, Planner.Node q) {
            if (pending <= 0 || pending >= path.size()) return;
            Planner.Node pn = path.get(pending);
            double[] fp = feats.get(pn), fq = feats.get(q);
            if (fp != null && fq != null) to.add(new double[][] {row(pn, fp), row(q, fq)});
        }

        private static double[] row(Planner.Node n, double[] f) {
            double[] r = new double[f.length + 2];
            r[0] = n.g;
            r[1] = n.h;
            System.arraycopy(f, 0, r, 2, f.length);
            return r;
        }

        String nodeCsv(String problem) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < nodePairs.size(); i++) {
                for (int j = 0; j < 2; j++) {
                    b.append(problem).append(',').append(i).append(',').append(j == 0 ? 1 : 0);
                    for (double v : nodePairs.get(i)[j]) b.append(',').append(String.format(Locale.ROOT, "%.4f", v));
                    b.append('\n');
                }
            }
            return b.toString();
        }

        static String nodeHeader() {
            return "problem,pair,plan,g,h," + String.join(",", GuideFeatures.NODE_NAMES);
        }

        static long key(int p, int q) {
            return ((long) p << 32) | (q & 0xFFFFFFFFL);
        }

        /**
         * The pairs aimed at at least {@code minTries} times: made if any expansion landed there. The
         * features are those of the step move between the two platforms with the most margin.
         */
        List<Sample> samples(Course c, String problem, int minTries) {
            List<Sample> out = new ArrayList<Sample>();
            for (Map.Entry<Long, int[]> e : pairs.entrySet()) {
                int p = (int) (e.getKey() >>> 32), q = (int) (long) e.getKey();
                int[] v = e.getValue();
                if (v[1] == 0 && v[0] < minTries) continue;
                double[] best = null;
                for (Course.Surface a : c.platforms.get(p)) {
                    for (Course.Surface b : a.next) {
                        if (b.platform != q || a.notByStep.contains(b)) continue;
                        double[] f = GuideFeatures.of(c, a, b);
                        if (best == null || f[2] > best[2]) best = f;
                    }
                }
                if (best == null) continue;
                out.add(new Sample(problem, best, v[1] > 0 ? 1 : 0, -1, -1, 0, v[0], v[1]));
            }
            return out;
        }
    }
}
