package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.List;

/**
 * The inputs a technique search tries each tick, around a heading. The view is free: for each
 * movement direction (heading plus an offset) there are three ways to move that way, W facing it,
 * W+A facing 45 degrees right of it and W+D facing 45 degrees left (the 45-degree strafe is about 2%
 * faster, but a jump's sprint boost goes where the view faces). Sprint is always held, so the search
 * never drops sprint on purpose except through "no movement key".
 */
public final class Actions {
    /** Direction offsets from the heading, in degrees, used by default. */
    public static final float[] DEFAULT_OFFSETS = {0, 4, -4, 10, -10, 22.5F, -22.5F, 45, -45, 90, -90};

    private Actions() {
    }

    /**
     * All inputs for the heading: every offset in three strafe modes, with and without jump, and with
     * and without sneak when {@code sneak} is set; plus releasing every movement key (with and without
     * jump), which stops sprinting.
     */
    public static List<PlayerInput> around(float headingYaw, float[] offsets, boolean sneak) {
        List<PlayerInput> out = new ArrayList<PlayerInput>();
        int[] sneaks = sneak ? new int[] {0, PlayerInput.SNEAK} : new int[] {0};
        for (int n : sneaks) {
            for (float off : offsets) {
                float dir = headingYaw + off;
                for (int jump : new int[] {0, PlayerInput.JUMP}) {
                    int base = PlayerInput.SPRINT | jump | n;
                    out.add(PlayerInput.of(base | PlayerInput.W, dir));
                    out.add(PlayerInput.of(base | PlayerInput.W | PlayerInput.A, dir + 45));
                    out.add(PlayerInput.of(base | PlayerInput.W | PlayerInput.D, dir - 45));
                }
            }
            out.add(PlayerInput.of(PlayerInput.SPRINT | n, headingYaw));
            out.add(PlayerInput.of(PlayerInput.SPRINT | PlayerInput.JUMP | n, headingYaw));
        }
        return out;
    }
}
