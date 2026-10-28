package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.PlayerState;

/** Where a technique ends: the first tick whose state satisfies {@link #reached}. */
public interface Exit {

    /** True when the tick that turned {@code before} into {@code after} completes the technique. */
    boolean reached(PlayerState before, PlayerState after);

    /**
     * How close {@code s} is to the exit, larger is closer, for ranking the beam. {@code along} is the
     * distance from the entry along the search heading, which is the default.
     */
    default double progress(PlayerState s, double along) {
        return along;
    }

    /**
     * A plane across the heading: the exit is reached on the first tick whose position is at least
     * {@code distance} past {@code (originX, originZ)} along the heading, with feet at or above
     * {@code minY}. With {@code onGround}, the player must also be standing.
     */
    static Exit plane(double originX, double originZ, float headingYaw, double distance, double minY,
                      boolean onGround) {
        double r = Math.toRadians(headingYaw);
        double dx = -Math.sin(r), dz = Math.cos(r);
        return (before, after) -> (after.posX - originX) * dx + (after.posZ - originZ) * dz >= distance
                && after.posY >= minY && (!onGround || after.onGround);
    }

    /**
     * The feet point inside an axis-aligned box, corners included (the problem format's goal test).
     * Ranked by the horizontal distance to the box, so the beam steers sideways into it too.
     */
    static Exit box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new Exit() {
            @Override
            public boolean reached(PlayerState before, PlayerState after) {
                return after.posX >= minX && after.posX <= maxX && after.posY >= minY && after.posY <= maxY
                        && after.posZ >= minZ && after.posZ <= maxZ;
            }

            @Override
            public double progress(PlayerState s, double along) {
                return -distance(s.posX, s.posZ, minX, maxX, minZ, maxZ);
            }
        };
    }

    /** Standing on the target's top face with the feet point over it: the first tick of the landing. */
    static Exit landing(Target t) {
        return new Exit() {
            @Override
            public boolean reached(PlayerState before, PlayerState after) {
                return after.onGround && Math.abs(after.posY - t.topY) < 1e-6 && after.posX >= t.minX
                        && after.posX <= t.maxX && after.posZ >= t.minZ && after.posZ <= t.maxZ;
            }

            @Override
            public double progress(PlayerState s, double along) {
                return -distance(s.posX, s.posZ, t.minX, t.maxX, t.minZ, t.maxZ);
            }
        };
    }

    /** Horizontal distance from a point to a rectangle (0 inside). */
    static double distance(double x, double z, double minX, double maxX, double minZ, double maxZ) {
        double ex = Math.max(0, Math.max(minX - x, x - maxX));
        double ez = Math.max(0, Math.max(minZ - z, z - maxZ));
        return Math.sqrt(ex * ex + ez * ez);
    }
}
