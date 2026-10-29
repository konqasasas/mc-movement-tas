package io.github.konqasasas.mctas.improve;

import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.judge.Hazards;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;

import java.util.Arrays;

/**
 * One problem and the exact judge of a whole run, as {@code mctas-problem time --strict} judges it:
 * the time is the goal tick minus the last start entry, sprint and sneak changes keep the 3-tick gap
 * (D-004), and states with {@code unsupportedAction} (D-010, D-011) make the run invalid. On top of
 * the judge, runs that take damage or trample farmland are invalid (D-005, {@link Hazards}, which the
 * judge checks too).
 *
 * <p>Thread-safe: {@link Physics#tick} does not change the world.
 */
public final class Course {
    public final Problem problem;
    public final BlockWorld world;
    private final PlayerState spawn;
    private final boolean hazards;
    /**
     * Runs that enter the start for the last time after this tick are invalid. The run-up is free
     * (D-003), but without a limit the edits that repeat ticks before the start grow it without end.
     */
    volatile int maxStartTick = Integer.MAX_VALUE;

    public Course(Problem problem) {
        this.problem = problem;
        this.world = problem.world;
        this.spawn = new PhysicsModel(problem).spawn(problem);
        this.hazards = Hazards.present(problem.world);
    }

    public PlayerState spawn() {
        return spawn;
    }

    /** Judges {@code inputs} from the spawn. */
    public Run evaluate(PlayerInput[] inputs) {
        return evaluate(null, 0, inputs);
    }

    /**
     * Judges {@code inputs}, reusing the states of {@code base} up to tick {@code from}. The caller
     * promises that the first {@code from} inputs are the same as {@code base}'s.
     */
    public Run evaluate(Run base, int from, PlayerInput[] inputs) {
        int n = inputs.length;
        int cap = Math.min(n, problem.maxTicks);
        PlayerState[] states = new PlayerState[cap + 1];
        int[] lastToggle = new int[cap + 1];
        if (base == null) from = 0;
        from = Math.max(0, Math.min(from, Math.min(base == null ? 0 : base.length, cap)));
        if (from == 0) {
            states[0] = spawn;
            lastToggle[0] = Integer.MIN_VALUE / 2;
        } else {
            System.arraycopy(base.states, 0, states, 0, from + 1);
            System.arraycopy(base.lastToggle, 0, lastToggle, 0, from + 1);
        }
        Region start = problem.start, goal = problem.goal;
        int lastStart = start.contains(spawn.posX, spawn.posY, spawn.posZ) ? 0 : -1;
        for (int t = 1; t <= from; t++) {
            PlayerState p = states[t - 1], s = states[t];
            if (start.contains(s.posX, s.posY, s.posZ) && !start.contains(p.posX, p.posY, p.posZ)) lastStart = t;
        }
        String failure = null;
        int goalTick = -1;
        int t = from;
        while (t < cap) {
            PlayerState p = states[t];
            PlayerState s = Physics.tick(world, p, inputs[t]);
            t++;
            states[t] = s;
            lastToggle[t] = lastToggle[t - 1];
            if (ToggleRule.toggles(p, s)) {
                if (!ToggleRule.allowed(lastToggle[t - 1], t)) {
                    failure = "toggle gap at tick " + t;
                    break;
                }
                lastToggle[t] = t;
            }
            if (s.unsupportedAction) {
                failure = "unsupported action at tick " + t;
                break;
            }
            if (hazards && Hazards.check(world, p, s) != null) {
                failure = "damage or trampling at tick " + t;
                break;
            }
            if (lastStart >= 0 && goal.contains(s.posX, s.posY, s.posZ)) {
                goalTick = t;
                break;
            }
            if (start.contains(s.posX, s.posY, s.posZ) && !start.contains(p.posX, p.posY, p.posZ)) lastStart = t;
            if (s.posY < problem.voidY) {
                failure = "fell into the void at tick " + t;
                break;
            }
        }
        if (failure == null && goalTick >= 0 && lastStart > maxStartTick) failure = "run-up longer than " + maxStartTick;
        if (failure == null && goalTick < 0) failure = t >= problem.maxTicks ? "too long" : "did not finish";
        boolean valid = failure == null;
        int len = t;
        PlayerInput[] used = len == n ? inputs : Arrays.copyOf(inputs, len);
        PlayerState[] st = len == cap ? states : Arrays.copyOf(states, len + 1);
        int[] lt = len == cap ? lastToggle : Arrays.copyOf(lastToggle, len + 1);
        double slack = 0;
        if (valid) {
            PlayerState before = st[goalTick - 1];
            slack = distance(goal, before.posX, before.posY, before.posZ);
            if (lastStart > 0) {
                PlayerState in = st[lastStart];
                slack += depth(start, in.posX, in.posY, in.posZ);
            }
        }
        // Keep the full input list when the run fails, so a later repair can still use its tail.
        return new Run(valid ? used : inputs, st, lt, len, lastStart, goalTick, valid, slack, failure);
    }

    static double distance(Region r, double x, double y, double z) {
        double ex = Math.max(0, Math.max(r.minX - x, x - r.maxX));
        double ey = Math.max(0, Math.max(r.minY - y, y - r.maxY));
        double ez = Math.max(0, Math.max(r.minZ - z, z - r.maxZ));
        return Math.sqrt(ex * ex + ey * ey + ez * ez);
    }

    /** How far inside the region the point is: the distance to its nearest face. */
    static double depth(Region r, double x, double y, double z) {
        double d = Math.min(Math.min(x - r.minX, r.maxX - x), Math.min(z - r.minZ, r.maxZ - z));
        return Math.max(0, Math.min(d, Math.min(y - r.minY, r.maxY - y)));
    }
}
