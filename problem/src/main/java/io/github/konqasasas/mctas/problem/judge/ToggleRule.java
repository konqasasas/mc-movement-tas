package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;

import java.util.ArrayList;
import java.util.List;

/**
 * The replay constraint from the real-client recordings (README): ticks where the sprint state or the
 * sneak state changes must be at least {@link #GAP} ticks apart, or the server's late echo of the
 * player flags can overwrite the client's sprint state.
 *
 * <p>A tick changes the state when {@code sprinting} or {@code prevSneak} after the tick differs from
 * the value before it. Sprint also stops by itself on a wall hit, and that counts too.
 */
public final class ToggleRule {
    public static final int GAP = 3;

    private ToggleRule() {
    }

    /** True when the tick that turned {@code before} into {@code after} changed sprint or sneak. */
    public static boolean toggles(PlayerState before, PlayerState after) {
        return before.sprinting != after.sprinting || before.prevSneak != after.prevSneak;
    }

    /** True when a toggle at {@code tick} keeps the gap to the previous toggle at {@code lastToggle}. */
    public static boolean allowed(int lastToggle, int tick) {
        return tick - lastToggle >= GAP;
    }

    /**
     * The ticks whose toggle comes less than {@link #GAP} ticks after the previous toggle, over the
     * inputs that the run uses (up to the goal tick when it finishes).
     */
    public static List<Integer> violations(Problem problem, List<TickInput> inputs) {
        PhysicsModel model = new PhysicsModel(problem);
        Judge judge = new Judge(problem);
        PlayerState s = model.spawn(problem);
        List<Integer> out = new ArrayList<Integer>();
        int last = Integer.MIN_VALUE / 2;
        for (int i = 0; i < inputs.size(); i++) {
            int t = i + 1;
            PlayerState next = model.tick(s, inputs.get(i));
            if (toggles(s, next)) {
                if (!allowed(last, t)) out.add(t);
                last = t;
            }
            s = next;
            if (judge.observe(model.position(s))) break;
        }
        return out;
    }
}
