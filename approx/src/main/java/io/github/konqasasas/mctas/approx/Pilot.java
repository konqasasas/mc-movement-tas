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
        /** The inputs played, when recorded. */
        List<PlayerInput> inputs;
    }

    private final Problem problem;
    private final Route route;

    Pilot(Problem problem, Route route) {
        this.problem = problem;
        this.route = route;
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
        int stopStage = stage + landings >= route.waypoints.size() - 1 ? Integer.MAX_VALUE : stage + landings;
        double[] aim = new double[2];
        for (int k = 1; k <= maxTicks; k++) {
            PlayerInput a = decide(s, stage, aim);
            PlayerState ns = Physics.tick(problem.world, s, a);
            int t = tick + k;
            if (ToggleRule.toggles(s, ns)) {
                if (!ToggleRule.allowed(lastToggle, t)) {
                    r.failed = true;
                    return finish(r, s, k - 1, stage, entry, inStart, lastToggle);
                }
                lastToggle = t;
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
            if (stage < route.waypoints.size() - 1 && route.landed(stage, s.posX, s.posY, s.posZ, s.onGround)) {
                stage++;
                if (stage >= stopStage) return finish(r, s, k, stage, entry, inStart, lastToggle);
            }
            if (s.posY < problem.voidY || fallen(s, stage)) {
                r.failed = true;
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

    private PlayerInput decide(PlayerState s, int stage, double[] aim) {
        Route.Waypoint w = route.waypoints.get(stage);
        route.remaining(s.posX, s.posZ, stage, aim);
        double dx = aim[0] - s.posX, dz = aim[1] - s.posZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        float heading = d > 1e-6 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : velocityHeading(s);
        if (s.onGround || s.inWater) {
            if (!w.needsJump) return PlayerInput.of(TickBeam.RUN, heading + 45F);
            boolean supported = route.support(s.posX, s.posZ, s.posY - 1e-6, s.posY + 1e-6) != null;
            int landing = Flight.landingTick(w.top - s.posY);
            double along = d > 1e-9 ? (s.motionX * dx + s.motionZ * dz) / d : 0;
            boolean reaches = landing > 0 && Flight.jumpDistance(Math.max(0, along), landing - 1) >= d;
            if (reaches || !supported) return PlayerInput.of(TickBeam.JUMP_W, heading);
            return PlayerInput.of(TickBeam.RUN, heading + 45F);
        }
        // In the air: where would the drift alone land, and should the keys push forward or brake?
        double lowY = w.goal ? problem.goal.minY : w.top;
        double x = s.posX, y = s.posY, z = s.posZ, mx = s.motionX, my = s.motionY, mz = s.motionZ;
        for (int k = 0; k < 40; k++) {
            double py = y;
            x += mx;
            y += my;
            z += mz;
            my = (my - 0.08) * 0.9800000190734863;
            mx *= 0.91;
            mz *= 0.91;
            if (y < py && y <= lowY) break;
        }
        route.remaining(x, z, stage, aim);
        double lx = aim[0] - x, lz = aim[1] - z;
        if (lx * lx + lz * lz < 1e-6) {
            // The drift already lands on the waypoint: push towards the next aim to carry speed on.
            return PlayerInput.of(TickBeam.RUN, heading + 45F);
        }
        float steer = (float) Math.toDegrees(Math.atan2(-lx, lz));
        return PlayerInput.of(TickBeam.RUN, steer + 45F);
    }

    private static float velocityHeading(PlayerState s) {
        if (s.motionX * s.motionX + s.motionZ * s.motionZ > 1e-8) return (float) Math.toDegrees(Math.atan2(-s.motionX, s.motionZ));
        return s.rotationYaw - 45F;
    }
}
