package io.github.konqasasas.mctas.search.bound;

import java.util.Arrays;

/**
 * A convex set in the horizontal plane, kept as an outer template polygon: the support value
 * {@code h[i] = max p·d_i} for {@link #N} fixed directions {@code d_i} at angles {@code 2πi/N}
 * (direction 0 is +x, {@code N/4} is +z). The set it stands for is {@code {p : p·d_i <= h[i]}}.
 *
 * <p>Every operation returns a set that contains the exact result, so a chain of them stays an
 * outer approximation. Support values are rounded outward by {@link #EPS} where floating point
 * could round them inward.
 */
public final class Poly {

    public static final int N = 64;
    static final double EPS = 1e-9;
    private static final double[] DX = new double[N];
    private static final double[] DZ = new double[N];

    static {
        for (int i = 0; i < N; i++) {
            double a = 2.0 * Math.PI * i / N;
            DX[i] = Math.cos(a);
            DZ[i] = Math.sin(a);
        }
        // Exact axes, so boxes are represented without rounding.
        for (int q = 0; q < 4; q++) {
            int i = q * N / 4;
            DX[i] = q == 0 ? 1 : q == 2 ? -1 : 0;
            DZ[i] = q == 1 ? 1 : q == 3 ? -1 : 0;
        }
    }

    private static final int PX = 0;
    private static final int PZ = N / 4;
    private static final int NX = N / 2;
    private static final int NZ = 3 * N / 4;

    public static final Poly EMPTY = new Poly(null);

    private final double[] h;

    private Poly(double[] h) {
        this.h = h;
    }

    public static Poly point(double x, double z) {
        double[] h = new double[N];
        for (int i = 0; i < N; i++) h[i] = x * DX[i] + z * DZ[i] + EPS;
        h[PX] = x;
        h[NX] = -x;
        h[PZ] = z;
        h[NZ] = -z;
        return new Poly(h);
    }

    /** The disk of radius {@code r} around the origin. */
    public static Poly disk(double r) {
        double[] h = new double[N];
        Arrays.fill(h, r);
        return new Poly(h);
    }

    public static Poly rect(double x0, double x1, double z0, double z1) {
        if (x0 > x1 || z0 > z1) return EMPTY;
        double[] h = new double[N];
        for (int i = 0; i < N; i++) {
            h[i] = Math.max(x0 * DX[i], x1 * DX[i]) + Math.max(z0 * DZ[i], z1 * DZ[i]) + EPS;
        }
        h[PX] = x1;
        h[NX] = -x0;
        h[PZ] = z1;
        h[NZ] = -z0;
        return new Poly(h);
    }

    public boolean isEmpty() {
        return h == null;
    }

    public double minX() { return -h[NX]; }
    public double maxX() { return h[PX]; }
    public double minZ() { return -h[NZ]; }
    public double maxZ() { return h[PZ]; }

    /** The largest extent of the set along the unit vector at angle {@code 2π i / N}. */
    public double support(int i) {
        return h[i];
    }

    /** Largest distance from the origin, over the polygon's vertices. */
    public double maxNorm() {
        double best = 0;
        for (double[] v : vertices(h)) best = Math.max(best, Math.hypot(v[0], v[1]));
        return best + EPS;
    }

    /** Minkowski sum. */
    public Poly plus(Poly o) {
        if (isEmpty() || o.isEmpty()) return EMPTY;
        double[] r = new double[N];
        for (int i = 0; i < N; i++) r[i] = h[i] + o.h[i] + EPS;
        return new Poly(r);
    }

    /** {@code f * this} for {@code f >= 0}. */
    public Poly scale(double f) {
        if (isEmpty()) return EMPTY;
        double[] r = new double[N];
        for (int i = 0; i < N; i++) r[i] = h[i] * f + EPS;
        return new Poly(r);
    }

    /** Convex hull of the union. */
    public Poly hull(Poly o) {
        if (isEmpty()) return o;
        if (o.isEmpty()) return this;
        double[] r = new double[N];
        for (int i = 0; i < N; i++) r[i] = Math.max(h[i], o.h[i]);
        return new Poly(r);
    }

    /** The smallest axis-aligned box around the set. */
    public Poly bbox() {
        if (isEmpty()) return EMPTY;
        return rect(minX(), maxX(), minZ(), maxZ());
    }

    public Poly intersectRect(double x0, double x1, double z0, double z1) {
        if (isEmpty()) return EMPTY;
        if (h[PX] <= x1 && h[NX] <= -x0 && h[PZ] <= z1 && h[NZ] <= -z0) return this;
        double[] r = h.clone();
        r[PX] = Math.min(r[PX], x1);
        r[NX] = Math.min(r[NX], -x0);
        r[PZ] = Math.min(r[PZ], z1);
        r[NZ] = Math.min(r[NZ], -z0);
        return canonical(r);
    }

    /** Keeps the part with {@code x >= v} (axis 0), {@code x <= v} (axis 1), {@code z >= v} (2) or {@code z <= v} (3). */
    public Poly halfPlane(int axis, double v) {
        double inf = Double.POSITIVE_INFINITY;
        switch (axis) {
            case 0: return intersectRect(v, inf, -inf, inf);
            case 1: return intersectRect(-inf, v, -inf, inf);
            case 2: return intersectRect(-inf, inf, v, inf);
            default: return intersectRect(-inf, inf, -inf, v);
        }
    }

    public boolean intersectsRect(double x0, double x1, double z0, double z1) {
        return !intersectRect(x0 - EPS, x1 + EPS, z0 - EPS, z1 + EPS).isEmpty();
    }

    /** True only if every point of the set lies in the closed rectangle (judged on the outer polygon). */
    public boolean insideRect(double x0, double x1, double z0, double z1) {
        return !isEmpty() && minX() >= x0 && maxX() <= x1 && minZ() >= z0 && maxZ() <= z1;
    }

    public boolean xRangeTouches(double lo, double hi) {
        return minX() < hi && maxX() > lo;
    }

    public boolean zRangeTouches(double lo, double hi) {
        return minZ() < hi && maxZ() > lo;
    }

    /** The set with every point's x replaced by 0. */
    public Poly zeroX() {
        if (isEmpty()) return EMPTY;
        return segmentZ(minZ(), maxZ());
    }

    /** The set with every point's z replaced by 0. */
    public Poly zeroZ() {
        if (isEmpty()) return EMPTY;
        return rect(minX(), maxX(), 0, 0);
    }

    private static Poly segmentZ(double z0, double z1) {
        return rect(0, 0, z0, z1);
    }

    /** True if the point lies in the set, allowing {@code tol} of slack. */
    public boolean contains(double x, double z, double tol) {
        if (isEmpty()) return false;
        for (int i = 0; i < N; i++) if (x * DX[i] + z * DZ[i] > h[i] + tol) return false;
        return true;
    }

    /** True if this set contains {@code o} (judged on support values). */
    public boolean covers(Poly o) {
        if (o.isEmpty()) return true;
        if (isEmpty()) return false;
        for (int i = 0; i < N; i++) if (h[i] < o.h[i]) return false;
        return true;
    }

    boolean sameAs(Poly o) {
        return Arrays.equals(h, o.h);
    }

    /** Rounds every support value up to a multiple of {@code q}, which makes repeated joins terminate. */
    public Poly roundUp(double q) {
        if (isEmpty()) return EMPTY;
        double[] r = new double[N];
        for (int i = 0; i < N; i++) r[i] = Math.ceil(h[i] / q) * q;
        return new Poly(r);
    }

    /** Tightens the support values to the polygon they define, or EMPTY if it has no points. */
    private static Poly canonical(double[] h) {
        double[][] vs = vertices(h);
        if (vs.length == 0) return EMPTY;
        double[] r = new double[N];
        for (int i = 0; i < N; i++) {
            double best = Double.NEGATIVE_INFINITY;
            for (double[] v : vs) best = Math.max(best, v[0] * DX[i] + v[1] * DZ[i]);
            r[i] = Math.min(h[i], best + EPS);
        }
        return new Poly(r);
    }

    /** Vertices of {@code {p : p·d_i <= h[i]}} by clipping a large square with each half-plane. */
    private static double[][] vertices(double[] h) {
        double big = 1e7;
        double[][] poly = {{-big, -big}, {big, -big}, {big, big}, {-big, big}};
        int n = 4;
        for (int i = 0; i < N && n > 0; i++) {
            if (h[i] == Double.POSITIVE_INFINITY) continue;
            // Clip with a slightly larger polygon so rounding never loses a point or a degenerate set.
            double c = h[i] + 1e-10 * (1 + Math.abs(h[i]));
            double[][] out = new double[n + 1][];
            int m = 0;
            for (int k = 0; k < n; k++) {
                double[] a = poly[k];
                double[] b = poly[(k + 1) % n];
                double fa = a[0] * DX[i] + a[1] * DZ[i] - c;
                double fb = b[0] * DX[i] + b[1] * DZ[i] - c;
                boolean ina = fa <= 0;
                boolean inb = fb <= 0;
                if (ina) out[m++] = a;
                if (ina != inb) {
                    double t = fa / (fa - fb);
                    out[m++] = new double[] {a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])};
                }
                if (m == out.length) out = Arrays.copyOf(out, m * 2);
            }
            poly = out;
            n = m;
        }
        return Arrays.copyOf(poly, n);
    }

    @Override
    public String toString() {
        if (isEmpty()) return "{}";
        return String.format("x[%.4f, %.4f] z[%.4f, %.4f]", minX(), maxX(), minZ(), maxZ());
    }
}
