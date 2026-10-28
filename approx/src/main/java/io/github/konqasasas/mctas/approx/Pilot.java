package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;

import java.util.ArrayList;
import java.util.List;

/**
 * A simple hand-written parkour policy, played forward with the real physics to score beam nodes
 * (the pilot method). It always holds sprint and strafes 45 degrees off the heading, so it never
 * toggles sprint or sneak on purpose. On the ground it runs at the route's aim point and jumps when
 * a jump from here would carry it onto the next waypoint, or on the last tick it can (the tick
 * after walking off an edge). In the air it steers its predicted landing point onto the waypoint,
 * braking by facing backwards when it would overshoot.
 *
 * <p>Because the policy is played with the real physics, a run that reaches the goal is a real
 * solution, and its length is an honest upper bound for the node it starts from.
 */
final class Pilot {
    /** Outcome of one rollout. */
    static final class Run {
        /** Ticks played. */
        int ticks;
        /** The state after the last tick played, and its stage and start entry tick. */
        PlayerState end;
        int stage;
        int entry;
        boolean inStart;
        int lastToggle;
        boolean finished;
        boolean failed;
        /** For a failed run: how far from the waypoint it came down through the waypoint's height. */
        double miss;
        /** The inputs played, when recorded. */
        List<PlayerInput> inputs;
    }

    private static final int HOP_TICKS = Flight.landingTick(0);
    private static final double LANDING_INSET = 0.2;

    private final Problem problem;
    private final Route route;
    /** The start box is too thin to enter in the air. */
    private final boolean flatStart;

    Pilot(Problem problem, Route route) {
        this.problem = problem;
        this.route = route;
        this.flatStart = problem.start.maxY - problem.start.minY < 0.5;
    }

    /**
     * Plays the policy from a state at tick {@code tick} until it has landed on {@code landings}
     * more waypoints, reached the goal, failed, or used {@code maxTicks}.
     */
    Run play(PlayerState start, int tick, int stage, int entry, boolean inStart, int lastToggle,
             int landings, int maxTicks, boolean record) {
        Run r = new Run();
        if (record) r.inputs = new ArrayList<PlayerInput>();
        PlayerState s = start.copy();
        Region goal = problem.goal;
        int stopStage = landings >= route.waypoints.size() - 1 - stage ? Integer.MAX_VALUE : stage + landings;
        double[] aim = new double[2];
        for (int k = 1; k <= maxTicks; k++) {
            int t = tick + k;
            PlayerInput a = decide(s, stage, entry >= 0, aim);
            // After the entry, a wall that stops the sprint must not make the next press break
            // the toggle rule. (Holding back before it too made the run-up beam dawdle on v1-dev.)
            if (entry >= 0) a = Rollout.holdSprint(s, a, lastToggle, t);
            PlayerState ns = Physics.tick(problem.world, s, a);
            if (ToggleRule.toggles(s, ns)) {
                if (!ToggleRule.allowed(lastToggle, t)) {
                    r.failed = true;
                    return finish(r, s, k - 1, stage, entry, inStart, lastToggle);
                }
                lastToggle = t;
            }
            if (!ns.onGround && ns.posY < s.posY) {
                double top = route.waypoints.get(stage).goal ? goal.minY : route.waypoints.get(stage).top;
                if (s.posY >= top && ns.posY < top) r.miss = route.missDistance(stage, s.posX, s.posZ);
            }
            s = ns;
            if (record) r.inputs.add(a);
            boolean in = problem.start.contains(s.posX, s.posY, s.posZ);
            if (in && !inStart) entry = t;
            inStart = in;
            if (entry >= 0 && goal.contains(s.posX, s.posY, s.posZ)) {
                r.finished = true;
                return finish(r, s, k, stage, entry, inStart, lastToggle);
            }
            int next = route.after(stage, s.posX, s.posY, s.posZ, s.onGround);
            if (next > stage) {
                stage = next;
                if (stage >= stopStage) return finish(r, s, k, stage, entry, inStart, lastToggle);
            }
            if (s.posY < problem.voidY || fallen(s, stage)) {
                r.failed = true;
                if (r.miss <= 0) r.miss = route.missDistance(stage, s.posX, s.posZ);
                return finish(r, s, k, stage, entry, inStart, lastToggle);
            }
        }
        return finish(r, s, maxTicks, stage, entry, inStart, lastToggle);
    }

    private static Run finish(Run r, PlayerState s, int ticks, int stage, int entry, boolean inStart, int lastToggle) {
        r.ticks = ticks;
        r.end = s;
        r.stage = stage;
        r.entry = entry;
        r.inStart = inStart;
        r.lastToggle = lastToggle;
        return r;
    }

    private boolean fallen(PlayerState s, int stage) {
        if (s.onGround || s.motionY >= 0 || s.inWater) return false;
        double ahead = route.waypoints.get(stage).top;
        double behind = stage == 0 ? route.startTop : route.waypoints.get(stage - 1).top;
        return s.posY < Math.min(ahead, behind) - 0.6 || s.posY < route.floorY - 1.0;
    }

    private PlayerInput decide(PlayerState s, int stage, boolean entered, double[] aim) {
        Route.Waypoint w = route.waypoints.get(stage);
        route.remaining(s.posX, s.posZ, stage, aim);
        double dx = aim[0] - s.posX, dz = aim[1] - s.posZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        float heading = d > 1e-6 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : velocityHeading(s);
        if (s.onGround || s.inWater) {
            double along = d > 1e-9 ? (s.motionX * dx + s.motionZ * dz) / d : 0;
            if (w.needsJump) {
                boolean supported = route.support(s.posX, s.posZ, s.posY - 1e-6, s.posY + 1e-6) != null;
                int landing = Flight.landingTick(w.top - s.posY);
                boolean reaches = landing > 0 && Flight.jumpDistance(Math.max(0, along), landing - 1) >= d;
                // Up against a step too high to walk onto: jump now.
                boolean blocked = w.top - s.posY > 0.6 && (s.collidedHorizontally || route.missDistance(stage, s.posX, s.posZ) < 0.35);
                if (reaches || !supported || blocked) return PlayerInput.of(TickBeam.JUMP_W, heading);
            }
            // Otherwise follow the route's ground. Sprint-jumping is faster than running: hop
            // whenever the hop lands on this same ground short of the aim.
            route.aimAlong(s.posX, s.posZ, stage, aim);
            double ax = aim[0] - s.posX, az = aim[1] - s.posZ;
            double ad = Math.sqrt(ax * ax + az * az);
            float runHeading = ad > 1e-6 ? (float) Math.toDegrees(Math.atan2(-ax, az)) : heading;
            double runAlong = ad > 1e-9 ? (s.motionX * ax + s.motionZ * az) / ad : 0;
            double hop = Flight.jumpDistance(Math.max(0, runAlong), HOP_TICKS - 1);
            // Before entering a start box that is only a block top, stay on the ground: a hop
            // could sail over it.
            if ((entered || !flatStart) && hop < d && ad > 1e-9 && route.support(s.posX + ax / ad * hop, s.posZ + az / ad * hop, s.posY - 1e-6, s.posY + 1e-6) != null) {
                return PlayerInput.of(TickBeam.JUMP_W, runHeading);
            }
            return PlayerInput.of(TickBeam.RUN, runHeading + 45F);
        }
        // In the air: where would the drift alone come down through the waypoint's height, and can
        // the keys still move that point onto the waypoint?
        double lowY = w.goal ? problem.goal.minY : w.top;
        double x = s.posX, y = s.posY, z = s.posZ, mx = s.motionX, my = s.motionY, mz = s.motionZ;
        double accel = s.jumpMovementFactor, reach = 0, reachSpeed = 0;
        boolean crosses = false;
        for (int k = 0; k < 40; k++) {
            double ox = x, oz = z, oreach = reach, py = y;
            reachSpeed += accel;
            reach += reachSpeed;
            reachSpeed *= 0.91;
            x += mx;
            y += my;
            z += mz;
            my = (my - 0.08) * 0.9800000190734863;
            mx *= 0.91;
            mz *= 0.91;
            if (y < py && py >= lowY && y <= lowY) {
                // Landing is decided by the position before this tick's horizontal move.
                crosses = route.missDistance(stage, ox, oz) <= oreach;
                x = ox;
                z = oz;
                break;
            }
            if (y < py && y < lowY) break;
        }
        if (!crosses) {
            // Cannot make the waypoint from here (a hop along the run-up, say): keep over the route's ground.
            route.aimAlong(s.posX, s.posZ, stage, aim);
            double ax = aim[0] - s.posX, az = aim[1] - s.posZ;
            float h = ax * ax + az * az > 1e-6 ? (float) Math.toDegrees(Math.atan2(-ax, az)) : heading;
            return PlayerInput.of(TickBeam.RUN, h + 45F);
        }
        // Steer the landing point a little inside the waypoint; once it is there, keep pushing on.
        route.nearestInside(stage, x, z, LANDING_INSET, aim);
        double lx = aim[0] - x, lz = aim[1] - z;
        if (lx * lx + lz * lz < 0.05 * 0.05) return PlayerInput.of(TickBeam.RUN, heading + 45F);
        float steer = (float) Math.toDegrees(Math.atan2(-lx, lz));
        return PlayerInput.of(TickBeam.RUN, steer + 45F);
    }

    private static float velocityHeading(PlayerState s) {
        if (s.motionX * s.motionX + s.motionZ * s.motionZ > 1e-8) return (float) Math.toDegrees(Math.atan2(-s.motionX, s.motionZ));
        return s.rotationYaw - 45F;
    }
}
