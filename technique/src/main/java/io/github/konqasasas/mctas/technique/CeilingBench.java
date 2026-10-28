package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.world.Blocks;

import java.util.List;

/**
 * Covering a flat stretch under a ceiling: running, chaining sprint jumps, and the search.
 *
 * <p>A flat floor (top at {@link StepWindow#FLOOR}, x -3..3) with a ceiling over z 0..60 whose bottom
 * is {@code gap} blocks above the floor (2, 2.5 with top slabs, 1.8125 and 2.8125 with closed
 * top trapdoors, 3, or none). The player starts at
 * steady sprint at z 5 and the exit is passing z 5 + {@code distance} (feet at floor height or above).
 */
public final class CeilingBench {
    static final BlockType TOP_SLAB = Blocks.byId("minecraft:stone_slab[half=top]");
    static final BlockType TOP_TRAPDOOR = Blocks.byId("minecraft:iron_trapdoor[facing=north,half=top,open=false]");

    private CeilingBench() {
    }

    /** {@code gap} 0 means no ceiling. */
    public static SparseBlockWorld world(double gap) {
        SparseBlockWorld w = new SparseBlockWorld();
        int f = StepWindow.FLOOR;
        w.fill(-3, f - 1, -40, 3, f - 1, 80, StepWindow.STONE);
        if (gap - Math.floor(gap) == 0.8125) {
            w.fill(-3, f + (int) gap, 0, 3, f + (int) gap, 60, TOP_TRAPDOOR);
        } else if (gap == 2.5) {
            w.fill(-3, f + 2, 0, 3, f + 2, 60, TOP_SLAB);
        } else if (gap > 0) {
            w.fill(-3, f + (int) gap, 0, 3, f + (int) gap, 60, StepWindow.STONE);
        }
        return w;
    }

    public static PlayerState entry() {
        PlayerState s = StepWindow.runUp(40);
        double shift = 5 - s.posZ;
        s.setPosition(0.5, s.posY, 5);
        s.lastReportedPosX = 0.5;
        s.lastReportedPosZ += shift;
        return s;
    }

    /** Ticks for a fixed policy: run with W+A at 45 degrees, jumping (W, facing +z) whenever standing if {@code jump}. */
    public static int policy(double gap, double distance, boolean jump) {
        SparseBlockWorld w = world(gap);
        PlayerState s = entry();
        PlayerInput run = PlayerInput.of(PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT, 45);
        PlayerInput hop = PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 0);
        for (int t = 1; t <= 400; t++) {
            s = Physics.tick(w, s, jump && s.onGround ? hop : run);
            if (s.posZ >= 5 + distance) return t;
        }
        return -1;
    }

    public static int search(double gap, double distance, SegmentSearch.Options o) {
        List<SegmentSearch.Result> rs = new SegmentSearch().search(world(gap), entry(), -100,
                Exit.plane(0.5, 5, 0, distance, StepWindow.FLOOR, false), 0, o);
        return rs.isEmpty() ? -1 : rs.get(0).ticks();
    }
}
