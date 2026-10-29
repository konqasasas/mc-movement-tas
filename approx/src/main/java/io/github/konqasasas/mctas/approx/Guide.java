package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;
import io.github.konqasasas.mctas.technique.Exit;
import io.github.konqasasas.mctas.technique.SegmentSearch;
import io.github.konqasasas.mctas.technique.Technique;

import java.util.ArrayList;
import java.util.List;

/**
 * Segment search chained towards the goal without a route: each segment ends standing somewhere
 * the route graph's cost to the goal ({@link Route#costToGoal}) is clearly lower, and the beam ranks
 * states by that cost. For courses whose graph does not connect with plain jumps (a ladder to climb,
 * water to swim up), where the gaps are left to the search as {@link Route#searchEdges}.
 */
final class Guide {
    /** The cost drops a segment aims for, largest first: long segments are faster, short ones surer. */
    static final double[] STEPS = {60, 25, 8};
    static final float[] OFFSETS = {0, 4, -4, 10, -10, 22.5F, -22.5F, 45, -45, 90, -90, 135, -135, 180};

    private final Problem problem;
    private final Route route;
    private Bridge.PrintStreamLog log;

    Guide(Problem problem, Route route) {
        this.problem = problem;
        this.route = route;
    }

    Guide log(Bridge.PrintStreamLog log) {
        this.log = log;
        return this;
    }

    /** Inputs from the spawn into the goal, or null when the chain gets stuck before the deadline. */
    List<PlayerInput> run(long deadlineMillis, long segmentMillis) {
        List<PlayerInput> inputs = new ArrayList<PlayerInput>();
        PlayerState s = Rollout.spawn(problem);
        Region start = problem.start, goal = problem.goal;
        boolean inStart = start.contains(s.posX, s.posY, s.posZ);
        int entry = inStart ? 0 : -1;
        int lastToggle = Integer.MIN_VALUE / 2;
        int tick = 0;
        while (System.currentTimeMillis() < deadlineMillis && tick < problem.maxTicks) {
            int since = Math.max(0, Math.min(1000, tick - lastToggle));
            Technique.Segment g = null;
            if (entry < 0) {
                // First into the start box.
                g = first(s, since, Exit.box(start.minX, start.minY, start.minZ, start.maxX, start.maxY, start.maxZ),
                        (start.minX + start.maxX) / 2, (start.minZ + start.maxZ) / 2, deadlineMillis, segmentMillis);
                if (g == null) {
                    say("guide: cannot reach the start");
                    return null;
                }
            } else {
                double h0 = route.costToGoalAt(s.posX, s.posY, s.posZ, 4);
                if (System.getenv("GUIDE_MAP") != null) {
                    for (int dz = -4; dz <= 14; dz++) {
                        StringBuilder sb = new StringBuilder(String.format("%4d", (int) Math.floor(s.posZ) + dz));
                        for (int dx = -10; dx <= 10; dx++) {
                            double best = Double.POSITIVE_INFINITY; double top = 0;
                            for (double y = s.posY + 10; y >= s.posY - 6; y -= 0.5) {
                                double h = route.costToGoalAt(Math.floor(s.posX) + dx + 0.5, y, Math.floor(s.posZ) + dz + 0.5, 0.49);
                                if (h < best) { best = h; top = y; }
                            }
                            sb.append(best == Double.POSITIVE_INFINITY ? "     ." : String.format(" %3.0f%2.0f", Math.min(best, 999), top % 100));
                        }
                        System.err.println(sb);
                    }
                }
                for (double step : STEPS) {
                    double[] aim = aim(s, h0, step);
                    if (System.getenv("GUIDE_DEBUG") != null) System.err.println("  debug: step " + step + " aim " + aim[0] + "," + aim[1]
                        + " h0 " + h0 + " t " + System.currentTimeMillis());
                    g = first(s, since, exit(h0 - step), aim[0], aim[1], deadlineMillis, segmentMillis);
                    if (g != null) break;
                }
                if (g == null) {
                    say("guide: stuck at (" + fmt(s.posX) + ", " + fmt(s.posY) + ", " + fmt(s.posZ) + "), cost " + fmt(h0));
                    return null;
                }
            }
            for (PlayerInput a : g.inputs) {
                tick++;
                PlayerState ns = Physics.tick(problem.world, s, a);
                if (ToggleRule.toggles(s, ns)) {
                    if (!ToggleRule.allowed(lastToggle, tick)) return null;
                    lastToggle = tick;
                }
                s = ns;
                inputs.add(a);
                boolean in = start.contains(s.posX, s.posY, s.posZ);
                if (in && !inStart) entry = tick;
                inStart = in;
                if (entry >= 0 && goal.contains(s.posX, s.posY, s.posZ)) return inputs;
            }
            say("guide: " + g.ticks() + " ticks to (" + fmt(s.posX) + ", " + fmt(s.posY) + ", " + fmt(s.posZ) + "), cost "
                    + fmt(route.costToGoalAt(s.posX, s.posY, s.posZ, 4)));
        }
        return null;
    }

    private Technique.Segment first(PlayerState s, int since, Exit exit, double ax, double az, long deadlineMillis,
                                    long segmentMillis) {
        float heading = Technique.headingTo(s, ax, az);
        // A plain search around the heading first; then all round with sneaking, which costs about
        // three times as much a tick.
        for (int pass = 0; pass < 2; pass++) {
            long budget = Math.min(pass == 0 ? segmentMillis / 3 : segmentMillis, deadlineMillis - System.currentTimeMillis());
            if (budget <= 0) return null;
            SegmentSearch.Options o = new SegmentSearch.Options();
            o.maxTicks = pass == 0 ? 60 : 80;
            o.voidY = problem.voidY;
            if (pass == 1) {
                o.sneak = true;
                o.offsets = OFFSETS;
            }
            List<Technique.Segment> segs = Technique.solve(problem.world, s, since, exit, heading, budget, o);
            if (!segs.isEmpty()) return segs.get(0);
        }
        return null;
    }

    /** Standing where the cost is at most {@code target}, or in the goal; ranked by the cost where the player is. */
    private Exit exit(double target) {
        Region g = problem.goal;
        return new Exit() {
            @Override
            public boolean reached(PlayerState before, PlayerState after) {
                if (g.contains(after.posX, after.posY, after.posZ)) return true;
                return after.onGround && !after.inWeb && route.costToGoalAt(after.posX, after.posY, after.posZ, 1e-3) <= target;
            }

            @Override
            public double progress(PlayerState s, double along) {
                double h = route.costToGoalAt(s.posX, s.posY, s.posZ, 64);
                return h < Double.POSITIVE_INFINITY ? -h : -1e6 + along;
            }
        };
    }

    /**
     * The point to face: the centre of the nearest block column with a surface at least {@code step}
     * below {@code h0} in cost; the goal when there is none within 12 blocks.
     */
    private double[] aim(PlayerState s, double h0, double step) {
        Region g = problem.goal;
        double[] best = {(g.minX + g.maxX) / 2, (g.minZ + g.maxZ) / 2};
        double bestCost = Double.POSITIVE_INFINITY;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                double x = Math.floor(s.posX) + dx + 0.5, z = Math.floor(s.posZ) + dz + 0.5;
                for (double y = s.posY + 1.5; y >= s.posY - 10; y -= 0.5) {
                    double h = route.costToGoalAt(x, y, z, 0.99);
                    if (h > h0 - step) continue;
                    // The nearest such place: the far ones are often across a gap left to the search.
                    double c = Math.hypot(x - s.posX, z - s.posZ) + 0.01 * h;
                    if (c < bestCost) {
                        bestCost = c;
                        best = new double[] {x, z};
                    }
                }
            }
        }
        return best;
    }

    private void say(String s) {
        if (log != null) log.say(s);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
