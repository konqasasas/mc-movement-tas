package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

import java.util.List;

/**
 * A neo: getting past a wall by jumping around its end over a drop. The run-up floor (top at
 * {@link StepWindow#FLOOR}) covers x -3..0, z 0..29; a wall 3 high stands at z 30, x -3..0 (so its end
 * face is at x 1); the landing floor covers x -3..0 from z {@code 30 + gap}. Nothing is beside the
 * wall's end, so the player has to go around it in the air. The player starts running at x 0.5 with
 * its front 2 blocks before the wall; the exit is standing on the landing floor.
 */
public final class NeoBench {
    static final int WALL = 30;

    private NeoBench() {
    }

    public static SparseBlockWorld world(int gap) {
        SparseBlockWorld w = new SparseBlockWorld();
        int f = StepWindow.FLOOR;
        w.fill(-3, f - 1, 0, 0, f - 1, WALL - 1, StepWindow.STONE);
        w.fill(-3, f, WALL, 0, f + 2, WALL, StepWindow.STONE);
        w.fill(-3, f - 1, WALL + gap, 0, f - 1, WALL + gap + 4, StepWindow.STONE);
        return w;
    }

    public static Target landing(int gap) {
        return new Target(-3, 1, WALL + gap, WALL + gap + 5, StepWindow.FLOOR);
    }

    public static PlayerState entry() {
        PlayerState s = StepWindow.runUp(40);
        double z = WALL - 0.3 - 2;
        double shift = z - s.posZ;
        s.setPosition(0.5, s.posY, z);
        s.lastReportedPosX = 0.5;
        s.lastReportedPosZ += shift;
        return s;
    }

    /** The search's fastest result, or null. */
    public static SegmentSearch.Result run(int gap, SegmentSearch.Options o) {
        o.voidY = StepWindow.FLOOR - 1.5;
        List<SegmentSearch.Result> rs = new SegmentSearch().search(world(gap), entry(), -100,
                Exit.landing(landing(gap)), 0, o);
        return rs.isEmpty() ? null : rs.get(0);
    }
}
