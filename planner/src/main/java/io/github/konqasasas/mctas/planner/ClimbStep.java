package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.List;

/**
 * A move through ladders, vines or water onto a foothold: run towards the foothold and jump after
 * {@code jumpAt} ground ticks, then, while in a climbable block, push towards the foothold, sneaking to
 * hold the height when the foothold is still far for the height left (sliding down a vine at 0.15 a
 * tick would lose it) and not sneaking when it must climb or may drop. Ends on the first landing on
 * another platform; a landing back on the start platform, the void or running out of ticks is a failure.
 */
final class ClimbStep implements Move {
    static final int MAX_TICKS = 240;
    final double aimX, aimZ, top;
    final double[] region;
    final int jumpAt;

    ClimbStep(double aimX, double aimZ, double top, double[] region, int jumpAt) {
        this.aimX = aimX;
        this.aimZ = aimZ;
        this.top = top;
        this.region = region;
        this.jumpAt = jumpAt;
    }

    @Override
    public void play(Run r, List<PlayerInput> out) {
        Course c = r.course;
        int from = c.platformAt(r.s);
        int ground = 0;
        boolean left = false;
        for (int t = 0; t < MAX_TICKS; t++) {
            if (r.over()) return;
            PlayerInput a = r.legal(control(r, ground));
            r.step(a);
            out.add(a);
            if (r.dead || r.done()) return;
            if (!r.s.onGround) {
                left = true;
                continue;
            }
            ground++;
            int p = c.platformAt(r.s);
            if (left && p >= 0 && p != from) return;
            if (left && (p == from || ground > Step.MAX_GROUND)) break;
        }
        r.dead = true;
    }

    private PlayerInput control(Run r, int ground) {
        double tx = Math.max(region[0], Math.min(region[1], aimX)), tz = Math.max(region[2], Math.min(region[3], aimZ));
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx - r.s.posX), tz - r.s.posZ));
        if (r.s.onGround) {
            int keys = PlayerInput.W | PlayerInput.SPRINT;
            if (ground >= jumpAt) keys |= PlayerInput.JUMP;
            return new PlayerInput(keys, yaw, 0F);
        }
        if (r.course.onLadder(r.s) || r.s.inWater) {
            double dx = Math.max(0, Math.max(region[0] - r.s.posX, r.s.posX - region[1]));
            double dz = Math.max(0, Math.max(region[2] - r.s.posZ, r.s.posZ - region[3]));
            double d = Math.sqrt(dx * dx + dz * dz);
            double above = r.s.posY - top;
            boolean sneak = !r.s.inWater && d > 0.3 && above > -0.5 && above < d + 0.5;
            int keys = PlayerInput.W | (sneak ? PlayerInput.SNEAK : PlayerInput.SPRINT);
            if (r.s.inWater && above < 0.5) keys |= PlayerInput.JUMP;
            return new PlayerInput(keys, yaw, 0F);
        }
        return new PlayerInput(PlayerInput.W | PlayerInput.SPRINT, yaw, 0F);
    }

    @Override
    public String toString() {
        return String.format("climb to (%.2f, %.2f, %.2f) jump at %d", aimX, top, aimZ, jumpAt);
    }
}
