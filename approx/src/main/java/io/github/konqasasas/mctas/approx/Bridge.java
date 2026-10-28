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
 * The pilot along a route, with the stretches it cannot fly handed to the segment search of the
 * technique module: from where the pilot last landed, the fastest way onto the next waypoint (or into
 * the goal). For ladders, vines, posts and water, where the pilot's rules give out.
 */
final class Bridge {
    private final Problem problem;
    private final Route route;
    private final Pilot pilot;
    private PrintStreamLog log;

    interface PrintStreamLog {
        void say(String s);
    }

    Bridge(Problem problem, Route route) {
        this.problem = problem;
        this.route = route;
        this.pilot = new Pilot(problem, route);
    }

    Bridge log(PrintStreamLog log) {
        this.log = log;
        return this;
    }

    /** Inputs from the spawn into the goal, or null when a stretch is not found before the deadline. */
    List<PlayerInput> run(long deadlineMillis, long segmentMillis) {
        List<PlayerInput> inputs = new ArrayList<PlayerInput>();
        PlayerState s = Rollout.spawn(problem);
        boolean inStart = problem.start.contains(s.posX, s.posY, s.posZ);
        int entry = inStart ? 0 : -1;
        int lastToggle = Integer.MIN_VALUE / 2;
        int stage = 0;
        int tick = 0;
        while (System.currentTimeMillis() < deadlineMillis && tick < problem.maxTicks) {
            Pilot.Run r = pilot.play(s, tick, stage, entry, inStart, lastToggle, 1, problem.maxTicks - tick, true);
            if (r.finished) {
                inputs.addAll(r.inputs);
                return inputs;
            }
            if (!r.failed && r.stage > stage) {
                inputs.addAll(r.inputs);
                s = r.end;
                tick += r.ticks;
                stage = r.stage;
                entry = r.entry;
                inStart = r.inStart;
                lastToggle = r.lastToggle;
                continue;
            }
            // The pilot does not make it to the next waypoint: search the stretch.
            Route.Waypoint w = route.waypoints.get(stage);
            final int from = stage;
            Exit exit = exit(from, s, false);
            double[] aim = new double[2];
            route.remaining(s.posX, s.posZ, from, aim);
            float heading = Technique.headingTo(s, aim[0], aim[1]);
            SegmentSearch.Options o = new SegmentSearch.Options();
            o.maxTicks = 80;
            o.sneak = true;
            o.voidY = problem.voidY;
            long budget = Math.min(segmentMillis, deadlineMillis - System.currentTimeMillis());
            if (budget <= 0) return null;
            int since = Math.max(0, Math.min(1000, tick - lastToggle));
            List<Technique.Segment> segs = Technique.solve(problem.world, s, since, exit, heading, budget / 2, o);
            // Ranked by the distance still to go, the beam can crowd against an obstacle the
            // route string bends round; then rank by the distance along the heading instead.
            if (segs.isEmpty()) {
                budget = Math.min(budget / 2, deadlineMillis - System.currentTimeMillis());
                if (budget > 0) segs = Technique.solve(problem.world, s, since, exit(from, s, true), heading, budget, o);
            }
            if (segs.isEmpty()) {
                say("bridge: no way towards waypoint " + from + " from (" + fmt(s.posX) + ", " + fmt(s.posY) + ", " + fmt(s.posZ) + ")");
                return null;
            }
            Technique.Segment g = segs.get(0);
            say("bridge: towards waypoint " + from + " in " + g.ticks() + " ticks");
            // Replay to keep the start entry, the toggles and the stage.
            for (PlayerInput a : g.inputs) {
                tick++;
                PlayerState ns = Physics.tick(problem.world, s, a);
                if (ToggleRule.toggles(s, ns)) {
                    if (!ToggleRule.allowed(lastToggle, tick)) return null;
                    lastToggle = tick;
                }
                s = ns;
                inputs.add(a);
                boolean in = problem.start.contains(s.posX, s.posY, s.posZ);
                if (in && !inStart) entry = tick;
                inStart = in;
                if (entry >= 0 && problem.goal.contains(s.posX, s.posY, s.posZ)) return inputs;
            }
            int next = route.after(from, s.posX, s.posY, s.posZ, s.onGround);
            stage = next;
        }
        return null;
    }

    /** A stretch may cover at most this much of the route, so long walks are taken in pieces. */
    static final double STRETCH = 8;

    /**
     * Onto waypoint {@code stage} (or into the goal), or {@link #STRETCH} further along the route
     * standing on the ground; ranked by the distance still to go along the route.
     */
    private Exit exit(int stage, PlayerState from, boolean byHeading) {
        Route.Waypoint w = route.waypoints.get(stage);
        Region g = problem.goal;
        double target = route.remaining(from.posX, from.posZ, stage, null) - STRETCH;
        // Not by dropping below both where it starts and the waypoint ahead.
        double floor = Math.min(from.posY, w.goal ? g.minY : w.top) - 1e-6;
        return new Exit() {
            @Override
            public boolean reached(PlayerState before, PlayerState after) {
                if (w.goal) {
                    if (after.posX >= g.minX && after.posX <= g.maxX && after.posY >= g.minY && after.posY <= g.maxY
                            && after.posZ >= g.minZ && after.posZ <= g.maxZ) return true;
                } else if (route.landed(stage, after.posX, after.posY, after.posZ, after.onGround)) {
                    return true;
                }
                return after.onGround && !after.inWeb && after.posY >= floor
                        && route.remaining(after.posX, after.posZ, stage, null) <= target;
            }

            @Override
            public double progress(PlayerState s, double along) {
                double top = w.goal ? g.minY : w.top;
                double ahead = byHeading ? along : -route.remaining(s.posX, s.posZ, stage, null);
                return ahead - Exit.CLIMB_WEIGHT * Math.max(0, top - s.posY);
            }
        };
    }

    private void say(String s) {
        if (log != null) log.say(s);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
