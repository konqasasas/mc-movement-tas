package io.github.konqasasas.mctas.improve;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.Random;

/**
 * Mends a run that breaks the 3-tick toggle rule (D-004) somewhere after an edit. A rebuilt stretch
 * moves the run a few ticks ahead, and a sprint that stops on a wall hit, or a sprint or sneak key the
 * old inputs press, then lands one or two ticks after another toggle. Most failed splices fail this
 * way (F-607), far from the rebuilt stretch, while the run would otherwise go on to the goal.
 *
 * <p>Each round tries small edits around the two clashing toggles: delay the later key change, move
 * the earlier one forward, drop or hold the sprint key over the gap, or turn the view a little to
 * miss the wall. The edit that gets furthest is kept, and the next round mends the next clash.
 */
final class ToggleFix {
    private static final int KEYS = PlayerInput.SPRINT | PlayerInput.SNEAK;
    private static final float[] NUDGES = {0.3F, -0.3F, 1, -1, 3, -3};

    int maxRounds = 8;
    int tries = 24;
    /**
     * Count only clashes as progress: a run that passes the clash but then wanders off and never
     * reaches the goal is worse, not further. Off for splices, where such runs still feed the repair
     * rounds (F-607); on for mending whole seeds.
     */
    boolean onlyClashes;

    /** The tick of a toggle-gap failure, or -1 for any other run. */
    static int clash(Run r) {
        if (r == null || r.valid || r.failure == null || !r.failure.startsWith("toggle gap at tick ")) return -1;
        return Integer.parseInt(r.failure.substring("toggle gap at tick ".length()));
    }

    /** {@code run} mended as far as the rounds go; {@code run} itself when nothing helps. */
    Run apply(Course c, Run run, Random rnd) {
        for (int round = 0; round < maxRounds; round++) {
            int t = clash(run);
            if (t < 2) return run;
            int l = Math.max(1, run.lastToggle[t - 1]);
            Run best = null;
            for (int k = 0; k < tries; k++) {
                PlayerInput[] in = run.inputs.clone();
                int from = edit(in, l, t, rnd, onlyClashes);
                if (from < 0) continue;
                Run x = c.evaluate(run, from, in);
                if (better(x, best, onlyClashes)) best = x;
            }
            if (best == null || !better(best, run, onlyClashes)) return run;
            run = best;
        }
        return run;
    }

    private static boolean better(Run x, Run o, boolean onlyClashes) {
        if (o == null) return true;
        if (x.valid || o.valid) return x.betterThan(o);
        if (onlyClashes) return progress(x) > progress(o);
        return x.length > o.length;
    }

    /** How far an invalid run gets while it still only breaks the toggle rule: its clash tick, or -1. */
    private static int progress(Run r) {
        return clash(r);
    }

    /** One random edit near the toggles at {@code l} and {@code t}; returns the first changed input, or -1. */
    private static int edit(PlayerInput[] in, int l, int t, Random rnd, boolean more) {
        // in[i] makes tick i + 1, so the toggle at t comes from in[t - 1].
        int n = in.length;
        switch (rnd.nextInt(more ? 7 : 5)) {
            case 5: { // press sprint again a few ticks later instead of at the later change
                int d = 1 + rnd.nextInt(3);
                if (t - 1 + d >= n || !in[t - 1].has(PlayerInput.SPRINT)) return -1;
                in[t - 1] = Edits.withKeys(in[t - 1], in[t - 1].keys & ~PlayerInput.SPRINT);
                for (int i = t; i <= t - 1 + d; i++) in[i] = Edits.withKeys(in[i], in[i].keys & ~PlayerInput.SPRINT);
                in[t - 1 + d] = Edits.withKeys(in[t - 1 + d], in[t - 1 + d].keys | PlayerInput.SPRINT);
                return t - 1;
            }
            case 6: { // keep W down on a strafe-only tick near the clash, facing so it moves the same way
                int from = Math.max(0, l - 2);
                for (int i = from; i < Math.min(n, t); i++) {
                    PlayerInput x = in[i];
                    if (x.has(PlayerInput.W) || x.has(PlayerInput.S) || x.has(PlayerInput.A) == x.has(PlayerInput.D)) continue;
                    float yaw = x.has(PlayerInput.A) ? x.yaw - 90 : x.yaw + 90;
                    int k = (x.keys & ~(PlayerInput.A | PlayerInput.D)) | PlayerInput.W;
                    in[i] = new PlayerInput(k, yaw, x.pitch, x.slot);
                    return i;
                }
                return -1;
            }
            case 0: { // delay the later key change: hold the keys from before it
                int d = 1 + rnd.nextInt(3);
                int keys = in[t - 2].keys & KEYS;
                for (int i = t - 1; i < Math.min(n, t - 1 + d); i++) in[i] = Edits.withKeys(in[i], (in[i].keys & ~KEYS) | keys);
                return t - 1;
            }
            case 1: { // move the earlier change forward
                if (l < 2) return -1;
                int d = 1 + rnd.nextInt(3);
                int keys = in[l - 1].keys & KEYS;
                int from = Math.max(0, l - 1 - d);
                for (int i = from; i < l - 1; i++) in[i] = Edits.withKeys(in[i], (in[i].keys & ~KEYS) | keys);
                return from;
            }
            case 2: { // release or press sprint over the gap
                boolean on = rnd.nextBoolean();
                int from = Math.max(0, l - 1 - rnd.nextInt(3));
                for (int i = from; i < Math.min(n, t + rnd.nextInt(4)); i++) {
                    int k = on ? in[i].keys | PlayerInput.SPRINT : in[i].keys & ~PlayerInput.SPRINT;
                    in[i] = Edits.withKeys(in[i], k);
                }
                return from;
            }
            default: { // turn a little before the later toggle, to miss the wall or meet it later
                float dy = NUDGES[rnd.nextInt(NUDGES.length)];
                int from = Math.max(0, t - 2 - rnd.nextInt(8));
                int to = rnd.nextBoolean() ? t : Math.min(n, t + 1 + rnd.nextInt(10));
                for (int i = from; i < to; i++) in[i] = Edits.withYaw(in[i], in[i].yaw + dy);
                return from;
            }
        }
    }
}
