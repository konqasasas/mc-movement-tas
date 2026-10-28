package io.github.konqasasas.mctas.improve;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Helpers for editing input lists. */
final class Edits {
    /** Copies of the last input appended after a spliced or shortened run, so it can still reach the goal. */
    static final int PAD = 40;

    private Edits() {
    }

    /** {@code head} (first {@code headLen} inputs), then {@code mid}, then {@code tail} from {@code tailFrom}, then padding. */
    static PlayerInput[] splice(PlayerInput[] head, int headLen, List<PlayerInput> mid, PlayerInput[] tail, int tailFrom) {
        List<PlayerInput> out = new ArrayList<PlayerInput>(headLen + mid.size() + PAD
                + Math.max(0, tail.length - tailFrom));
        out.addAll(Arrays.asList(head).subList(0, headLen));
        out.addAll(mid);
        if (tailFrom < tail.length) out.addAll(Arrays.asList(tail).subList(tailFrom, tail.length));
        return pad(out);
    }

    static PlayerInput[] pad(List<PlayerInput> in) {
        PlayerInput last = in.isEmpty() ? PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT, 0) : in.get(in.size() - 1);
        PlayerInput[] a = in.toArray(new PlayerInput[in.size() + PAD]);
        Arrays.fill(a, in.size(), a.length, last);
        return a;
    }

    /** The direction a W/A/D combination moves in, for an input facing {@code yaw}. NaN when it does not move forward. */
    static float moveDirection(PlayerInput in) {
        if (!in.has(PlayerInput.W) || in.has(PlayerInput.S)) return Float.NaN;
        boolean a = in.has(PlayerInput.A), d = in.has(PlayerInput.D);
        if (a == d) return in.yaw;
        return a ? in.yaw - 45 : in.yaw + 45;
    }

    /** The same keys but moving in {@code dir} with the strafe mode {@code mode}: 0 W, 1 W+A, 2 W+D. */
    static PlayerInput withMode(PlayerInput in, float dir, int mode) {
        int k = in.keys & ~(PlayerInput.A | PlayerInput.D);
        float yaw = dir;
        if (mode == 1) {
            k |= PlayerInput.A;
            yaw = dir + 45;
        } else if (mode == 2) {
            k |= PlayerInput.D;
            yaw = dir - 45;
        }
        return new PlayerInput(k, yaw, in.pitch, in.slot);
    }

    static PlayerInput withYaw(PlayerInput in, float yaw) {
        return new PlayerInput(in.keys, yaw, in.pitch, in.slot);
    }

    static PlayerInput withKeys(PlayerInput in, int keys) {
        return new PlayerInput(keys, in.yaw, in.pitch, in.slot);
    }
}
