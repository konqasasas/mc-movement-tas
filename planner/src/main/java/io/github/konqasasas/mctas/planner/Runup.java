package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * The free run-up (D-003): ways to cross into the start box with speed. For a few headings (towards
 * the best first footholds and the goal), walk to a point some distance behind the start, stop, then
 * sprint (straight, strafing, or hopping) into the start. Each first tick inside the start is a root
 * of the search, with the clock at zero.
 */
final class Runup {
    static final class Root {
        final Run run;
        final List<PlayerInput> inputs;
        final String label;

        Root(Run run, List<PlayerInput> inputs, String label) {
            this.run = run;
            this.inputs = inputs;
            this.label = label;
        }
    }

    private static final int WALK_LIMIT = 300;

    /** Run straight, run strafing, hop strafing, run strafing and jump on the tick that enters the start. */
    static final int STYLES = 4;

    static List<Root> roots(Course c) {
        List<Root> out = new ArrayList<Root>();
        Run spawn = Run.spawn(c);
        if (spawn.entry == 0) out.add(new Root(spawn, new ArrayList<PlayerInput>(), "spawn"));
        Region s = c.problem.start;
        for (final double[] dir : headings(c)) {
            // Where to cross: the point of the start box over solid ground nearest to the first foothold
            // along this heading.
            double ex = clamp(dir[2], s.minX, s.maxX), ez = clamp(dir[3], s.minZ, s.maxZ), bestD = Double.POSITIVE_INFINITY;
            for (Course.Surface a : c.surfaces) {
                if (a.top < s.minY - 1e-6 || a.top > s.maxY + 1e-6) continue;
                double x0 = Math.max(a.minX + 0.3, s.minX), x1 = Math.min(a.maxX - 0.3, s.maxX);
                double z0 = Math.max(a.minZ + 0.3, s.minZ), z1 = Math.min(a.maxZ - 0.3, s.maxZ);
                if (x0 > x1 || z0 > z1) continue;
                double qx = clamp(dir[2], x0, x1), qz = clamp(dir[3], z0, z1), d = Math.hypot(qx - dir[2], qz - dir[3]);
                if (d < bestD) {
                    bestD = d;
                    ex = qx;
                    ez = qz;
                }
            }
            List<List<Root>> mine = new ArrayList<List<Root>>();
            for (int style = 0; style < STYLES; style++) {
                mine.add(new ArrayList<Root>());
                Root r = attempt(c, spawn, Double.NaN, Double.NaN, ex, ez, dir, style);
                if (r != null) mine.get(style).add(r);
            }
            // Fine steps: the tick on which the run crosses into the start shifts with the distance.
            List<Double> ds = new ArrayList<Double>();
            for (double d = 0.15; d <= 3.0; d += 0.05) ds.add(d);
            for (double d = 3.07; d <= 20; d += 0.07) ds.add(d);
            for (double d : ds) {
                double px = ex - dir[0] * d, pz = ez - dir[1] * d;
                if (c.support(px, spawn.s.posY, pz) == null) continue;
                // Long run-ups only hop, or run and jump into the start: they are there for the speed.
                for (int style = d > 3 ? 2 : 0; style < STYLES; style++) {
                    Root r = attempt(c, spawn, px, pz, ex, ez, dir, style);
                    if (r != null) mine.get(style).add(r);
                }
            }
            for (List<Root> list : mine) out.addAll(list);
        }
        return out;
    }

    /** Horizontal speed along the heading at the entry. */
    private static double along(Root r, double[] dir) {
        return r.run.s.motionX * dir[0] + r.run.s.motionZ * dir[1];
    }

    /** Unit headings (dx, dz) with a target point (x, z): towards the best footholds next to the start, and the goal. */
    private static List<double[]> headings(Course c) {
        Region s = c.problem.start;
        double sx = (s.minX + s.maxX) / 2, sz = (s.minZ + s.maxZ) / 2;
        List<double[]> pts = new ArrayList<double[]>();
        List<Course.Surface> firsts = new ArrayList<Course.Surface>();
        for (Course.Surface a : c.surfaces) {
            if (a.top < s.minY - 1e-6 || a.top > s.maxY + 1e-6 || a.maxX < s.minX || a.minX > s.maxX || a.maxZ < s.minZ || a.minZ > s.maxZ) continue;
            for (Course.Surface b : a.next) {
                if (b.toGo < a.toGo && !firsts.contains(b)) firsts.add(b);
            }
        }
        firsts.sort((p, q) -> Double.compare(p.toGo, q.toGo));
        for (int i = 0; i < Math.min(3, firsts.size()); i++) {
            Course.Surface b = firsts.get(i);
            pts.add(new double[] {clamp(sx, b.minX + 0.3, b.maxX - 0.3), clamp(sz, b.minZ + 0.3, b.maxZ - 0.3)});
        }
        Region g = c.problem.goal;
        pts.add(new double[] {(g.minX + g.maxX) / 2, (g.minZ + g.maxZ) / 2});
        List<double[]> out = new ArrayList<double[]>();
        for (double[] p : pts) {
            double dx = p[0] - sx, dz = p[1] - sz, len = Math.hypot(dx, dz);
            if (len < 1e-6) continue;
            double[] h = {dx / len, dz / len, p[0], p[1]};
            boolean dup = false;
            for (double[] o : out) dup |= o[0] * h[0] + o[1] * h[1] > Math.cos(Math.toRadians(5));
            if (!dup) out.add(h);
        }
        return out;
    }

    private static Root attempt(Course c, Run spawn, double px, double pz, double ex, double ez, double[] dir, int style) {
        Region s = c.problem.start;
        Run r = spawn.copy();
        List<PlayerInput> in = new ArrayList<PlayerInput>();
        if (Double.isNaN(px)) {
            px = r.s.posX;
            pz = r.s.posZ;
        }
        if (s.contains(px, r.s.posY, pz)) return null;
        // Walk to the back point and stand still.
        for (int i = 0; i < WALK_LIMIT; i++) {
            double dx = px - r.s.posX, dz = pz - r.s.posZ;
            if (dx * dx + dz * dz < 0.0625) break;
            float h = Step.heading(r.s.posX, r.s.posZ, px, pz);
            Step.tick(r, r.legal(new PlayerInput(PlayerInput.W, h, r.s.rotationPitch)), in);
            if (r.over()) return null;
        }
        for (int i = 0; i < 40; i++) {
            if (r.s.onGround && Math.abs(r.s.motionX) < 1e-3 && Math.abs(r.s.motionZ) < 1e-3) break;
            Step.tick(r, r.legal(new PlayerInput(0, r.s.rotationYaw, r.s.rotationPitch)), in);
            if (r.over()) return null;
        }
        if (Math.hypot(px - r.s.posX, pz - r.s.posZ) > 0.4 || !r.s.onGround) return null;
        int entry = r.entry;
        boolean wasIn = r.inStart;
        double tx = ex + dir[0] * 20, tz = ez + dir[1] * 20;
        Step run = new Step(tx, -1e9, tz, null, style == 0 ? 0 : 1, Step.NO_JUMP);
        for (int i = 0; i < 200; i++) {
            PlayerInput a = style == 2 && r.s.onGround && i > 0 ? run.jump(r) : run.move(r);
            if (style == 3 && r.s.onGround) {
                // Jump on the tick that would cross into the start anyway, if the jump crosses too: the
                // latest takeoff that still starts the clock in the air.
                Run plain = r.copy();
                plain.step(a);
                Run j = r.copy();
                PlayerInput ja = run.jump(j);
                j.step(ja);
                if (!plain.over() && plain.entry == plain.tick && !j.over() && j.entry == j.tick) a = ja;
            }
            Step.tick(r, a, in);
            if (r.over()) return null;
            if (r.entry != entry || (r.inStart && !wasIn)) {
                return new Root(r, in, String.format("d %.1f style %d", Math.hypot(ex - px, ez - pz), style));
            }
            wasIn = r.inStart;
        }
        return null;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
