package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * How far one sprint jump carries the player's centre, measured once with the real physics: sprint
 * jumps along a long floor until the momentum settles, then the next jump flies through empty space.
 * The route uses it to decide which gaps can be jumped; it is optimistic on purpose (full momentum,
 * 45-degree strafe), so the tick search is the one that decides what is really possible.
 */
final class JumpTable {
    private static JumpTable instance;

    /** Height relative to the takeoff and horizontal distance from it, after each tick of the jump. */
    private final double[] rise;
    private final double[] dist;

    private JumpTable(double[] rise, double[] dist) {
        this.rise = rise;
        this.dist = dist;
    }

    static synchronized JumpTable get() {
        if (instance == null) instance = measure();
        return instance;
    }

    private static JumpTable measure() {
        SparseBlockWorld floor = new SparseBlockWorld();
        floor.fill(-1, 63, -5, 1, 63, 200, BlockType.STONE);
        PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0);
        PlayerInput run = PlayerInput.of(PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT, 45F);
        PlayerInput jump = PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 0F);
        int jumps = 0;
        for (int t = 0; t < 400 && jumps < 6; t++) {
            if (s.onGround && t > 2) {
                jumps++;
                if (jumps == 6) break;
                s = Physics.tick(floor, s, jump);
            } else {
                s = Physics.tick(floor, s, run);
            }
        }
        SparseBlockWorld empty = new SparseBlockWorld();
        double y0 = s.posY, z0 = s.posZ;
        List<double[]> pts = new ArrayList<double[]>();
        s = Physics.tick(empty, s, jump);
        pts.add(new double[] {s.posY - y0, s.posZ - z0});
        while (s.posY - y0 > -12) {
            s = Physics.tick(empty, s, run);
            pts.add(new double[] {s.posY - y0, s.posZ - z0});
        }
        double[] rise = new double[pts.size()];
        double[] dist = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            rise[i] = pts.get(i)[0];
            dist[i] = pts.get(i)[1];
        }
        return new JumpTable(rise, dist);
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

    /** Height relative to the takeoff once the centre has covered {@code d} horizontally. */
    double riseAt(double d) {
        for (int i = 0; i < dist.length; i++) if (dist[i] >= d) return rise[i];
        return rise[rise.length - 1];
    }

    double airTicks(double dy) {
        return landingTick(dy) + 1;
    }

    double maxReach() {
        return reach(-4);
    }
}
