package io.github.konqasasas.mctas.approx;

/**
 * Closed-form-ish sprint movement on stone-like ground and in the air, for the estimates: no
 * collisions, the keys always pushing in the direction of travel.
 */
final class Flight {
    /** Sprinting ground acceleration with a 45-degree strafe: 0.13 * 0.16277136 / 0.546^3. */
    static final double GROUND_ACCEL = 0.13;
    static final double GROUND_FRICTION = 0.6 * 0.91;
    static final double AIR_ACCEL = 0.026;
    static final double AIR_FRICTION = 0.91;
    static final double JUMP_BOOST = 0.2;

    private Flight() {
    }

    /**
     * The tick (counting the jump tick as 1) on which a jump from standing height comes down through
     * {@code dy} above the takeoff, or -1 when the jump never gets that high.
     */
    static int landingTick(double dy) {
        double y = 0, my = 0.42;
        for (int k = 1; k <= 200; k++) {
            double py = y;
            y += my;
            my = (my - 0.08) * 0.9800000190734863;
            if (y < py && py >= dy && y <= dy) return k;
            if (y < py && py < dy) return -1;
        }
        return -1;
    }

    /** Horizontal distance after {@code ticks} ticks of a sprint jump taken with ground speed {@code u}. */
    static double jumpDistance(double u, int ticks) {
        if (ticks <= 0) return 0;
        double m = u + JUMP_BOOST + GROUND_ACCEL;
        double d = m;
        m *= GROUND_FRICTION;
        for (int i = 2; i <= ticks; i++) {
            m += AIR_ACCEL;
            d += m;
            m *= AIR_FRICTION;
        }
        return d;
    }
}
