package io.github.konqasasas.mctas.improve;

import io.github.konqasasas.mctas.physics.PlayerState;

import java.util.Random;

/**
 * Joins two runs where they pass through nearly the same state: the inputs of {@code a} up to a
 * tick, then the inputs of {@code b} from its closest state on. Two runs that are each fast in a
 * different part of the course can make a run that is fast in both.
 */
final class Crossover {
    /** Largest state distance ({@link #distance}) at which two runs are joined. */
    double maxDistance = 0.25;

    Run apply(Course c, Run a, Run b, Random rnd) {
        if (a == b) return null;
        Run best = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            int lo = Math.max(1, a.startTick - 5);
            if (a.goalTick - lo < 3) return null;
            int i = lo + rnd.nextInt(a.goalTick - lo - 1);
            PlayerState s = a.states[i];
            int bestJ = -1;
            double bestD = maxDistance;
            for (int j = Math.max(1, b.startTick - 5); j < b.goalTick; j++) {
                double d = distance(s, b.states[j]);
                if (d < bestD) {
                    bestD = d;
                    bestJ = j;
                }
            }
            if (bestJ < 0) continue;
            java.util.List<io.github.konqasasas.mctas.physics.PlayerInput> mid = java.util.Collections.emptyList();
            Run r = c.evaluate(a, i, Edits.splice(a.inputs, i, mid, b.inputs, bestJ));
            if (r.betterThan(best)) best = r;
        }
        return best;
    }

    /** Position difference plus 5 times the velocity difference, or infinity when one stands and the other does not. */
    static double distance(PlayerState p, PlayerState q) {
        if (p.onGround != q.onGround || p.sprinting != q.sprinting || p.height != q.height) return Double.POSITIVE_INFINITY;
        double dx = p.posX - q.posX, dy = p.posY - q.posY, dz = p.posZ - q.posZ;
        double vx = p.motionX - q.motionX, vy = p.motionY - q.motionY, vz = p.motionZ - q.motionZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) + 5 * Math.sqrt(vx * vx + vy * vy + vz * vz);
    }
}
