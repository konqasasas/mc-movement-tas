package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * What one sprint jump can do, measured once with the real physics: sprint-jump along a long floor
 * until the momentum settles, then fly one more jump through empty space. Gives the optimistic graph
 * its reach for each height difference and the heuristic its top speed.
 */
final class Reach {
    private static Reach instance;

    /** Height relative to the takeoff and horizontal distance from it, after each tick of the jump. */
    private final double[] rise;
    private final double[] dist;
    private final double maxSpeed;

    private Reach(double[] rise, double[] dist, double maxSpeed) {
        this.rise = rise;
        this.dist = dist;
        this.maxSpeed = maxSpeed;
    }

    static synchronized Reach get() {
        if (instance == null) instance = measure();
        return instance;
    }

    private static Reach measure() {
        SparseBlockWorld floor = new SparseBlockWorld();
        floor.fill(-1, 63, -5, 1, 63, 400, BlockType.STONE);
        PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0);
        PlayerInput run = PlayerInput.of(PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT, 45F);
        PlayerInput jump = PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 0F);
        double top = 0;
        double lastZ = s.posZ;
        for (int t = 0; t < 300; t++) {
            s = Physics.tick(floor, s, s.onGround && t > 2 ? jump : run);
            if (t > 100) top = Math.max(top, s.posZ - lastZ);
            lastZ = s.posZ;
        }
        while (!s.onGround) s = Physics.tick(floor, s, run);
        SparseBlockWorld empty = new SparseBlockWorld();
        double y0 = s.posY, z0 = s.posZ;
        List<double[]> pts = new ArrayList<double[]>();
        s = Physics.tick(empty, s, jump);
        pts.add(new double[] {s.posY - y0, s.posZ - z0});
        while (s.posY - y0 > -16) {
            s = Physics.tick(empty, s, run);
            pts.add(new double[] {s.posY - y0, s.posZ - z0});
        }
        double[] rise = new double[pts.size()];
        double[] dist = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            rise[i] = pts.get(i)[0];
            dist[i] = pts.get(i)[1];
        }
        // A little above the settled bunny-hop speed, so that the bound stays a bound.
        return new Reach(rise, dist, top * 1.05);
    }

    double maxRise() {
        double m = 0;
        for (double r : rise) m = Math.max(m, r);
        return m;
    }

    /** The landing tick on a surface {@code dy} above the takeoff: the first falling tick that would go below it. */
    private int landingTick(double dy) {
        for (int i = 1; i < rise.length; i++) {
            if (rise[i] < rise[i - 1] && rise[i] < dy) return i;
        }
        return rise.length - 1;
    }

    /** Horizontal centre distance covered before landing {@code dy} above the takeoff height. */
    double reach(double dy) {
        if (dy > maxRise()) return -1;
        return dist[landingTick(dy)];
    }

    /** Top horizontal speed, blocks per tick. */
    double maxSpeed() {
        return maxSpeed;
    }
}
