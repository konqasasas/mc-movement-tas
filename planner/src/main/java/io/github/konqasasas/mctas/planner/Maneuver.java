package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.List;

/**
 * A source of transitions: from a run standing on (or flying towards) a foothold, the runs it can reach
 * next. The planner's own {@link StepManeuver} is one; a local technique search (WS8) plugs in here too.
 */
interface Maneuver {
    final class Successor {
        final Move move;
        final Run run;
        final List<PlayerInput> inputs;

        Successor(Move move, Run run, List<PlayerInput> inputs) {
            this.move = move;
            this.run = run;
            this.inputs = inputs;
        }
    }

    /** Adds the successors of {@code from} (not changed) towards the given aims to {@code out}. */
    void expand(Run from, List<double[]> aims, List<Successor> out);
}
