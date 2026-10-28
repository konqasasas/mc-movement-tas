package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.List;

/** Drops through the holes in the player's platform ({@link Course#holes}): the best few, by the bound past them. */
final class HoleManeuver implements Maneuver {
    static final int HOLES = 2;

    @Override
    public void expand(Run from, List<double[]> aims, List<Successor> out) {
        if (!from.s.onGround) return;
        Course c = from.course;
        Course.Surface a = c.support(from.s.posX, from.s.posY, from.s.posZ);
        if (a == null) return;
        List<Course.Hole> list = c.holes.get(a.platform);
        if (list == null) return;
        final double v = c.reach.maxSpeed(), x = from.s.posX, z = from.s.posZ;
        List<Course.Hole> cand = new ArrayList<Course.Hole>();
        for (Course.Hole h : list) if (h.below.toGo < a.toGo + 8) cand.add(h);
        cand.sort((p, q) -> Double.compare(Math.hypot(p.x - x, p.z - z) / v + p.below.toGo, Math.hypot(q.x - x, q.z - z) / v + q.below.toGo));
        for (int i = 0; i < Math.min(HOLES, cand.size()); i++) {
            HoleStep st = new HoleStep(cand.get(i));
            Run r = from.copy();
            List<PlayerInput> in = new ArrayList<PlayerInput>();
            st.play(r, in);
            if (!r.dead) out.add(new Successor(st, r, in));
        }
    }
}
