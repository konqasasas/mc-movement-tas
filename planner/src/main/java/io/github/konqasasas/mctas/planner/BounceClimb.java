package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.List;

/**
 * A bounce off slime up into a body of water or ladders, then the climb out of it: the {@link Step}
 * lands on the slime and steers the bounce at the entry cell; once the player is in the body, the
 * {@link ClimbStep} takes over (its first leg is already done).
 */
final class BounceClimb implements Move {
    final Step bounce;
    final ClimbStep climb;

    BounceClimb(Step bounce, ClimbStep climb) {
        this.bounce = bounce;
        this.climb = climb;
    }

    @Override
    public void play(Run r, List<PlayerInput> out) {
        for (int k = 0; r.s.onGround; k++) {
            if (k >= Step.MAX_GROUND) {
                r.dead = true;
                return;
            }
            Step.tick(r, k == bounce.jumpAt ? bounce.jump(r) : bounce.move(r), out);
            if (r.over()) return;
        }
        boolean off = false;
        for (int i = 0; i < Step.MAX_AIR; i++) {
            if (off && climb.inBody(r)) {
                climb.play(r, out);
                return;
            }
            // Landed without getting into the body: the move fails.
            if (r.s.onGround && !Step.bounced(r)) break;
            Step.tick(r, bounce.move(r, off), out);
            if (r.over()) return;
            off |= Step.bounced(r);
        }
        r.dead = true;
    }

    @Override
    public String toString() {
        return "bounce " + bounce + " then " + climb;
    }
}
