package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;

import java.util.List;

public final class Evaluator {
    private Evaluator() {
    }

    public static <S> RunResult run(Problem problem, MovementModel<S> model, List<TickInput> inputs) {
        Judge judge = new Judge(problem);
        S state = model.spawn(problem);
        for (TickInput in : inputs) {
            state = model.tick(state, in);
            if (judge.observe(model.position(state))) break;
        }
        return judge.result();
    }

    public static RunResult run(Problem problem, List<TickInput> inputs) {
        return run(problem, new ProvisionalModel(problem), inputs);
    }
}
