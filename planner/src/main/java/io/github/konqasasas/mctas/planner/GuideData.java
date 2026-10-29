package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /**
     * For each landing of a solution that stays on the ground two ticks or more before its next jump to
     * another platform: whether jumping at once (after 0 or 1 ground ticks, with the planner's step
     * controller aimed at where the solution landed next) also lands on that platform, and how many
     * ticks earlier. Each row: {ground ticks, ticks to the next landing, best ticks with a quick jump or -1}.
     */
    static List<int[]> quickJumps(Problem problem, List<TickInput> inputs) {
        Course c = new Course(problem);
        Run r = Run.spawn(c);
        List<Run> lands = new ArrayList<Run>();
        List<Integer> takeoff = new ArrayList<Integer>();
        boolean was = r.s.onGround;
        for (TickInput t : inputs) {
            Run before = r.copy();
            r.step(PhysicsModel.toPhysics(t));
            if (r.dead) return null;
            if (r.done()) break;
            if (r.s.onGround && !was && r.entry >= 0) lands.add(r.copy());
            if (!r.s.onGround && was && !lands.isEmpty() && takeoff.size() < lands.size()) takeoff.add(before.tick);
            was = r.s.onGround;
        }
        List<int[]> out = new ArrayList<int[]>();
        for (int i = 0; i + 1 < lands.size() && i < takeoff.size(); i++) {
            Run l = lands.get(i), n = lands.get(i + 1);
            int ground = takeoff.get(i) - l.tick + 1;
            int p0 = c.platformAt(l.s), p1 = c.platformAt(n.s);
            if (ground < 2 || p1 < 0 || p1 == p0) continue;
            Course.Surface s = c.support(n.s.posX, n.s.posY, n.s.posZ);
            int best = -1;
            for (int jumpAt = 0; jumpAt <= 1; jumpAt++) {
                for (int strafe : StepManeuver.ALL_STRAFES) {
                    Step st = new Step(n.s.posX, s.top, n.s.posZ, new double[] {s.minX, s.maxX, s.minZ, s.maxZ}, strafe, jumpAt);
                    Run x = l.copy();
                    st.play(x, new ArrayList<io.github.konqasasas.mctas.physics.PlayerInput>());
                    if (x.dead || !x.s.onGround || c.platformAt(x.s) != p1) continue;
                    int dt = x.tick - l.tick;
                    if (best < 0 || dt < best) best = dt;
                }
            }
            out.add(new int[] {ground, n.tick - l.tick, best});
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
