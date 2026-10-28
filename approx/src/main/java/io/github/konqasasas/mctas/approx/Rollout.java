package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Runs a whole input list with the judge's rules, and converts between the input types. */
final class Rollout {
    private Rollout() {
    }

    /**
     * The input without its sprint key when pressing it now would start sprinting too soon after the
     * last toggle (say a wall just stopped the sprint), or while still pressed against the wall that
     * would stop it again, so the toggle rule holds; otherwise the input.
     */
    static PlayerInput holdSprint(PlayerState s, PlayerInput a, int lastToggle, int t) {
        if (!a.has(PlayerInput.SPRINT) || s.sprinting || (ToggleRule.allowed(lastToggle, t) && !s.collidedHorizontally)) return a;
        return new PlayerInput(a.keys & ~PlayerInput.SPRINT, a.yaw, a.pitch, a.slot);
    }

    /** The input with the sneak key as it is now when changing it now would break the toggle rule. */
    static PlayerInput holdSneak(PlayerState s, PlayerInput a, int lastToggle, int t) {
        if (a.has(PlayerInput.SNEAK) == s.isSneaking() || ToggleRule.allowed(lastToggle, t)) return a;
        return new PlayerInput(s.isSneaking() ? a.keys | PlayerInput.SNEAK : a.keys & ~PlayerInput.SNEAK, a.yaw, a.pitch, a.slot);
    }

    static PlayerState spawn(Problem p) {
        PlayerState s = PlayerState.standing(p.spawn.pos.x, p.spawn.pos.y, p.spawn.pos.z, p.spawn.yaw);
        s.rotationPitch = p.spawn.pitch;
        return s;
    }

    /**
     * The ticks from the last start entry to the first goal tick, or -1 when the inputs do not finish
     * or break the toggle rule. {@code goalTick[0]} receives the goal tick.
     */
    static int ticks(Problem p, List<PlayerInput> inputs, int[] goalTick) {
        Region start = p.start, goal = p.goal;
        PlayerState s = spawn(p);
        boolean in = start.contains(s.posX, s.posY, s.posZ);
        int entry = in ? 0 : -1;
        int last = Integer.MIN_VALUE / 2;
        for (int i = 0; i < inputs.size() && i < p.maxTicks; i++) {
            int t = i + 1;
            PlayerState ns = Physics.tick(p.world, s, inputs.get(i));
            if (ToggleRule.toggles(s, ns)) {
                if (!ToggleRule.allowed(last, t)) return -1;
                last = t;
            }
            s = ns;
            if (entry >= 0 && goal.contains(s.posX, s.posY, s.posZ)) {
                if (goalTick != null) goalTick[0] = t;
                return t - entry;
            }
            boolean nin = start.contains(s.posX, s.posY, s.posZ);
            if (nin && !in) entry = t;
            in = nin;
            if (s.posY < p.voidY) return -1;
        }
        return -1;
    }

    static TickInput toTick(PlayerInput a) {
        Set<Key> k = EnumSet.noneOf(Key.class);
        if (a.has(PlayerInput.W)) k.add(Key.W);
        if (a.has(PlayerInput.A)) k.add(Key.A);
        if (a.has(PlayerInput.S)) k.add(Key.S);
        if (a.has(PlayerInput.D)) k.add(Key.D);
        if (a.has(PlayerInput.JUMP)) k.add(Key.JUMP);
        if (a.has(PlayerInput.SPRINT)) k.add(Key.SPRINT);
        if (a.has(PlayerInput.SNEAK)) k.add(Key.SNEAK);
        return new TickInput(k, a.yaw, a.pitch);
    }

    static List<TickInput> toTicks(List<PlayerInput> in) {
        List<TickInput> out = new ArrayList<TickInput>(in.size());
        for (PlayerInput a : in) out.add(toTick(a));
        return out;
    }
}
