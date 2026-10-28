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
    /** The cells from the entry to the exit, each touching the next, or null to head straight for the exit. */
    final List<int[]> path;

    ClimbStep(int[] entry, int[] exit, double top, double[] region, int jumpAt) {
        this(entry, exit, top, region, jumpAt, null);
    }

    ClimbStep(int[] entry, int[] exit, double top, double[] region, int jumpAt, List<int[]> path) {
        this(entry, exit, top, region, jumpAt, path, null);
    }

    /** The body's cells ({@link Course#key}), or null: the climb starts at the first ladder or water touched. */
    final java.util.Set<Long> body;

    ClimbStep(int[] entry, int[] exit, double top, double[] region, int jumpAt, List<int[]> path, java.util.Set<Long> body) {
        this(entry, exit, top, region, jumpAt, path, body, null);
    }

    /**
     * A point to run to before the entry cell, or null: in front of the open side of a body set in a
     * slot, which the straight way to the entry would hit the side of.
     */
    final double[] via;

    ClimbStep(int[] entry, int[] exit, double top, double[] region, int jumpAt, List<int[]> path, java.util.Set<Long> body, double[] via) {
        this.via = via;
        this.body = body;
        this.path = path;
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
        int next = 1;
        double lastY = r.s.posY;
        for (int t = 0; t < MAX_TICKS; t++) {
            if (r.over()) return;
            boolean in = c.onLadder(r.s) || r.s.inWater;
            if (leg == 0 && in && (body == null || inBody(r))) leg = 1;
            // Off at the exit, or where the climb stops near it (a ceiling over the ladder).
            boolean stuck = in && !r.s.inWater && t > 0 && r.s.posY - lastY < 0.01 && r.s.posY > exit[1] - 1;
            if (leg == 1 && (atExit(r) || stuck || (!in && left && r.s.posY > exit[1] + 0.5))) leg = 2;
            lastY = r.s.posY;
            if (path != null) while (next < path.size() - 1 && touches(r, path.get(next))) next++;
            PlayerInput a = r.legal(path != null && leg == 1 ? swim(r, path.get(Math.min(next, path.size() - 1)), in) : control(r, ground, leg, in));
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
            if (left && ground > Step.MAX_GROUND) break;
        }
        r.dead = true;
    }

    /** In a cell of the body: the feet in one, or the part of the box that feels water touching one. */
    boolean inBody(Run r) {
        if (body.contains(Course.key((int) Math.floor(r.s.posX), (int) Math.floor(r.s.posY), (int) Math.floor(r.s.posZ)))) return true;
        if (!r.s.inWater) return false;
        for (int x = (int) Math.floor(r.s.posX - 0.3); x <= (int) Math.floor(r.s.posX + 0.3); x++) {
            for (int y = (int) Math.floor(r.s.posY + 0.4); y <= (int) Math.floor(r.s.posY + 1.4); y++) {
                for (int z = (int) Math.floor(r.s.posZ - 0.3); z <= (int) Math.floor(r.s.posZ + 0.3); z++) {
                    if (body.contains(Course.key(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    /** The part of the player's box that feels water (feet + 0.4 to feet + 1.4) meets the cell. */
    static boolean touches(Run r, int[] c) {
        return r.s.posX + 0.3 > c[0] && r.s.posX - 0.3 < c[0] + 1 && r.s.posZ + 0.3 > c[2] && r.s.posZ - 0.3 < c[2] + 1
            && r.s.posY + 1.4 > c[1] && r.s.posY + 0.4 < c[1] + 1;
    }

    /** Along the path: towards the next cell's middle, swimming up unless the cell is below the feet. */
    private PlayerInput swim(Run r, int[] c, boolean in) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(c[0] + 0.5 - r.s.posX), c[2] + 0.5 - r.s.posZ));
        int keys = PlayerInput.W | PlayerInput.SPRINT;
        if (c[1] + 0.6 > r.s.posY) keys |= PlayerInput.JUMP;
        return new PlayerInput(keys, yaw, 0F);
    }

    private boolean atExit(Run r) {
        return Math.abs(r.s.posX - (exit[0] + 0.5)) < 0.7 && Math.abs(r.s.posZ - (exit[2] + 0.5)) < 0.7 && r.s.posY >= exit[1] - 0.2;
    }

    private PlayerInput control(Run r, int ground, int leg, boolean in) {
        double tx, tz;
        if (leg == 0) {
            tx = entry[0] + 0.5;
            tz = entry[2] + 0.5;
            if (via != null && Math.hypot(tx - r.s.posX, tz - r.s.posZ) > Math.hypot(tx - via[0], tz - via[1]) + 0.1) {
                tx = via[0];
                tz = via[1];
            }
        } else if (leg == 1) {
            // Up a ladder: push into the block it hangs on, which is what climbs, and keep in the
            // ladder's column: when the drift along the ladder would carry the player out, steer back.
            double[] push = r.course.push(exit);
            tx = exit[0] + 0.5 + push[0];
            tz = exit[2] + 0.5 + push[1];
            if (in && (push[0] != 0 || push[1] != 0)) {
                double e = push[0] != 0 ? r.s.posZ - tz : r.s.posX - tx, v = push[0] != 0 ? r.s.motionZ : r.s.motionX;
                double ahead = e + 3 * v;
                double side = Math.abs(ahead) > 0.15 ? -Math.signum(ahead) : -e;
                if (push[0] != 0) {
                    tx = exit[0] + 0.5 + 0.3 * push[0];
                    tz = r.s.posZ + side;
                } else {
                    tz = exit[2] + 0.5 + 0.3 * push[1];
                    tx = r.s.posX + side;
                }
                if (Math.abs(ahead) <= 0.15) {
                    tx = push[0] != 0 ? exit[0] + 0.5 + push[0] : tx;
                    tz = push[1] != 0 ? exit[2] + 0.5 + push[1] : tz;
                }
            }
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
            entry[0], entry[1], entry[2], exit[0], exit[1], exit[2], top, jumpAt)
            + (via == null ? "" : String.format(" by (%.2f, %.2f)", via[0], via[1]));
    }
}
