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
    /** The right mouse button: places the held block. */
    public static final int USE = 1 << 7;
    /**
     * Closes the open screen (a shulker box's) at the start of the tick, before anything else in it, as
     * {@code EntityPlayerSP.closeScreen} does; the rest of the input then applies as usual.
     */
    public static final int CLOSE = 1 << 8;
    public static final int ALL_KEYS = (1 << 9) - 1;

    private static final String NAMES = "WASDJRNUC";

    public final int keys;
    /** {@code rotationYaw}, unwrapped, as the game stores it. */
    public final float yaw;
    /** {@code rotationPitch}. Does not affect walking movement; aims the right-click. */
    public final float pitch;
    /** The hotbar slot (0 to 8) whose number key is pressed this tick, or -1 for none. */
    public final int slot;

    public PlayerInput(int keys, float yaw, float pitch) {
        this(keys, yaw, pitch, -1);
    }

    public PlayerInput(int keys, float yaw, float pitch, int slot) {
        if ((keys & ~ALL_KEYS) != 0) {
            throw new IllegalArgumentException("unknown key bits: " + keys);
        }
        if (slot < -1 || slot >= Hotbar.SLOTS) {
            throw new IllegalArgumentException("hotbar slot must be -1 to 8: " + slot);
        }
        this.keys = keys;
        this.yaw = yaw;
        this.pitch = pitch;
        this.slot = slot;
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
                && Float.floatToIntBits(pitch) == Float.floatToIntBits(p.pitch) && slot == p.slot;
    }

    @Override
    public int hashCode() {
        return ((keys * 31 + Float.floatToIntBits(yaw)) * 31 + Float.floatToIntBits(pitch)) * 31 + slot;
    }

    /** Keys as letters: W A S D, J = jump, R = sprint, N = sneak, U = use (right button), C = close the screen. */
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
        return keyString() + "@" + yaw + "/" + pitch + (slot >= 0 ? " slot" + slot : "");
    }
}
