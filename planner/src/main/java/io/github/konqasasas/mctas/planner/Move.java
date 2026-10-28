package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.List;

/** One edge of the plan: plays from a run (changed in place) and appends the inputs it used. */
interface Move {
    void play(Run r, List<PlayerInput> out);
}
