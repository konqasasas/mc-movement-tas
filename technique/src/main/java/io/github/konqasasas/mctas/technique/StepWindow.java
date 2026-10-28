package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * Where to jump to climb a step without touching its face: a sweep with the exact physics.
 *
 * <p>The player runs along +z on a flat floor (top at y 10), sprinting with W+A facing 45 degrees (the
 * fastest ground run), for {@code runTicks} ticks from a standstill, which sets its speed. A step of
 * height {@code h} has its face at z = {@code face}. Optionally a ceiling covers the run with a gap
 * of {@code ceilingGap} blocks up to {@code ceilingEnd} blocks before the face. On the next tick the
 * player jumps (W, facing +z, so the sprint boost goes forward) and then holds W+A at 45 degrees. A jump
 * is clean when the player lands on the step without a horizontal collision (which would stop sprint,
 * a toggle under D-004, and kill the speed). The sweep moves the face, so the distance from the
 * player's front (z + 0.3) to the face at the jump varies while the run stays the same.
 */
public final class StepWindow {
    /** One jump distance. */
    public static final class Jump {
        /** From the player's front to the face, at the jump tick (before moving). */
        public final double distance;
        public final boolean clean;
        /** Ticks from the jump input to the first tick standing on the step, or -1. */
        public final int landTicks;
        /** Horizontal speed on landing. */
        public final double landSpeed;

        Jump(double distance, boolean clean, int landTicks, double landSpeed) {
            this.distance = distance;
            this.clean = clean;
            this.landTicks = landTicks;
            this.landSpeed = landSpeed;
        }
    }

    private StepWindow() {
    }

    static final BlockType STONE = io.github.konqasasas.mctas.problem.world.Blocks.byId("minecraft:stone");
    static final BlockType SLAB = io.github.konqasasas.mctas.problem.world.Blocks.byId("minecraft:stone_slab[half=bottom]");
    static final BlockType BOTTOM_TRAPDOOR = io.github.konqasasas.mctas.problem.world.Blocks.byId(
            "minecraft:iron_trapdoor[facing=north,half=bottom,open=false]");

    /** Height of the floor's top face. */
    static final int FLOOR = 10;

    /**
     * The world: floor top at {@link #FLOOR} over z -40..40, step of height h (0.5, 0.1875 with a closed
     * bottom trapdoor, or whole blocks) from
     * z face.
     */
    static SparseBlockWorld world(double h, int face, double ceilingGap, int ceilingEnd) {
        SparseBlockWorld w = new SparseBlockWorld();
        w.fill(-3, FLOOR - 1, -40, 3, FLOOR - 1, 40, STONE);
        int full = (int) Math.floor(h);
        if (full > 0) w.fill(-3, FLOOR, face, 3, FLOOR + full - 1, 40, STONE);
        if (h - full == 0.1875) w.fill(-3, FLOOR + full, face, 3, FLOOR + full, 40, BOTTOM_TRAPDOOR);
        else if (h - full > 0) w.fill(-3, FLOOR + full, face, 3, FLOOR + full, 40, SLAB);
        if (ceilingGap > 0) {
            int cy = FLOOR + (int) Math.round(ceilingGap);
            w.fill(-3, cy, -40, 3, cy, face - ceilingEnd - 1, STONE);
        }
        return w;
    }

    /** The state after sprinting from a standstill at z 0 for {@code runTicks} ticks (on a flat floor). */
    public static PlayerState runUp(int runTicks) {
        SparseBlockWorld flat = world(0, 40, 0, 0);
        PlayerState s = PlayerState.standing(0.5, FLOOR, -30, 0);
        PlayerInput run = PlayerInput.of(PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT, 45);
        for (int i = 0; i < runTicks; i++) s = Physics.tick(flat, s, run);
        return s;
    }

    /**
     * The first tick on the ground after {@code hops} sprint jumps on a flat floor (jumping again on
     * each landing, W+A at 45 degrees in the air): the speed a player arrives with in a jump chain.
     */
    public static PlayerState landing(int hops) {
        SparseBlockWorld flat = world(0, 40, 0, 0);
        PlayerState s = runUp(1);
        PlayerInput run = PlayerInput.of(PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT, 45);
        PlayerInput jump = PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 0);
        for (int h = 0; h < hops; h++) {
            s = Physics.tick(flat, s, jump);
            while (!s.onGround) s = Physics.tick(flat, s, run);
        }
        return s;
    }

    /** Horizontal distance the player moves on the next tick if it keeps running (W+A at 45 degrees). */
    public static double stride(PlayerState s) {
        PlayerState n = Physics.tick(world(0, 40, 0, 0), s,
                PlayerInput.of(PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT, 45));
        return Math.hypot(n.posX - s.posX, n.posZ - s.posZ);
    }

    /** Ways to take off: the jump tick's input. */
    public enum Takeoff {
        /** W facing the step: the sprint boost adds 0.2 forward. */
        FORWARD(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 0),
        /** W+D facing 135 degrees left of the step: the boost points back and to the side and cancels speed. */
        BACK_DIAGONAL(PlayerInput.W | PlayerInput.D | PlayerInput.SPRINT | PlayerInput.JUMP, -135);

        public final PlayerInput input;

        Takeoff(int keys, float yaw) {
            this.input = PlayerInput.of(keys, yaw);
        }
    }

    /**
     * Sweeps the face so that the front-to-face distance at the jump runs over [0, maxDistance] in
     * steps of about {@code step} (the face is a whole block, so the run's start is shifted instead).
     */
    public static List<Jump> sweep(double h, double ceilingGap, int ceilingEnd, PlayerState base, Takeoff takeoff,
                                   double maxDistance, double step) {
        List<Jump> out = new ArrayList<Jump>();
        PlayerInput jump = takeoff.input;
        PlayerInput air = PlayerInput.of(PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT, 45);
        int face = 30;
        SparseBlockWorld w = world(h, face, ceilingGap, ceilingEnd);
        for (double d = step / 2; d <= maxDistance + 1e-9; d += step) {
            // Shift the run so the front is d before the face at the jump.
            PlayerState s = base.copy();
            double shift = face - 0.3 - d - s.posZ;
            s.setPosition(s.posX, s.posY, s.posZ + shift);
            s.lastReportedPosZ += shift;
            out.add(play(w, s, d, h, jump, air));
        }
        return out;
    }

    private static Jump play(SparseBlockWorld w, PlayerState s, double d, double h, PlayerInput jump,
                             PlayerInput air) {
        for (int k = 1; k <= 30; k++) {
            PlayerState ns = Physics.tick(w, s, k == 1 ? jump : air);
            if (ns.collidedHorizontally || (k > 1 && ns.sprinting != s.sprinting)) return new Jump(d, false, -1, 0);
            s = ns;
            if (s.onGround && k > 1) {
                boolean on = Math.abs(s.posY - FLOOR - h) < 1e-6;
                return new Jump(d, on, on ? k : -1, Math.hypot(s.motionX, s.motionZ));
            }
        }
        return new Jump(d, false, -1, 0);
    }
}
