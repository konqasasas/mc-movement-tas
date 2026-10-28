package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.List;

/**
 * A drop through a hole in the floor ({@link Course.Hole}): on the ground, each tick takes whichever of
 * a few inputs towards the hole (sprinting, walking, sneaking, letting go) brings the player's
 * stopping point closest to the hole's middle, until the player falls in; then lets go until the
 * landing on the foothold below. A landing anywhere else or running out of ticks is a failure.
 */
final class HoleStep implements Move {
    static final int MAX_TICKS = 120;
    /** Ground ticks a slide at speed v still covers: friction 0.546 a tick, so v * 0.546 / (1 - 0.546). */
    static final double SLIDE = 1.2;
    final Course.Hole hole;

    HoleStep(Course.Hole hole) {
        this.hole = hole;
    }

    @Override
    public void play(Run r, List<PlayerInput> out) {
        boolean fell = false;
        for (int t = 0; t < MAX_TICKS; t++) {
            if (r.over()) return;
            PlayerInput a;
            if (!fell && r.s.onGround) {
                a = best(r);
            } else {
                fell = true;
                a = r.legal(new PlayerInput(0, r.s.rotationYaw, 0F));
            }
            r.step(a);
            out.add(a);
            if (r.dead || r.done()) return;
            if (fell && r.s.onGround) {
                if (Math.abs(r.s.posY - hole.below.top) > 1e-6) break;
                return;
            }
        }
        r.dead = true;
    }

    private PlayerInput best(Run r) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(hole.x - r.s.posX), hole.z - r.s.posZ));
        int[] options = {PlayerInput.W | PlayerInput.SPRINT, PlayerInput.W, PlayerInput.W | PlayerInput.SNEAK, 0};
        PlayerInput best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int keys : options) {
            PlayerInput a = r.legal(new PlayerInput(keys, yaw, 0F));
            Run c = r.copy();
            c.step(a);
            if (c.dead) continue;
            double score = c.s.onGround
                ? Math.hypot(c.s.posX + c.s.motionX * SLIDE - hole.x, c.s.posZ + c.s.motionZ * SLIDE - hole.z)
                : Math.hypot(c.s.posX - hole.x, c.s.posZ - hole.z) - 1;
            if (score < bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best == null ? r.legal(new PlayerInput(0, yaw, 0F)) : best;
    }

    @Override
    public String toString() {
        return "drop through the " + hole;
    }
}
