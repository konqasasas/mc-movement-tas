package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.input.TickInput;

public interface MovementModel<S> {
    String name();

    S spawn(Problem problem);

    S tick(S state, TickInput input);

    Vec3 position(S state);

    /** Why the tick from {@code before} to {@code after} breaks D-005 (see {@link Hazards}), or null. */
    default String violation(S before, S after) {
        return null;
    }
}
