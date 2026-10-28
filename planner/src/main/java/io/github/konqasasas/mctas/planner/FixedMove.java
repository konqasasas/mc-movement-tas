package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.List;

/** A move given as fixed inputs (from a local technique search, or a run-up); it does not adapt when replayed from elsewhere. */
final class FixedMove implements Move {
    final List<PlayerInput> inputs;

    FixedMove(List<PlayerInput> inputs) {
        this.inputs = new ArrayList<PlayerInput>(inputs);
    }

    @Override
    public void play(Run r, List<PlayerInput> out) {
        for (PlayerInput a : inputs) {
            if (r.over()) return;
            PlayerInput l = r.legal(a);
            r.step(l);
            out.add(l);
        }
    }

    @Override
    public String toString() {
        return "fixed " + inputs.size() + " ticks";
    }
}
