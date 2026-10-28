package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.world.Blocks;

import java.util.List;

/**
 * Crossing an open iron trapdoor standing across the path: a thin wall (3/16 thick, 1 high) at one
 * edge of its block.
 *
 * <p>The floor's top is at {@link StepWindow#FLOOR}. A row of open bottom trapdoors facing
 * {@code facing} stands on the floor at z = {@link #WALL} (x -3..3). The player starts at steady sprint
 * at z 5 and the exit is passing z {@link #WALL} + 6 with the feet at floor height or above. The
 * closed trapdoors are covered by {@link CeilingBench} (top half as a 1.8125 or 2.8125 ceiling) and
 * {@link StepWindow} (bottom half on a block as a 1.1875 step).
 */
public final class TrapdoorBench {
    static final int WALL = 12;

    private TrapdoorBench() {
    }

    public static SparseBlockWorld world(String facing) {
        SparseBlockWorld w = CeilingBench.world(0);
        if (facing != null) {
            BlockType t = Blocks.byId("minecraft:iron_trapdoor[facing=" + facing + ",half=bottom,open=true]");
            w.fill(-3, StepWindow.FLOOR, WALL, 3, StepWindow.FLOOR, WALL, t);
        }
        return w;
    }

    /** Ticks to the exit with the trapdoors facing {@code facing} (null for no trapdoor), or -1. */
    public static int search(String facing, SegmentSearch.Options o) {
        List<SegmentSearch.Result> rs = new SegmentSearch().search(world(facing), CeilingBench.entry(), -100,
                Exit.plane(0.5, 5, 0, WALL + 6 - 5, StepWindow.FLOOR, false), 0, o);
        return rs.isEmpty() ? -1 : rs.get(0).ticks();
    }
}
