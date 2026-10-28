package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The anytime driver: plans a route, runs the tick beam with growing widths, and polishes each new
 * best with {@link Refiner}, until the time budget runs out or a wider beam stops paying off.
 */
public final class ApproxSolver {
    public static final String ENGINE = "approx-beam-v2";
    /** Reach to spare on route jumps, one route per value. */
    static final double[] SLACKS = {0, 0.5, 1.0};

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
        // Routes planned with less and more reach to spare: the bold one may take a shortcut that the
        // physics cannot quite make, the careful one goes round it.
        List<Route> routes = new ArrayList<Route>();
        Set<String> seen = new HashSet<String>();
        for (double slack : SLACKS) {
            Route route = new Route(problem, slack);
            if (seen.add(route.toString())) {
                routes.add(route);
                say("route (slack " + slack + "): " + (route.found() ? route.toString() : "none found, heading straight for the goal"));
            }
        }
        List<PlayerInput> best = null;
        int bestTicks = Integer.MAX_VALUE;
        long foundAt = -1;
        // The pilot alone, from the spawn, along each route: a cheap first answer, and a hint which
        // routes the physics can actually fly. Routes it gets through go first.
        final int[] pilotTicks = new int[routes.size()];
        for (int i = 0; i < routes.size(); i++) {
            pilotTicks[i] = Integer.MAX_VALUE;
            Pilot.Run run = new Pilot(problem, routes.get(i)).play(Rollout.spawn(problem), 0, 0, -1, false,
                Integer.MIN_VALUE / 2, Integer.MAX_VALUE, problem.maxTicks, true);
            if (!run.finished) continue;
            int ticks = Rollout.ticks(problem, run.inputs, new int[1]);
            if (ticks < 0) continue;
            pilotTicks[i] = ticks;
            if (ticks < bestTicks) {
                best = run.inputs;
                bestTicks = ticks;
                foundAt = System.currentTimeMillis() - t0;
                say("route " + i + " pilot: " + ticks + " ticks");
            }
        }
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < routes.size(); i++) order.add(i);
        order.sort((a, b) -> Integer.compare(pilotTicks[a], pilotTicks[b]));
        int[] stale = new int[routes.size()];
        for (int width = 200; System.currentTimeMillis() < deadline; width *= 2) {
            boolean any = false;
            List<Integer> live = new ArrayList<Integer>();
            for (int i : order) if (!(stale[i] >= 2 && best != null)) live.add(i);
            for (int k = 0; k < live.size() && System.currentTimeMillis() < deadline; k++) {
                int i = live.get(k);
                any = true;
                Route route = routes.get(i);
                // Share what is left of the budget between the routes still to run this round.
                long now0 = System.currentTimeMillis();
                long until = now0 + (deadline - now0) / (live.size() - k);
                TickBeam.Result r = new TickBeam(problem, route, width).deadline(until).run(bestTicks);
                long now = System.currentTimeMillis() - t0;
                if (r != null && r.ticks < bestTicks) {
                    best = r.inputs;
                    bestTicks = r.ticks;
                    foundAt = now;
                    stale[i] = 0;
                    say("route " + i + " beam " + width + ": " + bestTicks + " ticks at " + now + " ms");
                    // Polishing gets a quarter of what is left; wider beams get the rest.
                    long left = deadline - System.currentTimeMillis();
                    Refiner.Outcome o = new Refiner(problem, route).deadline(System.currentTimeMillis() + left / 4).improve(best, bestTicks);
                    if (o.ticks < bestTicks) {
                        best = o.inputs;
                        bestTicks = o.ticks;
                        foundAt = System.currentTimeMillis() - t0;
                        say("refined: " + bestTicks + " ticks at " + foundAt + " ms");
                    }
                } else {
                    stale[i]++;
                    say("route " + i + " beam " + width + ": no improvement (" + now + " ms)");
                }
            }
            if (!any) break;
        }
        if (best == null) return null;
        int[] goal = new int[1];
        int check = Rollout.ticks(problem, best, goal);
        if (check != bestTicks) {
            io.github.konqasasas.mctas.problem.judge.RunResult rr = io.github.konqasasas.mctas.problem.judge.Evaluator.run(problem, Rollout.toTicks(best));
            throw new IllegalStateException("re-run gives " + check + " ticks, search said " + bestTicks + "; judge: " + rr.status
                + " inputs=" + best.size() + " toggles=" + io.github.konqasasas.mctas.problem.judge.ToggleRule.violations(problem, Rollout.toTicks(best)));
        }
        return new Solution(Rollout.toTicks(best.subList(0, goal[0])), bestTicks, foundAt);
    }
}
