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

    public PlannerSolver(Problem problem, long budgetMillis) {
        this.problem = problem;
        this.budgetMillis = budgetMillis;
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
        List<Runup.Root> roots = Runup.roots(course);
        List<Runup.Root> picked = ROOTS > 0 ? new Planner(course).pickRoots(roots, ROOTS) : roots;
        say(roots.size() + " run-up roots, searching from " + picked.size() + " (" + (System.currentTimeMillis() - t0) + " ms)");
        Planner.Plan best = null;
        long foundAt = -1;
        // Rounds of growing breadth: a greedy first round for a plan soon, then each round bounded by
        // the best time with a lower weight, more footholds to aim for, finer speed states and wider
        // jump branching, until the budget runs out. Each new plan is tightened.
        for (int level = 0; System.currentTimeMillis() < deadline; level++) {
            Planner planner = new Planner(course);
            planner.weight = level == 0 ? 3.0 : level == 1 ? 1.6 : 1.0;
            planner.targets = Math.min(4 + level, 10);
            planner.window = 4 + 2 * level;
            planner.posCell = 0.2 / (1 + 0.5 * level);
            planner.velCell = 0.04 / (1 + 0.5 * level);
            planner.far = 1.5 + 0.5 * level;
            long until = System.currentTimeMillis() + (deadline - System.currentTimeMillis()) / 2;
            Planner.Plan p = planner.search(picked, until, best == null ? Integer.MAX_VALUE : best.time);
            say("A* level " + level + ": " + (p == null ? "no better plan" : p.time + " ticks, " + p.moves.size() + " moves")
                + ", " + planner.expanded + " expanded, " + planner.generated + " generated (" + (System.currentTimeMillis() - t0) + " ms)");
            if (p == null) continue;
            best = p;
            foundAt = System.currentTimeMillis() - t0;
            Tightener tight = new Tightener(roots);
            long tUntil = System.currentTimeMillis() + (deadline - System.currentTimeMillis()) / 3;
            Planner.Plan q = tight.tighten(best, tUntil);
            say("tightened: " + q.time + " ticks (" + tight.kept + " of " + tight.tried + " changes kept, " + (System.currentTimeMillis() - t0) + " ms)");
            if (q.time < best.time) {
                best = q;
                foundAt = System.currentTimeMillis() - t0;
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
