package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.world.Blocks;

import java.util.List;

/**
 * Climbing a ladder onto a wall: the fastest way found by {@link SegmentSearch}, against holding W.
 *
 * <p>The floor's top is at {@link StepWindow#FLOOR}. A wall of height {@code h} starts at z = 30
 * (x -3..3), and a one-wide ladder column hangs on its face at x 0, z 29, from the floor to the top.
 * The player starts on the floor at x 0.5 with its front {@code distance} blocks before the ladder
 * block (z 29), either standing or after a sprint run-up. The exit is standing on the wall's top.
 */
public final class LadderBench {
    static final BlockType LADDER = Blocks.byId("minecraft:ladder[facing=north]");
    static final int FACE = 30;

    /** One configuration and its times. */
    public static final class Row {
        public final int height;
        public final double distance;
        public final boolean running;
        /** Ticks holding W (sprint, facing the wall), or -1 when it does not get up. */
        public final int holdW;
        /** The search's ticks, or -1. */
        public final int search;
        public final List<PlayerInput> inputs;

        Row(int height, double distance, boolean running, int holdW, int search, List<PlayerInput> inputs) {
            this.height = height;
            this.distance = distance;
            this.running = running;
            this.holdW = holdW;
            this.search = search;
            this.inputs = inputs;
        }
    }

    private LadderBench() {
    }

    public static SparseBlockWorld world(int h) {
        SparseBlockWorld w = new SparseBlockWorld();
        int f = StepWindow.FLOOR;
        w.fill(-3, f - 1, -40, 3, f - 1, 40, StepWindow.STONE);
        w.fill(-3, f, FACE, 3, f + h - 1, FACE + 3, StepWindow.STONE);
        w.fill(0, f, FACE - 1, 0, f + h - 1, FACE - 1, LADDER);
        return w;
    }

    public static Target top(int h) {
        return new Target(-3, 4, FACE, FACE + 4, StepWindow.FLOOR + h);
    }

    /** The entry state: front {@code distance} before the ladder block, standing or running. */
    public static PlayerState entry(double distance, boolean running) {
        PlayerState s = running ? StepWindow.runUp(40) : PlayerState.standing(0.5, StepWindow.FLOOR, 0, 0);
        double z = FACE - 1 - 0.3 - distance;
        double shift = z - s.posZ;
        s.setPosition(0.5, s.posY, z);
        s.lastReportedPosX = 0.5;
        s.lastReportedPosZ += shift;
        return s;
    }

    public static Row run(int h, double distance, boolean running, SegmentSearch.Options o) {
        SparseBlockWorld w = world(h);
        PlayerState start = entry(distance, running);
        Exit exit = Exit.landing(top(h));
        PlayerState s = start;
        int hold = -1;
        PlayerInput w0 = PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT, 0);
        for (int t = 1; t <= 200; t++) {
            PlayerState ns = Physics.tick(w, s, w0);
            if (exit.reached(s, ns)) {
                hold = t;
                break;
            }
            s = ns;
        }
        List<SegmentSearch.Result> rs = new SegmentSearch().search(w, start, -100, exit, 0, o);
        return new Row(h, distance, running, hold, rs.isEmpty() ? -1 : rs.get(0).ticks(),
                rs.isEmpty() ? null : rs.get(0).inputs);
    }
}
