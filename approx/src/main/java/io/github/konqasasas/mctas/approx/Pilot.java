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
        // From before the start entry, the tick allowance counts from the entry: otherwise a later
        // entry would leave less of the course to play and look better than it is.
        int limit = maxTicks;
        int played = 0;
        for (int k = 1; k <= limit && tick + k <= problem.maxTicks; k++) {
            played = k;
            int t = tick + k;
            PlayerInput a = decide(s, stage, entry >= 0, aim);
            // After the entry, a wall that stops the sprint must not make the next press break
            // the toggle rule. (Holding back before it too made the run-up beam dawdle on v1-dev.)
            if (entry >= 0) a = Rollout.holdSprint(s, a, lastToggle, t);
            a = Rollout.holdSneak(s, a, lastToggle, t);
            PlayerState ns = Physics.tick(problem.world, s, a);
            if (ToggleRule.toggles(s, ns)) {
                if (!ToggleRule.allowed(lastToggle, t)) {
                    r.failed = true;
                    return finish(r, s, k - 1, stage, entry, inStart, lastToggle);
                }
                lastToggle = t;
            }
            if (Hazard.hurts(problem.world, s, ns)) {
                r.failed = true;
                return finish(r, s, k - 1, stage, entry, inStart, lastToggle);
            }
            if (!ns.onGround && ns.posY < s.posY) {
                double top = route.waypoints.get(stage).goal ? goal.minY : route.waypoints.get(stage).top;
                if (s.posY >= top && ns.posY < top) r.miss = route.missDistance(stage, s.posX, s.posZ);
            }
            s = ns;
            if (record) r.inputs.add(a);
            boolean in = problem.start.contains(s.posX, s.posY, s.posZ);
            if (in && !inStart) {
                if (entry < 0) limit = Math.max(limit, k + maxTicks);
                entry = t;
            }
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
            // Caught in a cobweb: the policy has no way out worth taking.
            if (s.posY < problem.voidY || fallen(s, stage) || s.inWeb) {
                r.failed = true;
                if (r.miss <= 0) r.miss = route.missDistance(stage, s.posX, s.posZ);
                return finish(r, s, k, stage, entry, inStart, lastToggle);
            }
        }
        return finish(r, s, played, stage, entry, inStart, lastToggle);
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

    /**
     * The heading to slide along an obstacle right ahead that a jump does not clear, towards the
     * nearer side where the body fits through; NaN when nothing blocks ahead or no side is free.
     */
    private float sidestep(PlayerState s, float heading) {
        double fx = -Math.sin(Math.toRadians(heading)), fz = Math.cos(Math.toRadians(heading));
        double lo = s.posY + 1.3, hi = s.posY + 1.8;
        // Blocked: something ahead still in the way at the height a jump lifts the feet to.
        if (!route.solidAt(s.posX + fx * 0.35, lo, s.posZ + fz * 0.35, hi, 0.29)) return Float.NaN;
        for (double k = 0.25; k <= 1.5; k += 0.25) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double lx = -fz * sign * k, lz = fx * sign * k;
                if (!route.solidAt(s.posX + lx + fx * 0.35, s.posY + 0.05, s.posZ + lz + fz * 0.35, hi, 0.29)) {
                    return heading + sign * 90F;
                }
            }
        }
        return Float.NaN;
    }

    /**
     * Right ahead, a surface of the route still to come, above step height and low enough for a
     * jump, with room above it.
     */
    private boolean stepAhead(PlayerState s, int stage, float heading) {
        double fx = -Math.sin(Math.toRadians(heading)), fz = Math.cos(Math.toRadians(heading));
        double x = s.posX + fx * 0.65, z = s.posZ + fz * 0.65;
        Route.Surface c = route.support(x, z, s.posY + 0.6, s.posY + 1.25);
        return c != null && route.ahead(stage, c) && !route.solidAt(s.posX + fx * 0.35, s.posY + 1.3, s.posZ + fz * 0.35, c.top + 1.8, 0.29);
    }

    /** In ladders or vines, sneak once the feet are this little above the platform ahead. */
    static final double CLIMB_HOLD = 0.35;

    private PlayerInput decide(PlayerState s, int stage, boolean entered, double[] aim) {
        Route.Waypoint w = route.waypoints.get(stage);
        route.remaining(s.posX, s.posZ, stage, aim);
        double dx = aim[0] - s.posX, dz = aim[1] - s.posZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        float heading = d > 1e-6 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : velocityHeading(s);
        if (w.viaClimb && route.holding(s.posX, s.posY, s.posZ) && !s.onGround) {
            // Holding on in ladders or vines on the way: move along the route, sneaking whenever
            // the feet sink near the height of the platform ahead so as not to drop below it.
            route.aimAlong(s.posX, s.posZ, stage, aim);
            double ax = aim[0] - s.posX, az = aim[1] - s.posZ;
            float h = ax * ax + az * az > 1e-6 ? (float) Math.toDegrees(Math.atan2(-ax, az)) : heading;
            if (s.posY < w.top + CLIMB_HOLD) return PlayerInput.of(PlayerInput.W | PlayerInput.SNEAK, h);
            return PlayerInput.of(TickBeam.RUN, h + 45F);
        }
        if (s.onGround) {
            // Pressed against something too high to step or jump onto (a post, say): slide along
            // it towards the nearer side where the body fits past it.
            float side = sidestep(s, heading);
            if (!Float.isNaN(side)) return PlayerInput.of(TickBeam.RUN, side + 45F);
            // Stopped against a step of the route that a jump clears: jump.
            if (s.collidedHorizontally && stepAhead(s, stage, heading)) return PlayerInput.of(TickBeam.JUMP_W, heading);
        }
        if (s.onGround || s.inWater) {
            double along = d > 1e-9 ? (s.motionX * dx + s.motionZ * dz) / d : 0;
            if (w.needsJump) {
                boolean supported = route.support(s.posX, s.posZ, s.posY - 1e-6, s.posY + 1e-6) != null;
                int landing = Flight.landingTick(w.top - s.posY);
                boolean reaches = landing > 0 && Flight.jumpDistance(Math.max(0, along), landing - 1) >= d;
                // Up against the next step and it is too high to walk onto: jump now. A wall further
                // from the step is something else in the way, and jumping onto it can strand us.
                double miss = route.missDistance(stage, s.posX, s.posZ);
                boolean blocked = w.top - s.posY > 0.6 && (miss < 0.35 || s.collidedHorizontally && miss < 1.2);
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
