package io.github.konqasasas.mctas.problem.input;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class TickInput {
    private final EnumSet<Key> keys;
    public final float yaw;
    public final float pitch;

    public TickInput(Set<Key> keys, float yaw, float pitch) {
        this.keys = keys.isEmpty() ? EnumSet.noneOf(Key.class) : EnumSet.copyOf(keys);
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public boolean has(Key k) {
        return keys.contains(k);
    }

    public Set<Key> keys() {
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public String toString() {
        return keys + " yaw=" + yaw + " pitch=" + pitch;
    }
}
