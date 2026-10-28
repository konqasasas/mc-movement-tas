package io.github.konqasasas.mctas.problem.input;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class TickInput {
    private final EnumSet<Key> keys;
    public final float yaw;
    public final float pitch;
    /** The hotbar slot (0 to 8) selected by a number key this tick, or -1 for none. */
    public final int slot;

    public TickInput(Set<Key> keys, float yaw, float pitch) {
        this(keys, yaw, pitch, -1);
    }

    public TickInput(Set<Key> keys, float yaw, float pitch, int slot) {
        if (slot < -1 || slot > 8) throw new IllegalArgumentException("slot must be 0 to 8, or -1 for none: " + slot);
        this.keys = keys.isEmpty() ? EnumSet.noneOf(Key.class) : EnumSet.copyOf(keys);
        this.yaw = yaw;
        this.pitch = pitch;
        this.slot = slot;
    }

    public boolean has(Key k) {
        return keys.contains(k);
    }

    public Set<Key> keys() {
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public String toString() {
        return keys + " yaw=" + yaw + " pitch=" + pitch + (slot >= 0 ? " slot=" + slot : "");
    }
}
