package io.github.konqasasas.mctas.planner;

/**
 * The features of an optimistic move between platforms (WS13): from surface {@code a} to surface
 * {@code b} on another platform. They only look at the course around the move, not at the course as a
 * whole, so that a model fitted on some courses carries over to others.
 */
final class GuideFeatures {
    static final String[] NAMES = {"gap", "dy", "margin", "runway", "depth", "width", "opt", "drop", "ceiling"};
    static final int N = NAMES.length;
    /** Longest straight run-up worth measuring: a sprint reaches top speed well within it. */
    static final double RUNWAY_CAP = 6;
    static final double DEPTH_CAP = 3;
    private static final double STEP = 0.25;

    private GuideFeatures() {
    }

    static double[] of(Course c, Course.Surface a, Course.Surface b) {
        double[] f = new double[N];
        double gap = Course.rectGap(a, b);
        double dy = b.top - a.top;
        double r = c.reach.reach(dy);
        double ux = b.cx() - a.cx(), uz = b.cz() - a.cz(), ul = Math.hypot(ux, uz);
        if (ul < 1e-9) {
            ux = 1;
            uz = 0;
        } else {
            ux /= ul;
            uz /= ul;
        }
        f[0] = gap;
        f[1] = dy;
        f[2] = r < 0 ? -3 : clamp(r + Course.REACH_MARGIN - gap, -3, 3);
        f[3] = run(c, a, -ux, -uz, RUNWAY_CAP);
        f[4] = run(c, b, ux, uz, DEPTH_CAP);
        // Room on the landing platform across the move.
        f[5] = Math.min(run(c, b, -uz, ux, DEPTH_CAP) + run(c, b, uz, -ux, DEPTH_CAP), 2 * DEPTH_CAP);
        f[6] = ul / c.reach.maxSpeed();
        f[7] = Math.max(0, -dy);
        f[8] = lowCeiling(c, a) ? 1 : 0;
        return f;
    }

    static final String[] NODE_NAMES = {"dh", "dt", "rate", "speed", "along", "ground", "ahead", "behind", "nextMargin",
        "nextGap", "nextDy", "ceiling", "vy", "sprint", "early"};
    static final int NODE_N = NODE_NAMES.length;

    /**
     * The features of a successor {@code child} of {@code parent} in the A*, for ranking it among its
     * siblings: the progress it makes on the bound, the speed it keeps towards the foothold the bound
     * goes on to, the room it has to run up to that foothold, and how hard the move there is.
     */
    static double[] node(Course c, Run child, Run parent, double hChild, double hParent) {
        double[] f = new double[NODE_N];
        double x = child.s.posX, z = child.s.posZ;
        int dt = Math.max(1, child.tick - parent.tick);
        f[0] = hParent - hChild;
        f[1] = dt;
        f[2] = f[0] / dt;
        f[3] = Math.hypot(child.s.motionX, child.s.motionZ);
        Course.Surface on = child.s.onGround ? c.support(x, child.s.posY, z) : null;
        Course.Surface next = on == null ? null : bestNext(on);
        double ux, uz;
        if (next != null) {
            ux = next.cx() - x;
            uz = next.cz() - z;
        } else {
            ux = (c.problem.goal.minX + c.problem.goal.maxX) / 2 - x;
            uz = (c.problem.goal.minZ + c.problem.goal.maxZ) / 2 - z;
        }
        double ul = Math.hypot(ux, uz);
        if (ul < 1e-9) {
            ux = 1;
            uz = 0;
        } else {
            ux /= ul;
            uz /= ul;
        }
        f[4] = child.s.motionX * ux + child.s.motionZ * uz;
        f[5] = child.s.onGround ? 1 : 0;
        f[6] = on == null ? 0 : runFrom(c, on, x, z, ux, uz, RUNWAY_CAP);
        f[7] = on == null ? 0 : runFrom(c, on, x, z, -ux, -uz, RUNWAY_CAP);
        if (next != null) {
            double[] e = of(c, on, next);
            f[8] = e[2];
            f[9] = e[0];
            f[10] = e[1];
            f[11] = e[8];
        } else {
            f[8] = 3;
        }
        f[12] = child.s.motionY;
        f[13] = child.s.sprinting ? 1 : 0;
        f[14] = child.entry < 0 ? 1 : 0;
        return f;
    }

    /** The foothold after {@code b} on the way to the goal: the lowest bound among its moves off its platform. */
    static Course.Surface bestNext(Course.Surface b) {
        Course.Surface best = null;
        for (Course.Surface n : b.next) {
            if (n.platform == b.platform) continue;
            if (best == null || n.toGo < best.toGo) best = n;
        }
        return best != null && best.toGo < b.toGo ? best : null;
    }

    /** {@link #run} from the point (x, z) on {@code s}'s platform instead of from its centre. */
    static double runFrom(Course c, Course.Surface s, double x0, double z0, double ux, double uz, double cap) {
        double t = 0;
        while (t + STEP <= cap) {
            Course.Surface o = c.support(x0 + ux * (t + STEP), s.top, z0 + uz * (t + STEP));
            if (o == null || o.platform != s.platform) break;
            t += STEP;
        }
        return t;
    }

    /**
     * How far one can go from the centre of {@code s} in direction (ux, uz) and still stand on its
     * platform at its height, up to {@code cap}.
     */
    static double run(Course c, Course.Surface s, double ux, double uz, double cap) {
        double t = 0;
        while (t + STEP <= cap) {
            double x = s.cx() + ux * (t + STEP), z = s.cz() + uz * (t + STEP);
            Course.Surface o = c.support(x, s.top, z);
            if (o == null || o.platform != s.platform) break;
            t += STEP;
        }
        return t;
    }

    /** A ceiling over the middle of {@code s} within a jump's rise above the standing box. */
    static boolean lowCeiling(Course c, Course.Surface s) {
        double px = s.cx(), pz = s.cz();
        io.github.konqasasas.mctas.physics.Aabb box = new io.github.konqasasas.mctas.physics.Aabb(px - 0.299, s.top + 1.8, pz - 0.299, px + 0.299, s.top + 3.05, pz + 0.299);
        java.util.List<io.github.konqasasas.mctas.physics.Aabb> hit = new java.util.ArrayList<io.github.konqasasas.mctas.physics.Aabb>();
        int x = (int) Math.floor(px), z = (int) Math.floor(pz), y = (int) Math.floor(s.top);
        for (int yy = y + 1; yy <= y + 4; yy++) {
            for (int xx = x - 1; xx <= x + 1; xx++) {
                for (int zz = z - 1; zz <= z + 1; zz++) {
                    c.world.getBlock(xx, yy, zz).addCollisionBoxes(c.world, xx, yy, zz, box, hit);
                    if (!hit.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
