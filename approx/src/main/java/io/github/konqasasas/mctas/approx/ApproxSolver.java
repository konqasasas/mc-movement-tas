package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;

import java.io.PrintStream;
import java.util.List;

/**
 * The anytime driver: plans a route, runs the tick beam with growing widths, and polishes each new
 * best with {@link Refiner}, until the time budget runs out or a wider beam stops paying off.
 */
public final class ApproxSolver {
    public static final String ENGINE = "approx-beam";

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

    public ApproxSolver(Problem problem, long budgetMillis) {
        this.problem = problem;
        this.budgetMillis = budgetMillis;
    }

    public ApproxSolver log(PrintStream out) {
        this.log = out;
        return this;
    }

    private void say(String s) {
        if (log != null) log.println("  " + s);
    }

    public Solution solve() {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + budgetMillis;
        Route route = new Route(problem);
        say("route: " + (route.found() ? route.toString() : "none found, heading straight for the goal"));
        List<PlayerInput> best = null;
        int bestTicks = Integer.MAX_VALUE;
        long foundAt = -1;
        int stale = 0;
        for (int width = 200; System.currentTimeMillis() < deadline; width *= 2) {
            TickBeam.Result r = new TickBeam(problem, route, width).deadline(deadline).run(bestTicks);
            long now = System.currentTimeMillis() - t0;
            if (r != null && r.ticks < bestTicks) {
                best = r.inputs;
                bestTicks = r.ticks;
                foundAt = now;
                stale = 0;
                say("beam " + width + ": " + bestTicks + " ticks at " + now + " ms");
                Refiner.Outcome o = new Refiner(problem, route).deadline(deadline).improve(best, bestTicks);
                if (o.ticks < bestTicks) {
                    best = o.inputs;
                    bestTicks = o.ticks;
                    foundAt = System.currentTimeMillis() - t0;
                    say("refined: " + bestTicks + " ticks at " + foundAt + " ms");
                }
            } else {
                stale++;
                say("beam " + width + ": no improvement (" + now + " ms)");
                if (stale >= 2 && best != null) break;
            }
        }
        if (best == null) return null;
        int[] goal = new int[1];
        int check = Rollout.ticks(problem, best, goal);
        if (check != bestTicks) throw new IllegalStateException("re-run gives " + check + " ticks, search said " + bestTicks);
        return new Solution(Rollout.toTicks(best.subList(0, goal[0])), bestTicks, foundAt);
    }
}
