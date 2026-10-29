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
            S before = state;
            state = model.tick(state, in);
            boolean done = judge.observe(model.position(state));
            // (Also on the tick that reaches the goal: getting hurt there makes the run invalid too.)
            String v = model.violation(before, state);
            if (v != null) {
                judge.reject(v);
                break;
            }
            if (done) break;
        }
        return judge.result();
    }

    public static RunResult run(Problem problem, List<TickInput> inputs) {
        return run(problem, new PhysicsModel(problem), inputs);
    }
}
