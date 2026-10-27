package io.github.konqasasas.mctas.physics;

/**
 * Everything about the player that can change the outcome of a later tick, including the hidden
 * fields the game keeps between ticks. Two states with equal fields evolve identically under the
 * same inputs.
 *
 * <p>Field names follow the MCP names of 1.12.2 so the code can be checked against the game. The
 * position and the bounding box are stored separately because the game does too: the box is the
 * source of truth during a move, and the position is copied back from it, but a size change (sneak)
 * rebuilds the box without touching the position.
 */
public final class PlayerState implements Cloneable {

    public double posX;
    public double posY;
    public double posZ;
    public Aabb box;
    public double motionX;
    public double motionY;
    public double motionZ;
    public float rotationYaw;
    public float rotationPitch;

    public boolean onGround;
    public boolean collidedHorizontally;
    public boolean collidedVertically;
    public float fallDistance;
    public float width = 0.6F;
    public float height = 1.8F;

    public boolean sprinting;
    public int jumpTicks;
    /** Air acceleration. Updated after the move each tick, so it lags the sprint flag by one tick. */
    public float jumpMovementFactor = 0.02F;

    /** {@code EntityPlayerSP.sprintToggleTimer}: the double-tap-W window. */
    public int sprintToggleTimer;
    /** Last tick's {@code MovementInput} values, read before this tick's keys are applied. */
    public float prevMoveForward;
    public float prevMoveStrafe;
    public boolean prevJump;
    public boolean prevSneak;

    /** Speed effect amplifier, or -1 for none. */
    public int speedAmplifier = -1;
    /** Slowness effect amplifier, or -1 for none. */
    public int slownessAmplifier = -1;
    /** Jump boost effect amplifier, or -1 for none. */
    public int jumpBoostAmplifier = -1;

    public PlayerState() {
    }

    /**
     * A player standing still on the ground with feet at (x, y, z), the way the game leaves a player
     * that has stood there for at least one tick: vertical velocity is one tick of gravity.
     */
    public static PlayerState standing(double x, double y, double z, float yaw) {
        PlayerState s = new PlayerState();
        s.setPosition(x, y, z);
        s.motionY = -0.0784000015258789D;
        s.rotationYaw = yaw;
        s.onGround = true;
        s.collidedVertically = true;
        return s;
    }

    /** {@code Entity.setPosition}: moves the player and rebuilds the box around the new position. */
    public void setPosition(double x, double y, double z) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        float f = this.width / 2.0F;
        float f1 = this.height;
        this.box = new Aabb(x - (double) f, y, z - (double) f, x + (double) f, y + (double) f1, z + (double) f);
    }

    public boolean isSneaking() {
        return prevSneak;
    }

    public PlayerState copy() {
        try {
            return (PlayerState) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /** Bitwise equality of every field. */
    public boolean sameAs(PlayerState o) {
        return firstDifference(o) == null;
    }

    /** The name of the first field that differs bitwise, or null when the states are identical. */
    public String firstDifference(PlayerState o) {
        if (!same(posX, o.posX)) return "posX";
        if (!same(posY, o.posY)) return "posY";
        if (!same(posZ, o.posZ)) return "posZ";
        if (!box.equals(o.box)) return "box";
        if (!same(motionX, o.motionX)) return "motionX";
        if (!same(motionY, o.motionY)) return "motionY";
        if (!same(motionZ, o.motionZ)) return "motionZ";
        if (!same(rotationYaw, o.rotationYaw)) return "rotationYaw";
        if (!same(rotationPitch, o.rotationPitch)) return "rotationPitch";
        if (onGround != o.onGround) return "onGround";
        if (collidedHorizontally != o.collidedHorizontally) return "collidedHorizontally";
        if (collidedVertically != o.collidedVertically) return "collidedVertically";
        if (!same(fallDistance, o.fallDistance)) return "fallDistance";
        if (!same(width, o.width)) return "width";
        if (!same(height, o.height)) return "height";
        if (sprinting != o.sprinting) return "sprinting";
        if (jumpTicks != o.jumpTicks) return "jumpTicks";
        if (!same(jumpMovementFactor, o.jumpMovementFactor)) return "jumpMovementFactor";
        if (sprintToggleTimer != o.sprintToggleTimer) return "sprintToggleTimer";
        if (!same(prevMoveForward, o.prevMoveForward)) return "prevMoveForward";
        if (!same(prevMoveStrafe, o.prevMoveStrafe)) return "prevMoveStrafe";
        if (prevJump != o.prevJump) return "prevJump";
        if (prevSneak != o.prevSneak) return "prevSneak";
        if (speedAmplifier != o.speedAmplifier) return "speedAmplifier";
        if (slownessAmplifier != o.slownessAmplifier) return "slownessAmplifier";
        if (jumpBoostAmplifier != o.jumpBoostAmplifier) return "jumpBoostAmplifier";
        return null;
    }

    private static boolean same(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    private static boolean same(float a, float b) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b);
    }

    @Override
    public String toString() {
        return "pos=(" + posX + ", " + posY + ", " + posZ + ") motion=(" + motionX + ", " + motionY + ", " + motionZ
                + ") yaw=" + rotationYaw + " ground=" + onGround + " sprint=" + sprinting + " sneak=" + prevSneak
                + " jumpTicks=" + jumpTicks + " " + box;
    }
}
