package io.github.konqasasas.mctas.search.exact;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A coarse route: the platforms to land on between the start and the goal. Platforms are the
 * connected groups of standable block tops; two platforms are linked when the gap between them is
 * at most {@link #MAX_GAP} blocks and the far one is at most one block higher. The route is the
 * shortest chain of links by distance between platform centres.
 */
public final class Route {

    static final double MAX_GAP = 4.5;

    private static final class Platform {
        final int top;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;

        Platform(int top) {
            this.top = top;
        }

        double cx() { return (x0 + x1 + 1) / 2.0; }
        double cz() { return (z0 + z1 + 1) / 2.0; }

        double gap(Platform o) {
            double gx = Math.max(0, Math.max(o.x0 - (x1 + 1), x0 - (o.x1 + 1)));
            double gz = Math.max(0, Math.max(o.z0 - (z1 + 1), z0 - (o.z1 + 1)));
            return Math.hypot(gx, gz);
        }

        boolean under(double x, double z) {
            return x >= x0 - 0.3 && x <= x1 + 1.3 && z >= z0 - 0.3 && z <= z1 + 1.3;
        }

        Region landing() {
            return new Region(x0 - 0.3, top, z0 - 0.3, x1 + 1.3, top, z1 + 1.3);
        }
    }

    private Route() {
    }

    /** The landing regions strictly between the start platform and the goal platform, in order. */
    public static List<Region> landings(Problem problem) {
        List<Platform> ps = platforms(problem.world);
        Region s = problem.start, g = problem.goal;
        double sx = (s.minX + s.maxX) / 2, sz = (s.minZ + s.maxZ) / 2;
        double gx = (g.minX + g.maxX) / 2, gz = (g.minZ + g.maxZ) / 2;
        int from = -1, to = -1;
        for (int i = 0; i < ps.size(); i++) {
            Platform p = ps.get(i);
            if (from < 0 && p.under(sx, sz) && p.top >= s.minY && p.top <= s.maxY) from = i;
            if (to < 0 && p.under(gx, gz) && p.top >= g.minY && p.top <= g.maxY) to = i;
        }
        List<Region> out = new ArrayList<Region>();
        if (from < 0 || to < 0 || from == to) return out;
        int n = ps.size();
        double[] dist = new double[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        boolean[] done = new boolean[n];
        dist[from] = 0;
        for (int it = 0; it < n; it++) {
            int u = -1;
            for (int i = 0; i < n; i++) if (!done[i] && (u < 0 || dist[i] < dist[u])) u = i;
            if (u < 0 || dist[u] == Double.POSITIVE_INFINITY) break;
            done[u] = true;
            for (int v = 0; v < n; v++) {
                Platform a = ps.get(u), b = ps.get(v);
                if (v == u || a.gap(b) > MAX_GAP || b.top > a.top + 1) continue;
                double d = dist[u] + Math.hypot(a.cx() - b.cx(), a.cz() - b.cz());
                if (d < dist[v]) {
                    dist[v] = d;
                    prev[v] = u;
                }
            }
        }
        if (prev[to] < 0) return out;
        for (int v = prev[to]; v != from; v = prev[v]) out.add(0, ps.get(v).landing());
        return out;
    }

    private static List<Platform> platforms(SparseBlockWorld world) {
        final Map<Long, Integer> tops = new HashMap<Long, Integer>();
        final Set<Long> solid = new HashSet<Long>();
        world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                solid.add(key(x, y, z));
            }
        });
        world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                if (!solid.contains(key(x, y + 1, z)) && !solid.contains(key(x, y + 2, z))) tops.put(key(x, y, z), y + 1);
            }
        });
        List<Platform> out = new ArrayList<Platform>();
        Set<Long> seen = new HashSet<Long>();
        for (Long k : tops.keySet()) {
            if (seen.contains(k)) continue;
            Platform p = new Platform(tops.get(k));
            List<Long> stack = new ArrayList<Long>();
            stack.add(k);
            seen.add(k);
            while (!stack.isEmpty()) {
                long c = stack.remove(stack.size() - 1);
                int x = (int) (c >> 40), y = (int) ((c >> 20) & 0xFFFFF) - 0x80000, z = (int) (c & 0xFFFFF) - 0x80000;
                p.x0 = Math.min(p.x0, x);
                p.x1 = Math.max(p.x1, x);
                p.z0 = Math.min(p.z0, z);
                p.z1 = Math.max(p.z1, z);
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long nk = key(x + d[0], y, z + d[1]);
                    if (tops.containsKey(nk) && !seen.contains(nk)) {
                        seen.add(nk);
                        stack.add(nk);
                    }
                }
            }
            out.add(p);
        }
        return out;
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) | ((long) (y + 0x80000) << 20) | (long) (z + 0x80000);
    }
}
