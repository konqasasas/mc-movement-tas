package io.github.konqasasas.mctas.search.bound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A convex polygon given by its vertices, used for one axis of the motion: position {@code u}
 * and velocity {@code w} along x, or along z. Keeping the two together remembers, for example, that
 * a player far out to the side got there with a sideways velocity.
 *
 * <p>Like {@link Poly}, every operation returns a superset of the exact result. Coordinates are
 * pushed outward by a small margin where rounding could pull them in.
 */
final class CPoly {

    private static final double EPS = 1e-9;
    /** Above this many vertices, the polygon is replaced by a slightly larger one with fewer. */
    private static final int MAX_VERTICES = 48;

    static final CPoly EMPTY = new CPoly(new double[0][]);

    /** Counter-clockwise vertices (u, w). */
    private final double[][] v;

    private CPoly(double[][] v) {
        this.v = v;
    }

    static CPoly rect(double u0, double u1, double w0, double w1) {
        if (u0 > u1 || w0 > w1) return EMPTY;
        return new CPoly(new double[][] {{u0, w0}, {u1, w0}, {u1, w1}, {u0, w1}});
    }

    static CPoly point(double u, double w) {
        return new CPoly(new double[][] {{u, w}});
    }

    boolean isEmpty() {
        return v.length == 0;
    }

    double minU() { double m = Double.POSITIVE_INFINITY; for (double[] p : v) m = Math.min(m, p[0]); return m; }
    double maxU() { double m = Double.NEGATIVE_INFINITY; for (double[] p : v) m = Math.max(m, p[0]); return m; }
    double minW() { double m = Double.POSITIVE_INFINITY; for (double[] p : v) m = Math.min(m, p[1]); return m; }
    double maxW() { double m = Double.NEGATIVE_INFINITY; for (double[] p : v) m = Math.max(m, p[1]); return m; }

    /** The tick update along one axis: {@code (u, w) -> (u + w + a, f (w + a))} for {@code a} in {@code [-r, r]}. */
    CPoly move(double f, double r) {
        if (isEmpty()) return EMPTY;
        if (v.length < 3) {
            List<double[]> pts = new ArrayList<double[]>(v.length * 2);
            for (double[] p : v) {
                for (int s = -1; s <= 1; s += 2) {
                    double a = s * r;
                    pts.add(new double[] {p[0] + p[1] + a, f * (p[1] + a)});
                }
            }
            return hullOf(pts).grow();
        }
        // The map (u, w) -> (u + w, f w) keeps orientation (determinant f > 0), so the image of a
        // counter-clockwise polygon is one too; then add the segment {t (1, f) : |t| <= r}.
        double[][] img = new double[v.length][];
        for (int i = 0; i < v.length; i++) img[i] = new double[] {v[i][0] + v[i][1], f * v[i][1]};
        CPoly c = new CPoly(img).plusSegment(1, f, r);
        // Rounding: add a tiny square.
        c = c.plusSegment(1, 0, EPS).plusSegment(0, 1, EPS);
        return c.v.length > MAX_VERTICES ? c.reduce() : c;
    }

    /** Minkowski sum with the segment {t (eu, ew) : |t| <= a}, for a convex counter-clockwise polygon. */
    private CPoly plusSegment(double eu, double ew, double a) {
        int n = v.length;
        double du = eu * a, dw = ew * a;
        List<double[]> out = new ArrayList<double[]>(n + 2);
        // Side of edge i: sign of its outward normal along e. Outward normal of (p -> q) in a CCW
        // polygon is (dq_w, -dq_u).
        int[] side = new int[n];
        for (int i = 0; i < n; i++) {
            double[] p = v[i], q = v[(i + 1) % n];
            double nu = q[1] - p[1], nw = -(q[0] - p[0]);
            side[i] = nu * eu + nw * ew >= 0 ? 1 : -1;
        }
        for (int i = 0; i < n; i++) {
            int prev = side[(i - 1 + n) % n], cur = side[i];
            double[] p = v[i];
            out.add(new double[] {p[0] + prev * du, p[1] + prev * dw});
            if (prev != cur) out.add(new double[] {p[0] + cur * du, p[1] + cur * dw});
        }
        return new CPoly(dedupe(out));
    }

    private static double[][] dedupe(List<double[]> pts) {
        List<double[]> out = new ArrayList<double[]>(pts.size());
        for (double[] p : pts) {
            if (!out.isEmpty()) {
                double[] q = out.get(out.size() - 1);
                if (q[0] == p[0] && q[1] == p[1]) continue;
            }
            out.add(p);
        }
        while (out.size() > 1) {
            double[] a = out.get(0), b = out.get(out.size() - 1);
            if (a[0] == b[0] && a[1] == b[1]) out.remove(out.size() - 1);
            else break;
        }
        return out.toArray(new double[0][]);
    }

    CPoly hull(CPoly o) {
        if (isEmpty()) return o;
        if (o.isEmpty()) return this;
        List<double[]> pts = new ArrayList<double[]>(Arrays.asList(v));
        pts.addAll(Arrays.asList(o.v));
        return hullOf(pts);
    }

    /** Keeps the part with {@code a u + b w <= c}. */
    CPoly clip(double a, double b, double c) {
        if (isEmpty()) return EMPTY;
        if (c == Double.POSITIVE_INFINITY) return this;
        double cc = c + EPS * (1 + Math.abs(c));
        List<double[]> out = new ArrayList<double[]>();
        int n = v.length;
        if (n == 1) {
            return a * v[0][0] + b * v[0][1] <= cc ? this : EMPTY;
        }
        for (int k = 0; k < n; k++) {
            double[] p = v[k];
            double[] q = v[(k + 1) % n];
            double fp = a * p[0] + b * p[1] - cc;
            double fq = a * q[0] + b * q[1] - cc;
            if (fp <= 0) out.add(p);
            if ((fp <= 0) != (fq <= 0)) {
                double t = fp / (fp - fq);
                out.add(new double[] {p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])});
            }
        }
        if (out.isEmpty()) return EMPTY;
        double[][] r = dedupe(out);
        return r.length < 3 ? hullOf(java.util.Arrays.asList(r)) : new CPoly(r);
    }

    CPoly clipU(double lo, double hi) {
        if (isEmpty() || (minU() >= lo && maxU() <= hi)) return this;
        return clip(1, 0, hi).clip(-1, 0, -lo);
    }

    CPoly clipW(double lo, double hi) {
        if (isEmpty() || (minW() >= lo && maxW() <= hi)) return this;
        return clip(0, 1, hi).clip(0, -1, -lo);
    }

    /** The set with every point's velocity replaced by 0, joined with the set itself. */
    CPoly withStopped() {
        if (isEmpty()) return EMPTY;
        return hull(rect(minU(), maxU(), 0, 0));
    }

    /** The bounding rectangle. */
    CPoly box() {
        if (isEmpty()) return EMPTY;
        return rect(minU(), maxU(), minW(), maxW());
    }

    /** True if the point lies in the polygon, allowing {@code tol} of slack. */
    boolean contains(double u, double w, double tol) {
        if (isEmpty()) return false;
        int n = v.length;
        if (n < 3) {
            return u >= minU() - tol && u <= maxU() + tol && w >= minW() - tol && w <= maxW() + tol;
        }
        for (int k = 0; k < n; k++) {
            double[] a = v[k];
            double[] b = v[(k + 1) % n];
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double cross = (b[0] - a[0]) * (w - a[1]) - (b[1] - a[1]) * (u - a[0]);
            if (cross < -tol * len) return false;
        }
        return true;
    }

    boolean covers(CPoly o) {
        if (o.isEmpty()) return true;
        if (isEmpty()) return false;
        int n = v.length;
        if (n < 3) return hull(o).sameAs(this);
        for (double[] p : o.v) {
            for (int k = 0; k < n; k++) {
                double[] a = v[k];
                double[] b = v[(k + 1) % n];
                double cross = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
                if (cross < -1e-12) return false;
            }
        }
        return true;
    }

    private boolean sameAs(CPoly o) {
        return Arrays.deepEquals(v, o.v);
    }

    private static final int WIDEN_DIRS = 32;

    /**
     * Grid offsets when this polygon was made by {@link #widenJoin}, else null. Kept so that the
     * next widening starts from them and not from the clipped vertices, whose rounding slack would
     * otherwise push every widening one grid step further out.
     */
    private double[] grid;

    /**
     * The join of {@code old} and {@code in}, rounded outward onto a coarse grid of directions and
     * offsets, so that a sequence of joins can only grow finitely often. {@code su} and {@code sw}
     * scale the axes to similar sizes.
     */
    static CPoly widenJoin(CPoly old, CPoly in, double su, double sw, double q) {
        if (old.isEmpty() && in.isEmpty()) return EMPTY;
        double[] hs = new double[WIDEN_DIRS];
        double[][] ds = new double[WIDEN_DIRS][];
        for (int i = 0; i < WIDEN_DIRS; i++) {
            double ang = 2 * Math.PI * i / WIDEN_DIRS;
            double a = Math.cos(ang) / su, b = Math.sin(ang) / sw;
            ds[i] = new double[] {a, b};
            double best = Double.NEGATIVE_INFINITY;
            for (CPoly c : new CPoly[] {old, in}) {
                if (c.grid != null) {
                    best = Math.max(best, c.grid[i]);
                } else {
                    for (double[] p : c.v) best = Math.max(best, a * p[0] + b * p[1] + EPS);
                }
            }
            hs[i] = Math.ceil(best / q) * q;
        }
        if (old.grid != null && Arrays.equals(old.grid, hs)) return old;
        double big = 1e7;
        CPoly r = rect(-big, big, -big, big);
        for (int i = 0; i < WIDEN_DIRS; i++) r = r.clip(ds[i][0], ds[i][1], hs[i]);
        CPoly out = new CPoly(r.v);
        out.grid = hs;
        return out;
    }

    /** Pushes every vertex away from the centroid by a tiny margin against rounding. */
    private CPoly grow() {
        if (v.length == 1) return rect(v[0][0] - EPS, v[0][0] + EPS, v[0][1] - EPS, v[0][1] + EPS);
        if (v.length == 2) {
            double[] a = v[0], b = v[1];
            double du = b[0] - a[0], dw = b[1] - a[1];
            double len = Math.hypot(du, dw);
            double nu = -dw / len * EPS, nw = du / len * EPS;
            double tu = du / len * EPS, tw = dw / len * EPS;
            List<double[]> q = new ArrayList<double[]>();
            q.add(new double[] {a[0] - tu + nu, a[1] - tw + nw});
            q.add(new double[] {a[0] - tu - nu, a[1] - tw - nw});
            q.add(new double[] {b[0] + tu + nu, b[1] + tw + nw});
            q.add(new double[] {b[0] + tu - nu, b[1] + tw - nw});
            return hullOf(q);
        }
        double cu = 0, cw = 0;
        for (double[] p : v) {
            cu += p[0];
            cw += p[1];
        }
        cu /= v.length;
        cw /= v.length;
        double[][] r = new double[v.length][];
        for (int i = 0; i < v.length; i++) {
            double du = v[i][0] - cu, dw = v[i][1] - cw;
            r[i] = new double[] {v[i][0] + Math.signum(du) * EPS, v[i][1] + Math.signum(dw) * EPS};
        }
        return hullOf(Arrays.asList(r));
    }

    /** Convex hull (monotone chain), then fewer vertices if needed. */
    private static CPoly hullOf(List<double[]> pts) {
        if (pts.isEmpty()) return EMPTY;
        double[][] p = pts.toArray(new double[0][]);
        Arrays.sort(p, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]);
            }
        });
        int n = p.length;
        double[][] h = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        int m = Math.max(1, k - 1);
        double[][] r = Arrays.copyOf(h, m);
        if (n == 1) r = new double[][] {p[0]};
        CPoly c = new CPoly(r);
        return c.v.length > MAX_VERTICES ? c.reduce() : c;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /**
     * Removes vertices by extending their neighbouring edges until they meet, choosing each time
     * the vertex whose removal adds the least area. The result contains the original.
     */
    private CPoly reduce() {
        List<double[]> pts = new ArrayList<double[]>(Arrays.asList(v));
        while (pts.size() > MAX_VERTICES / 2) {
            int n = pts.size();
            int best = -1;
            double bestArea = Double.POSITIVE_INFINITY;
            double[] bestPoint = null;
            for (int i = 0; i < n; i++) {
                // Replace edge (b, c) by the intersection of lines (a, b) and (c, d).
                double[] a = pts.get((i - 1 + n) % n), b = pts.get(i), c = pts.get((i + 1) % n), d = pts.get((i + 2) % n);
                double[] x = intersect(a, b, c, d);
                if (x == null) continue;
                // The new point must lie beyond b along a->b and beyond c along d->c.
                if (dot(b[0] - a[0], b[1] - a[1], x[0] - b[0], x[1] - b[1]) < 0) continue;
                if (dot(c[0] - d[0], c[1] - d[1], x[0] - c[0], x[1] - c[1]) < 0) continue;
                double area = Math.abs(cross(b, x, c)) / 2;
                if (area < bestArea) {
                    bestArea = area;
                    best = i;
                    bestPoint = x;
                }
            }
            if (best < 0) break;
            int n0 = pts.size();
            pts.set(best, bestPoint);
            pts.remove((best + 1) % n0);
        }
        return new CPoly(pts.toArray(new double[0][])).grow();
    }

    private static double dot(double a, double b, double c, double d) {
        return a * c + b * d;
    }

    private static double[] intersect(double[] a, double[] b, double[] c, double[] d) {
        double r0 = b[0] - a[0], r1 = b[1] - a[1];
        double s0 = d[0] - c[0], s1 = d[1] - c[1];
        double den = r0 * s1 - r1 * s0;
        if (Math.abs(den) < 1e-15) return null;
        double t = ((c[0] - a[0]) * s1 - (c[1] - a[1]) * s0) / den;
        return new double[] {a[0] + t * r0, a[1] + t * r1};
    }

    @Override
    public String toString() {
        if (isEmpty()) return "{}";
        return String.format("u[%.4f, %.4f] w[%.4f, %.4f] (%d)", minU(), maxU(), minW(), maxW(), v.length);
    }
}
