package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The learned guide (WS13): an estimate of the ticks to the goal like {@link Course#toGo}, from the
 * same backward Dijkstra over the optimistic moves, but each step move between platforms costs
 * {@code penalty} ticks more per unit of minus the log of the probability ({@link GuideModel}) that
 * the search can make it. Not a lower bound: the A* orders by it and still prunes by the bound.
 */
final class GuideField {
    /** The least probability a move is given: below it, a move costs the same. */
    static final double MIN_P = 0.02;

    private final Course c;
    private final double penalty;
    /** The extra cost of each step move between platforms, by the ids of its two surfaces. */
    private final Map<Long, Double> extra = new HashMap<Long, Double>();
    private double[] value;

    GuideField(Course c, GuideModel model, double penalty) {
        this.c = c;
        this.penalty = penalty;
        for (Course.Surface a : c.surfaces) {
            for (Course.Surface b : a.next) {
                if (b.platform == a.platform || a.notByStep.contains(b) || a.bounceVia.containsKey(b)) continue;
                double p = Math.max(MIN_P, model.made(GuideFeatures.of(c, a, b)));
                extra.put(key(a, b), -Math.log(p));
            }
        }
        solve();
    }

    private static long key(Course.Surface a, Course.Surface b) {
        return ((long) a.id << 32) | (b.id & 0xFFFFFFFFL);
    }

    /** Works the estimate out again over the moves the course has now (after {@link Course#dropUnmade}). */
    void solve() {
        List<Course.Surface> surfaces = c.surfaces;
        double v = c.reach.maxSpeed();
        List<List<Course.Surface>> into = new ArrayList<List<Course.Surface>>();
        for (int i = 0; i < surfaces.size(); i++) into.add(new ArrayList<Course.Surface>());
        for (Course.Surface a : surfaces) for (Course.Surface b : a.next) into.get(b.id).add(a);
        double[] dist = new double[surfaces.size()];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> pq = new PriorityQueue<double[]>(16, (p, q) -> Double.compare(p[0], q[0]));
        for (Course.Surface a : surfaces) {
            if (a.nearGoal) {
                dist[a.id] = c.goalDist(a.cx(), a.cz()) / v;
                pq.add(new double[] {dist[a.id], a.id});
            }
        }
        while (!pq.isEmpty()) {
            double[] e = pq.poll();
            int u = (int) e[1];
            if (e[0] > dist[u]) continue;
            Course.Surface b = surfaces.get(u);
            for (Course.Surface a : into.get(u)) {
                Double x = extra.get(key(a, b));
                double cost = dist[u] + edgeCost(a, b, v) + (x == null ? 0 : penalty * x);
                if (cost < dist[a.id]) {
                    dist[a.id] = cost;
                    pq.add(new double[] {cost, a.id});
                }
            }
        }
        value = dist;
    }

    /** Same as the course's: centre to centre at the best speed, or the climb when slower. */
    private double edgeCost(Course.Surface a, Course.Surface b, double v) {
        double flat = Math.hypot(a.cx() - b.cx(), a.cz() - b.cz()) / v;
        double rise = b.top - a.top - c.reach.maxRise();
        return rise > 0 ? Math.max(flat, rise / Course.CLIMB_SPEED) : flat;
    }

    double at(Course.Surface s) {
        return value[s.id];
    }

    /** The estimate from a player at (x, y, z), the way {@link Course#toGo} works it out from the surfaces. */
    double toGo(double x, double y, double z) {
        double v = c.reach.maxSpeed();
        Region g = c.problem.goal;
        double dx = Math.max(0, Math.max(g.minX - x, x - g.maxX));
        double dz = Math.max(0, Math.max(g.minZ - z, z - g.maxZ));
        double direct = Math.sqrt(dx * dx + dz * dz) / v;
        double best = Double.POSITIVE_INFINITY;
        if (!c.flatGoal() && y >= g.minY - c.reach.maxRise() && Math.sqrt(dx * dx + dz * dz) <= c.reach.reach(g.minY - y) + Course.REACH_MARGIN) best = direct;
        int r = (int) Math.ceil(c.reach.reach(-8) + Course.REACH_MARGIN) + 1;
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        for (int ix = -r; ix <= r; ix++) {
            for (int iz = -r; iz <= r; iz++) {
                List<Course.Surface> list = c.column(cx + ix, cz + iz);
                if (list == null) continue;
                for (Course.Surface b : list) {
                    double vb = value[b.id];
                    if (vb == Double.POSITIVE_INFINITY) continue;
                    double dy = b.top - y;
                    if (dy > c.reach.maxRise() + Course.EPS) continue;
                    double d = b.dist(x, z);
                    if (d > c.reach.reach(dy) + Course.REACH_MARGIN) continue;
                    best = Math.min(best, Math.max(d, Math.hypot(b.cx() - x, b.cz() - z) - Course.CENTRE_SLACK) / v + vb);
                }
            }
        }
        return Math.max(best, direct);
    }
}
