package io.github.konqasasas.mctas.physics;

/**
 * One tick of player input: the held keys and the absolute view angles in effect for that tick.
 * Immutable.
 */
public final class PlayerInput {

    public static final int W = 1;
    public static final int A = 1 << 1;
    public static final int S = 1 << 2;
    public static final int D = 1 << 3;
    public static final int JUMP = 1 << 4;
    public static final int SPRINT = 1 << 5;
    public static final int SNEAK = 1 << 6;
    public static final int ALL_KEYS = (1 << 7) - 1;

    private static final String NAMES = "WASDJRN";

    public final int keys;
    /** {@code rotationYaw}, unwrapped, as the game stores it. */
    public final float yaw;
    /** {@code rotationPitch}. Does not affect walking movement; kept for replay. */
    public final float pitch;

    public PlayerInput(int keys, float yaw, float pitch) {
        if ((keys & ~ALL_KEYS) != 0) {
            throw new IllegalArgumentException("unknown key bits: " + keys);
        }
        this.keys = keys;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static PlayerInput of(int keys, float yaw) {
        return new PlayerInput(keys, yaw, 0.0F);
    }

    public boolean has(int key) {
        return (keys & key) != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PlayerInput)) {
            return false;
        }
        PlayerInput p = (PlayerInput) o;
        return keys == p.keys && Float.floatToIntBits(yaw) == Float.floatToIntBits(p.yaw)
                && Float.floatToIntBits(pitch) == Float.floatToIntBits(p.pitch);
    }

    @Override
    public int hashCode() {
        return (keys * 31 + Float.floatToIntBits(yaw)) * 31 + Float.floatToIntBits(pitch);
    }

    /** Keys as letters: W A S D, J = jump, R = sprint, N = sneak. */
    public String keyString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAMES.length(); i++) {
            if ((keys & (1 << i)) != 0) {
                sb.append(NAMES.charAt(i));
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return keyString() + "@" + yaw + "/" + pitch;
    }
}
