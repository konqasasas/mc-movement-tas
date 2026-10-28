package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.List;

/**
 * One transition between footholds, as a small closed-loop controller: run towards an aim point
 * (straight, or strafing 45 degrees), jump after {@code jumpAt} ground ticks (facing the aim, for the
 * sprint-jump boost), then keep steering towards the aim in the air until landing. With
 * {@code jumpAt < 0} it never jumps: it walks onto another platform or runs off the edge.
 *
 * <p>Because the controller steers from wherever the player is, a plan made of steps can be replayed
 * after an earlier step changes, which is what {@link Tightener} relies on.
 */
final class Step implements Move {
    static final int NO_JUMP = -1;
    /** Give up a step that is still on the ground after this many ticks. */
    static final int MAX_GROUND = 60;
    static final int MAX_AIR = 120;
    /** Horizontal acceleration of one sprinting, strafing air tick. */
    static final double AIR_ACCEL = 0.026;
    /** Land at least this far inside the region. */
    static final double LAND_MARGIN = 0.05;

    final double aimX, aimZ;
    /** Height of the top to land on at the aim: the air controller aims its landing there. */
    final double aimY;
    /** Where the player's centre may land to count (minX, maxX, minZ, maxZ), or null to land at the aim. */
    final double[] region;
    /** 0 straight, +1 strafe left (A, yaw turned 45 degrees right of the heading), -1 strafe right (D). */
    final int strafe;
    final int jumpAt;

    Step(double aimX, double aimY, double aimZ, double[] region, int strafe, int jumpAt) {
        this.region = region;
        this.aimX = aimX;
        this.aimY = aimY;
        this.aimZ = aimZ;
        this.strafe = strafe;
        this.jumpAt = jumpAt;
    }

    Step withJump(int j) {
        return new Step(aimX, aimY, aimZ, region, strafe, j);
    }

    Step withStrafe(int s) {
        return new Step(aimX, aimY, aimZ, region, s, jumpAt);
    }

    Step withAim(double x, double z) {
        return new Step(x, aimY, z, region, strafe, jumpAt);
    }

    static float heading(double fromX, double fromZ, double toX, double toZ) {
        return (float) Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
    }

    /**
     * The input for a running or flying tick. On the ground it runs at the aim. In the air it steers
     * the landing: it predicts where the player will be when the fall crosses the aim's top (coasting
     * from here, air drag 0.91 a tick; the landing is decided before that tick's horizontal move, L-013)
     * and pushes towards the miss, so that the landing closes in on the aim instead of overshooting.
     */
    PlayerInput move(Run r) {
        double tx = aimX, tz = aimZ;
        if (!r.s.onGround) {
            int k = movesBeforeLanding(r.s.posY, r.s.motionY, aimY);
            if (k > 0) {
                // Coasting landing point, and how far a full push now moves it.
                double vx = r.s.motionX, vz = r.s.motionZ, px = r.s.posX, pz = r.s.posZ, f = 1, gain = 0;
                for (int i = 0; i < k; i++) {
                    px += vx * f;
                    pz += vz * f;
                    gain += f;
                    f *= 0.91;
                }
                double dx = aimX - px, dz = aimZ - pz, len = Math.hypot(dx, dz);
                double wantX = aimX, wantZ = aimZ;
                if (region != null && len > 1e-9) {
                    // Push on towards the aim, but land inside the region: aim for the pushed landing,
                    // pulled back into the region when it would overshoot.
                    double push = Math.min(len, gain * AIR_ACCEL);
                    wantX = clamp(px + dx / len * push, region[0] + LAND_MARGIN, region[1] - LAND_MARGIN);
                    wantZ = clamp(pz + dz / len * push, region[2] + LAND_MARGIN, region[3] - LAND_MARGIN);
                    if (Math.hypot(wantX - px, wantZ - pz) < 1e-6) {
                        wantX = aimX;
                        wantZ = aimZ;
                    }
                }
                tx = r.s.posX + (wantX - px);
                tz = r.s.posZ + (wantZ - pz);
            }
        }
        float h = heading(r.s.posX, r.s.posZ, tx, tz);
        int keys = PlayerInput.W | PlayerInput.SPRINT;
        if (strafe > 0) {
            keys |= PlayerInput.A;
            h += 45F;
        } else if (strafe < 0) {
            keys |= PlayerInput.D;
            h -= 45F;
        }
        return r.legal(new PlayerInput(keys, h, r.s.rotationPitch));
    }

    /**
     * Horizontal moves left before the tick whose vertical move falls through {@code top}, or 0 when
     * that is this tick, or -1 when the fall never gets there from above (the top is out of reach).
     */
    private static double clamp(double v, double lo, double hi) {
        return lo > hi ? (lo + hi) / 2 : v < lo ? lo : v > hi ? hi : v;
    }

    static int movesBeforeLanding(double y, double vy, double top) {
        for (int k = 0; k < 200; k++) {
            if (vy < 0 && y < top - 1e-9) return -1;
            if (vy < 0 && y + vy <= top + 1e-9) return k;
            y += vy;
            vy = (vy - 0.08) * 0.98;
        }
        return -1;
    }

    /** The jump input: facing the aim, so that the sprint-jump boost points there. */
    PlayerInput jump(Run r) {
        float h = heading(r.s.posX, r.s.posZ, aimX, aimZ);
        return r.legal(new PlayerInput(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, h, r.s.rotationPitch));
    }

    /** Flies until landing (or the run ends), recording the inputs. */
    void air(Run r, List<PlayerInput> out) {
        for (int i = 0; i < MAX_AIR && !r.s.onGround && !r.over(); i++) tick(r, move(r), out);
        if (!r.s.onGround && !r.over()) r.dead = true;
    }

    static void tick(Run r, PlayerInput a, List<PlayerInput> out) {
        r.step(a);
        out.add(a);
    }

    /**
     * Plays the step from {@code r} (changed in place) and appends its inputs. Returns normally when
     * the player lands, walks onto another platform, or the run ends; {@code r.dead} marks failure.
     */
    @Override
    public void play(Run r, List<PlayerInput> out) {
        if (!r.s.onGround) {
            air(r, out);
            return;
        }
        int platform = r.course.platformAt(r.s);
        for (int k = 0; ; k++) {
            if (k == jumpAt) {
                tick(r, jump(r), out);
                if (r.over()) return;
                air(r, out);
                return;
            }
            if (k >= MAX_GROUND) {
                r.dead = true;
                return;
            }
            tick(r, move(r), out);
            if (r.over()) return;
            if (!r.s.onGround) {
                air(r, out);
                return;
            }
            int now = r.course.platformAt(r.s);
            if (now >= 0 && now != platform) return;
        }
    }

    @Override
    public String toString() {
        return String.format("aim(%.2f,%.2f) strafe %d jump %d", aimX, aimZ, strafe, jumpAt);
    }
}
