package io.github.konqasasas.mctas.improve;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;

/**
 * A whole run from the spawn, judged: the inputs, the state after every tick and the ticks of the
 * start entry and the goal. Immutable once built by {@link Course#evaluate}.
 *
 * <p>Runs are ordered by {@link #ticks()} and then by {@link #slack}: how far the run is from saving
 * one more tick (the feet's distance to the goal one tick before the goal, plus how deep inside the
 * start the feet are on the tick the run enters it). Driving the slack to zero is how a continuous
 * change finds the next whole tick.
 */
public final class Run {
    /** The inputs, cut after the goal tick when the run finishes. */
    final PlayerInput[] inputs;
    /** {@code states[t]} is the state after {@code t} ticks; {@code states[0]} is the spawn. */
    final PlayerState[] states;
    /** {@code lastToggle[t]}: the last tick at or before {@code t} that changed sprint or sneak, or a large negative. */
    final int[] lastToggle;
    final int startTick;
    final int goalTick;
    /** Finished inside the rules: toggle gap, no unsupported action, no damage. */
    final boolean valid;
    /** Ticks simulated (where the run stopped). */
    final int length;
    final double slack;
    /** Why the run is not valid, for logging. */
    final String failure;

    Run(PlayerInput[] inputs, PlayerState[] states, int[] lastToggle, int length, int startTick, int goalTick,
        boolean valid, double slack, String failure) {
        this.inputs = inputs;
        this.states = states;
        this.lastToggle = lastToggle;
        this.length = length;
        this.startTick = startTick;
        this.goalTick = goalTick;
        this.valid = valid;
        this.slack = slack;
        this.failure = failure;
    }

    public boolean valid() {
        return valid;
    }

    /** Ticks from the start entry to the goal; only for valid runs. */
    public int ticks() {
        return goalTick - startTick;
    }

    public double slack() {
        return slack;
    }

    public int startTick() {
        return startTick;
    }

    public int goalTick() {
        return goalTick;
    }

    public PlayerInput[] inputs() {
        return inputs.clone();
    }

    /** One number to minimise: the ticks, plus a fraction below 1 that grows with the slack. Invalid runs are infinite. */
    double fitness() {
        return valid ? ticks() + slack / (1 + slack) : Double.POSITIVE_INFINITY;
    }

    /** True when this run is strictly better than {@code o} (an invalid run is worse than any valid one). */
    public boolean betterThan(Run o) {
        if (!valid) return false;
        if (o == null || !o.valid) return true;
        if (ticks() != o.ticks()) return ticks() < o.ticks();
        return slack < o.slack - 1e-12;
    }

    @Override
    public String toString() {
        return valid ? ticks() + " ticks (start " + startTick + ", goal " + goalTick + ", slack "
                + String.format("%.4f", slack) + ")" : "invalid: " + failure;
    }
}
