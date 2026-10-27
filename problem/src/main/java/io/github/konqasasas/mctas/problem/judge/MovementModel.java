package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.input.TickInput;

public interface MovementModel<S> {
    String name();

    S spawn(Problem problem);

    S tick(S state, TickInput input);

    Vec3 position(S state);
}
