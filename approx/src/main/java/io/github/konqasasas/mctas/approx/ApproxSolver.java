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
    public static final String ENGINE = "approx-beam-v4";
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
    private boolean fixedYawLanes;
    private List<TickInput> seed;

    public ApproxSolver(Problem problem, long budgetMillis) {
        this.problem = problem;
        this.budgetMillis = budgetMillis;
    }

    /** Also search keys only at a fixed yaw (off by default: it has not paid off yet, F-024). */
    public ApproxSolver fixedYawLanes(boolean on) {
        this.fixedYawLanes = on;
        return this;
    }

    /** A known solution to start from (a human run, say): the search polishes it and tries to beat it. */
    public ApproxSolver seed(List<TickInput> inputs) {
        this.seed = inputs;
        return this;
    }

    public ApproxSolver log(PrintStream out) {
        this.log = out;
        return this;
    }

    private void say(String s) {
        if (log != null) log.println("  " + s);
    }

    /** Yaws for the keys-only lanes: the spawn's, and 45 degrees off the start-to-goal heading (strafing straight). */
    private List<Float> fixedYaws() {
        List<Float> out = new ArrayList<Float>();
        out.add(wrap(problem.spawn.yaw));
        double sx = (problem.start.minX + problem.start.maxX) / 2, sz = (problem.start.minZ + problem.start.maxZ) / 2;
        double gx = (problem.goal.minX + problem.goal.maxX) / 2, gz = (problem.goal.minZ + problem.goal.maxZ) / 2;
        float heading = (float) Math.toDegrees(Math.atan2(-(gx - sx), gz - sz));
        float strafe = wrap(heading + 45F);
        if (Math.abs(wrap(strafe - out.get(0))) > 1F) out.add(strafe);
        return out;
    }

    private static float wrap(float yaw) {
        yaw %= 360F;
        if (yaw >= 180F) yaw -= 360F;
        if (yaw < -180F) yaw += 360F;
        return yaw;
    }

    public Solution solve() {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + budgetMillis;
        // Routes planned with less and more reach to spare: the bold one may take a shortcut that the
        // physics cannot quite make, the careful one goes round it. The check for walls in a jump's
        // way is a guess along a straight line, so each also comes without it when that differs.
        List<Route> routes = new ArrayList<Route>();
        Set<String> seen = new HashSet<String>();
        // Also with surfaces kept clear of the walls beside them, for courses with trunks and posts
        // standing in the way.
        for (int kind = 0; kind < 3; kind++) {
            for (double slack : SLACKS) {
                Route route = new Route(problem, slack, kind == 2, kind == 1);
                if (seen.add(route.toString())) {
                    routes.add(route);
                    say("route (slack " + slack + (kind == 1 ? ", clear of walls" : kind == 2 ? ", through walls" : "") + "): "
                        + (route.found() ? route.toString() : "none found, heading straight for the goal"));
                }
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
        if (seed != null) {
            List<PlayerInput> in = new ArrayList<PlayerInput>();
            for (TickInput ti : seed) in.add(io.github.konqasasas.mctas.problem.judge.PhysicsModel.toPhysics(ti));
            int ticks = Rollout.ticks(problem, in, new int[1]);
            if (ticks < 0) {
                say("seed: does not finish under the rules; ignored");
            } else {
                say("seed: " + ticks + " ticks");
                if (ticks < bestTicks) {
                    best = in;
                    bestTicks = ticks;
                    foundAt = System.currentTimeMillis() - t0;
                }
                // Polish the seed along the route the pilot liked best, with up to half the budget.
                int r0 = 0;
                for (int i = 1; i < routes.size(); i++) if (pilotTicks[i] < pilotTicks[r0]) r0 = i;
                long left = deadline - System.currentTimeMillis();
                Refiner.Outcome o = new Refiner(problem, routes.get(r0)).deadline(System.currentTimeMillis() + left / 2).improve(in, ticks);
                if (o.ticks < bestTicks) {
                    best = o.inputs;
                    bestTicks = o.ticks;
                    foundAt = System.currentTimeMillis() - t0;
                    say("seed refined: " + bestTicks + " ticks at " + foundAt + " ms");
                }
            }
        }
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < routes.size(); i++) order.add(i);
        order.sort((a, b) -> Integer.compare(pilotTicks[a], pilotTicks[b]));
        // Lanes: each route with free turning, then (when asked) the most promising route searched
        // with keys only at a fixed yaw (the spawn's, and strafing straight from start to goal).
        List<Integer> laneRoute = new ArrayList<Integer>();
        List<Float> laneYaw = new ArrayList<Float>();
        for (int i : order) {
            laneRoute.add(i);
            laneYaw.add(null);
        }
        for (float yaw : fixedYawLanes ? fixedYaws() : java.util.Collections.<Float>emptyList()) {
            laneRoute.add(order.get(0));
            laneYaw.add(yaw);
        }
        int[] stale = new int[laneRoute.size()];
        for (int width = 200; System.currentTimeMillis() < deadline; width *= 2) {
            boolean any = false;
            List<Integer> live = new ArrayList<Integer>();
            for (int i = 0; i < laneRoute.size(); i++) if (!(stale[i] >= 2 && best != null)) live.add(i);
            for (int k = 0; k < live.size() && System.currentTimeMillis() < deadline; k++) {
                int lane = live.get(k);
                any = true;
                Route route = routes.get(laneRoute.get(lane));
                String name = "route " + laneRoute.get(lane) + (laneYaw.get(lane) == null ? "" : " yaw " + laneYaw.get(lane));
                // Share what is left of the budget between the lanes still to run this round.
                long now0 = System.currentTimeMillis();
                long until = now0 + (deadline - now0) / (live.size() - k);
                TickBeam.Result r = new TickBeam(problem, route, width).fixedYaw(laneYaw.get(lane)).deadline(until).run(bestTicks);
                long now = System.currentTimeMillis() - t0;
                if (r != null && r.ticks < bestTicks) {
                    best = r.inputs;
                    bestTicks = r.ticks;
                    foundAt = now;
                    stale[lane] = 0;
                    say(name + " beam " + width + ": " + bestTicks + " ticks at " + now + " ms");
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
                    stale[lane]++;
                    say(name + " beam " + width + ": no improvement (" + now + " ms)");
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
