package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.provisional.PlayerState;
import io.github.konqasasas.mctas.problem.provisional.ReferenceStepper;

public final class ProvisionalModel implements MovementModel<PlayerState> {
    private final ReferenceStepper stepper;

    public ProvisionalModel(Problem problem) {
        this.stepper = new ReferenceStepper(problem.world);
    }

    @Override
    public String name() {
        return "provisional-1.12.2-fullblock";
    }

    @Override
    public PlayerState spawn(Problem problem) {
        return PlayerState.standingAt(problem.spawn);
    }

    @Override
    public PlayerState tick(PlayerState state, TickInput input) {
        return stepper.tick(state, input);
    }

    @Override
    public Vec3 position(PlayerState state) {
        return new Vec3(state.x(), state.y(), state.z());
    }
}
