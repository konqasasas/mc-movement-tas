package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.problem.Problem;

import java.util.List;

/** Local improvement of a finished input list. */
final class Refiner {
    static final class Outcome {
        final List<PlayerInput> inputs;
        final int ticks;

        Outcome(List<PlayerInput> inputs, int ticks) {
            this.inputs = inputs;
            this.ticks = ticks;
        }
    }

    private final Problem problem;
    private final Route route;
    private long deadline = Long.MAX_VALUE;

    Refiner(Problem problem, Route route) {
        this.problem = problem;
        this.route = route;
    }

    Refiner deadline(long epochMillis) {
        this.deadline = epochMillis;
        return this;
    }

    Outcome improve(List<PlayerInput> inputs, int ticks) {
        return new Outcome(inputs, ticks);
    }
}
