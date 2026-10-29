package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.judge.Hazards;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Damage and trampling (D-005) as the judge has them ({@link Hazards}): a run that takes either is not
 * used. Only checked on a course that has such blocks.
 */
final class Hazard {
    private Hazard() {
    }

    private static final Map<SparseBlockWorld, Boolean> PRESENT = Collections.synchronizedMap(new WeakHashMap<SparseBlockWorld, Boolean>());

    private static boolean present(SparseBlockWorld w) {
        Boolean a = PRESENT.get(w);
        if (a == null) {
            a = Hazards.present(w);
            PRESENT.put(w, a);
        }
        return a;
    }

    /** Whether the tick from {@code before} to {@code after} takes damage or tramples farmland. */
    static boolean hurts(SparseBlockWorld w, PlayerState before, PlayerState after) {
        return present(w) && Hazards.check(w, before, after) != null;
    }

    /** Whether {@code inputs} from {@code s} take damage or trample farmland on some tick. */
    static boolean hurts(SparseBlockWorld w, PlayerState s, List<PlayerInput> inputs) {
        if (!present(w)) return false;
        for (PlayerInput a : inputs) {
            PlayerState ns = Physics.tick(w, s, a);
            if (Hazards.check(w, s, ns) != null) return true;
            s = ns;
        }
        return false;
    }
}
