package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hierarchical planner end to end: the foothold graph and its lower bounds ({@link Course}), the
 * run-up roots ({@link Runup}), A* over landings with a loose heuristic weight ({@link Planner}), the
 * tick-level re-join ({@link Tightener}), then a second, stricter A* bounded by the best time so far.
 */
public final class PlannerSolver {
    public static final String ENGINE = "planner-v1";
    /** Run-up roots the A* starts from, when set (0: all of them; the tightener always tries all). */
    static final int ROOTS = Integer.getInteger("planner.roots", 0);
    /**
     * Heuristic weight of the first, greedy round: a plan within seconds on the long courses, which
     * bounds the rounds after it (weights 3, 1.6, then 1).
     */
    static final double FIRST_WEIGHT = Double.parseDouble(System.getProperty("planner.firstWeight", "6.0"));
    /** Experiment knobs: the weights after the first round, and the jump branching of each round. */
    static final double[] WEIGHTS = parse(System.getProperty("planner.weights", "3,1.6,1"));
    static final double FAR = Double.parseDouble(System.getProperty("planner.far", "1.5"));
    static final double FAR_STEP = Double.parseDouble(System.getProperty("planner.farStep", "0.5"));
    static final int TARGETS_STEP = Integer.getInteger("planner.targetsStep", 1);
    /** From this round on, aim only for the platforms of the plans found so far. */
    static final int CORRIDOR_FROM = Integer.getInteger("planner.corridorFrom", Integer.MAX_VALUE);

    private static double[] parse(String s) {
        String[] p = s.split(",");
        double[] d = new double[p.length];
        for (int i = 0; i < p.length; i++) d[i] = Double.parseDouble(p[i].trim());
        return d;
    }

    public static final class Solution {
        public final List<TickInput> inputs;
        public final int ticks;
        public final long foundMillis;

        Solution(List<TickInput> inputs, int ticks, long foundMillis) {
            this.inputs = inputs;
            this.ticks = ticks;
            this.foundMillis = foundMillis;
        }
    }

    private final Problem problem;
    private final long budgetMillis;
    private PrintStream log;
    private boolean segments = true;

    public PlannerSolver(Problem problem, long budgetMillis) {
        this.problem = problem;
        this.budgetMillis = budgetMillis;
    }

    /** Re-search the plan's moves with the local technique search (WS8). */
    public PlannerSolver segments(boolean on) {
        this.segments = on;
        return this;
    }

    public PlannerSolver log(PrintStream out) {
        this.log = out;
        return this;
    }

    private void say(String s) {
        if (log != null) log.println("  " + s);
    }

    public Solution solve() {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + budgetMillis;
        Course course = new Course(problem);
        Run spawn = Run.spawn(course);
        Course.Surface under = course.support(spawn.s.posX, spawn.s.posY, spawn.s.posZ);
        say(course.surfaces.size() + " footholds, " + course.platforms.size() + " platforms, bound from spawn "
            + String.format("%.1f", course.toGo(spawn.s.posX, spawn.s.posY, spawn.s.posZ)) + " ticks"
            + (under == null ? " (spawn not on a foothold)" : "") + " (" + (System.currentTimeMillis() - t0) + " ms)");
        if (Planner.DEBUG) for (Map.Entry<Integer, List<Course.Hole>> e : course.holes.entrySet()) say("holes in platform " + e.getKey() + ": " + e.getValue());
        if (Planner.DEBUG && under != null && under.toGo == Double.POSITIVE_INFINITY) gap(course, under);
        List<Runup.Root> roots = Runup.roots(course);
        List<Runup.Root> picked = ROOTS > 0 ? new Planner(course).pickRoots(roots, ROOTS) : roots;
        say(roots.size() + " run-up roots, searching from " + picked.size() + " (" + (System.currentTimeMillis() - t0) + " ms)");
        Planner.Plan best = null;
        long foundAt = -1;
        boolean segmentsDone = false;
        java.util.Set<Integer> corridor = new java.util.HashSet<Integer>();
        // Rounds of growing breadth: a greedy first round for a plan soon, then each round bounded by
        // the best time with a lower weight, more footholds to aim for, finer speed states and wider
        // jump branching, until the budget runs out. Each new plan is tightened.
        for (int level = 0; System.currentTimeMillis() < deadline; level++) {
            Planner planner = new Planner(course);
            planner.weight = level == 0 ? FIRST_WEIGHT : WEIGHTS[Math.min(level, WEIGHTS.length) - 1];
            planner.targets = Math.min(4 + TARGETS_STEP * level, 10);
            planner.window = 4 + 2 * level;
            planner.posCell = 0.2 / (1 + 0.5 * level);
            planner.velCell = 0.04 / (1 + 0.5 * level);
            planner.far = FAR + FAR_STEP * level;
            // The greedy first round hands most of the time on once it has a plan.
            if (level == 0) planner.afterFirst = 0.2;
            if (level >= CORRIDOR_FROM && !corridor.isEmpty()) planner.corridor = corridor;
            // Until there is a plan, a round keeps the whole budget: restarting a long course from
            // scratch with a stricter search only throws the frontier away. After the greedy round's
            // plan, the next round (weight 3) takes most of what is left: it is the one that finds the
            // good plans on the short courses (F-518).
            long left = deadline - System.currentTimeMillis();
            long until = best == null ? deadline : System.currentTimeMillis() + (level == 1 ? left * 2 / 3 : left / 2);
            Planner.Plan p = planner.search(picked, until, best == null ? Integer.MAX_VALUE : best.time);
            say("A* level " + level + ": " + (p == null ? "no better plan" : p.time + " ticks, " + p.moves.size() + " moves")
                + ", " + planner.expanded + " expanded, " + planner.generated + " generated (" + (System.currentTimeMillis() - t0) + " ms)");
            if (Planner.DEBUG) for (Map.Entry<String, Long> e : planner.maneuverNanos.entrySet()) say("  " + e.getKey() + " " + e.getValue() / 1000000 + " ms");
            if (p != null) {
                corridor.addAll(p.platforms);
                best = p;
                foundAt = System.currentTimeMillis() - t0;
                segmentsDone = false;
                Tightener tight = new Tightener(roots);
                long tUntil = System.currentTimeMillis() + (deadline - System.currentTimeMillis()) / 3;
                Planner.Plan q = tight.tighten(best, tUntil);
                say("tightened: " + q.time + " ticks (" + tight.kept + " of " + tight.tried + " changes kept, " + (System.currentTimeMillis() - t0) + " ms)");
                if (q.time < best.time) {
                    best = q;
                    foundAt = System.currentTimeMillis() - t0;
                }
            }
            // The segment re-search is slow: not on the greedy round's plan, which the next round
            // usually beats, but on the best plan after each later round that has not had it yet.
            if (segments && best != null && !segmentsDone && level >= 1) {
                segmentsDone = true;
                Tightener seg = new Tightener(roots);
                long sUntil = System.currentTimeMillis() + (deadline - System.currentTimeMillis()) / 2;
                long perCall = Math.max(200, (sUntil - System.currentTimeMillis()) / Math.max(1, 2 * best.moves.size()));
                Planner.Plan q = seg.segments(best, sUntil, perCall);
                say("technique re-search: " + q.time + " ticks (" + seg.kept + " of " + seg.tried + " segments kept, " + (System.currentTimeMillis() - t0) + " ms)");
                if (q.time < best.time) {
                    best = q;
                    foundAt = System.currentTimeMillis() - t0;
                    Tightener tight = new Tightener(roots);
                    q = tight.tighten(best, System.currentTimeMillis() + (deadline - System.currentTimeMillis()) / 3);
                    if (q.time < best.time) best = q;
                }
            }
        }
        if (best == null) return null;
        List<TickInput> ticks = toTicks(best.inputs);
        RunResult r = Evaluator.run(problem, ticks);
        if (!r.finished() || r.ticks() != best.time || !ToggleRule.violations(problem, ticks).isEmpty()) {
            throw new IllegalStateException("judge gives " + r.status + " " + r.ticks() + ", planner said " + best.time
                + "; toggles " + ToggleRule.violations(problem, ticks));
        }
        return new Solution(ticks.subList(0, r.goalTick), best.time, foundAt);
    }

    /**
     * Where the foothold graph breaks (with {@link Planner#DEBUG}): the footholds reachable from the
     * spawn that come nearest the goal, and the nearest footholds to them from which the goal is reachable.
     */
    private void gap(Course course, Course.Surface from) {
        java.util.ArrayDeque<Course.Surface> q = new java.util.ArrayDeque<Course.Surface>();
        Set<Course.Surface> seen = new java.util.HashSet<Course.Surface>();
        q.add(from);
        seen.add(from);
        while (!q.isEmpty()) {
            Course.Surface a = q.poll();
            for (Course.Surface b : a.next) if (seen.add(b)) q.add(b);
        }
        double gx = (problem.goal.minX + problem.goal.maxX) / 2, gy = problem.goal.minY, gz = (problem.goal.minZ + problem.goal.maxZ) / 2;
        // Progress along the line from the spawn to the goal, footholds above the spawn's lowest floor only.
        final double ux = gx - from.cx(), uz = gz - from.cz(), ul = Math.hypot(ux, uz);
        double floor = Double.POSITIVE_INFINITY;
        for (Course.Surface a : course.surfaces) floor = Math.min(floor, a.top);
        final double low = floor;
        // Above the floor under the course (and a step on it).
        List<Course.Surface> reach = new ArrayList<Course.Surface>();
        for (Course.Surface a : seen) if (a.top > low + 1.5) reach.add(a);
        reach.sort((a, b) -> Double.compare(((b.cx() - from.cx()) * ux + (b.cz() - from.cz()) * uz) / ul, ((a.cx() - from.cx()) * ux + (a.cz() - from.cz()) * uz) / ul));
        say(seen.size() + " footholds reachable from the spawn; above the lowest floor, furthest on towards the goal:");
        for (Course.Surface a : reach.subList(0, Math.min(5, reach.size()))) {
            Course.Surface near = null;
            double nd = Double.POSITIVE_INFINITY;
            for (Course.Surface b : course.surfaces) {
                if (b.toGo == Double.POSITIVE_INFINITY) continue;
                double d = Math.hypot(Math.hypot(a.cx() - b.cx(), a.cz() - b.cz()), a.top - b.top);
                if (d < nd) {
                    nd = d;
                    near = b;
                }
            }
            say(String.format("  %s top %.3f (%d moves out); nearest with a way on: %s top %.3f, %.2f away", a, a.top, a.next.size(), near, near == null ? 0 : near.top, nd));
        }
        List<Course.Surface> on = new ArrayList<Course.Surface>();
        for (Course.Surface b : course.surfaces) if (b.toGo < Double.POSITIVE_INFINITY) on.add(b);
        on.sort((a, b) -> Double.compare(b.toGo, a.toGo));
        if (System.getenv("PLANNER_GAP_BOX") != null) {
            String[] b = System.getenv("PLANNER_GAP_BOX").split(",");
            double x0 = Double.parseDouble(b[0]), x1 = Double.parseDouble(b[1]), z0 = Double.parseDouble(b[2]), z1 = Double.parseDouble(b[3]);
            for (Course.Surface a : reach) if (a.cx() >= x0 && a.cx() <= x1 && a.cz() >= z0 && a.cz() <= z1) say(String.format("  in box: %s top %.3f", a, a.top));
        }
        say(on.size() + " footholds with a way to the goal; furthest back:");
        for (Course.Surface b : on.subList(0, Math.min(8, on.size()))) say(String.format("  %s top %.3f toGo %.1f", b, b.top, b.toGo));
    }

    static TickInput toTick(PlayerInput a) {
        Set<Key> k = EnumSet.noneOf(Key.class);
        if (a.has(PlayerInput.W)) k.add(Key.W);
        if (a.has(PlayerInput.A)) k.add(Key.A);
        if (a.has(PlayerInput.S)) k.add(Key.S);
        if (a.has(PlayerInput.D)) k.add(Key.D);
        if (a.has(PlayerInput.JUMP)) k.add(Key.JUMP);
        if (a.has(PlayerInput.SPRINT)) k.add(Key.SPRINT);
        if (a.has(PlayerInput.SNEAK)) k.add(Key.SNEAK);
        return new TickInput(k, a.yaw, a.pitch);
    }

    static List<TickInput> toTicks(List<PlayerInput> in) {
        List<TickInput> out = new ArrayList<TickInput>(in.size());
        for (PlayerInput a : in) out.add(toTick(a));
        return out;
    }
}
