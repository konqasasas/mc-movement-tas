package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.List;

/**
 * A move through a body of ladders, vines or water onto a foothold, in three legs: run to the body's
 * entry cell and jump after {@code jumpAt} ground ticks; inside the body, head for the exit cell,
 * climbing (pushing on, or swimming up) while below it, and sneaking to hold the height on a vine or
 * ladder when the way left is long for the height left (sliding down at 0.15 a tick would lose it);
 * from the exit cell, on to the foothold. Ends on the first landing on another platform; a landing
 * back on the start platform, the void or running out of ticks is a failure.
 */
final class ClimbStep implements Move {
    static final int MAX_TICKS = 300;
    /** Entry and exit cells of the body (block coordinates). */
    final int[] entry, exit;
    final double top;
    /** The foothold's feet area: minX, maxX, minZ, maxZ. */
    final double[] region;
    final int jumpAt;

    ClimbStep(int[] entry, int[] exit, double top, double[] region, int jumpAt) {
        this.entry = entry;
        this.exit = exit;
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
        int leg = 0;
        for (int t = 0; t < MAX_TICKS; t++) {
            if (r.over()) return;
            boolean in = c.onLadder(r.s) || r.s.inWater;
            if (leg == 0 && in) leg = 1;
            if (leg == 1 && (atExit(r) || (!in && left && r.s.posY > exit[1] + 0.5))) leg = 2;
            PlayerInput a = r.legal(control(r, ground, leg, in));
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
            if (left && (p == from && leg == 0 || ground > Step.MAX_GROUND)) break;
        }
        r.dead = true;
    }

    private boolean atExit(Run r) {
        return Math.abs(r.s.posX - (exit[0] + 0.5)) < 0.7 && Math.abs(r.s.posZ - (exit[2] + 0.5)) < 0.7 && r.s.posY >= exit[1] - 0.2;
    }

    private PlayerInput control(Run r, int ground, int leg, boolean in) {
        double tx, tz;
        if (leg == 0) {
            tx = entry[0] + 0.5;
            tz = entry[2] + 0.5;
        } else if (leg == 1) {
            tx = exit[0] + 0.5;
            tz = exit[2] + 0.5;
        } else {
            tx = Math.max(region[0], Math.min(region[1], exit[0] + 0.5));
            tz = Math.max(region[2], Math.min(region[3], exit[2] + 0.5));
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx - r.s.posX), tz - r.s.posZ));
        if (r.s.onGround && !in) {
            int keys = PlayerInput.W | PlayerInput.SPRINT;
            if (ground >= jumpAt) keys |= PlayerInput.JUMP;
            return new PlayerInput(keys, yaw, 0F);
        }
        if (in) {
            double goalY = leg == 1 ? exit[1] : top;
            double d = Math.hypot(tx - r.s.posX, tz - r.s.posZ);
            double above = r.s.posY - goalY;
            if (r.s.inWater) {
                int keys = PlayerInput.W | PlayerInput.SPRINT;
                if (above < 0.5 || leg == 2) keys |= PlayerInput.JUMP;
                return new PlayerInput(keys, yaw, 0F);
            }
            boolean sneak = d > 0.3 && above > -0.5 && above < d + 0.5;
            if (r.s.onGround) sneak = false;
            return new PlayerInput(PlayerInput.W | (sneak ? PlayerInput.SNEAK : PlayerInput.SPRINT), yaw, 0F);
        }
        return new PlayerInput(PlayerInput.W | PlayerInput.SPRINT, yaw, 0F);
    }

    @Override
    public String toString() {
        return String.format("climb via (%d %d %d)-(%d %d %d) onto top %.2f, jump at %d",
            entry[0], entry[1], entry[2], exit[0], exit[1], exit[2], top, jumpAt);
    }
}
