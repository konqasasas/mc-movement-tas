package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

import java.util.List;

/**
 * Turning a 90-degree corner on a path {@code width} blocks wide with a drop on both sides. The path
 * runs along +z (x -1..width-2) from z 0 to z 30, then turns to +x along z 30-width+1..30. The player
 * starts at steady sprint in the middle of the path at z 20; the exit is passing x 15 on the path.
 * The beam ranks states by distance along the path's centre line (z up to the turn, then x).
 */
public final class CornerBench {
    private CornerBench() {
    }

    public static SparseBlockWorld world(int width) {
        SparseBlockWorld w = new SparseBlockWorld();
        int f = StepWindow.FLOOR;
        w.fill(-1, f - 1, 0, width - 2, f - 1, 30, StepWindow.STONE);
        w.fill(-1, f - 1, 31 - width, 40, f - 1, 30, StepWindow.STONE);
        return w;
    }

    public static PlayerState entry(int width) {
        PlayerState s = StepWindow.runUp(40);
        double x = -1 + width / 2.0;
        double shift = 20 - s.posZ;
        s.setPosition(x, s.posY, 20);
        s.lastReportedPosX = x;
        s.lastReportedPosZ += shift;
        return s;
    }

    /** The fastest way, or null. Searched around the diagonal heading with the default offsets. */
    public static SegmentSearch.Result run(int width, SegmentSearch.Options o) {
        o.voidY = StepWindow.FLOOR - 1.5;
        double turnZ = 30.5 - width / 2.0;
        Exit exit = new Exit() {
            @Override
            public boolean reached(PlayerState before, PlayerState after) {
                return after.posX >= 15 && after.posY >= StepWindow.FLOOR;
            }

            @Override
            public double progress(PlayerState s, double along) {
                // Distance along the path's centre line: up to the turn on z, then on x.
                return Math.min(s.posZ, turnZ) + Math.max(s.posX, -1 + width / 2.0);
            }
        };
        List<SegmentSearch.Result> rs = new SegmentSearch().search(world(width), entry(width), -100, exit, -45, o);
        return rs.isEmpty() ? null : rs.get(0);
    }
}
