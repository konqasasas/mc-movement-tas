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
    /** {@code Entity.isInWeb}: set by touching a web, slows the next move and is cleared by it. */
    public boolean inWeb;
    /** {@code Entity.inWater}: set by {@code handleWaterMovement}, picks the swimming branch of travel and the swim jump. */
    public boolean inWater;
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

    /**
     * Blocks this player placed, on top of the world passed to {@link Physics#tick}. Immutable and
     * shared between copies; {@link #world} gives the combined view.
     */
    public WorldDelta placed = WorldDelta.EMPTY;
    /** The hotbar. Immutable and shared between copies. */
    public Hotbar hotbar = Hotbar.EMPTY;
    /** {@code InventoryPlayer.currentItem}. */
    public int selectedSlot;
    /** {@code Minecraft.rightClickDelayTimer}: ticks until holding the right button clicks again. */
    public int rightClickDelay;
    /** The right button was held last tick, so holding it now is not a new press. */
    public boolean prevUse;
    /**
     * Set for good once the player did something the engine does not model, such as right-clicking a
     * door or a chest, looking through a block whose selection box is not modelled, placing a block
     * the server may refuse ({@link Placement}), or letting go of sneak under a ceiling too low to stand
     * up. Later ticks
     * are not to be trusted; a search should drop such a state.
     */
    public boolean unsupportedAction;

    /**
     * {@code EntityPlayerSP.lastReportedPosX/Y/Z}: the position last sent to the server, Y being the box's
     * minY. The server rebuilds its copy of the player's box from it ({@code Entity.setPosition}), and
     * that box, not the client's, decides whether a block may be placed.
     */
    public double lastReportedPosX;
    public double lastReportedPosY;
    public double lastReportedPosZ;
    /** {@code EntityPlayerSP.positionUpdateTicks}: ticks since the position was last sent; it is sent again at 20. */
    public int positionUpdateTicks;
    /** {@code EntityPlayerSP.serverSneakState}: the sneak state last sent to the server. */
    public boolean serverSneakState;
    /**
     * Ticks since {@link #serverSneakState} last changed, up to {@link Placement#SNEAK_SETTLE}. Until then
     * the server may not have resized its player yet.
     */
    public int sneakReportedTicks = Placement.SNEAK_SETTLE;

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
        s.reportPosition();
        return s;
    }

    /**
     * Marks the current position as the one the server has, with the periodic resend just done: how a
     * player that has stood still for a while starts.
     */
    public void reportPosition() {
        this.lastReportedPosX = this.posX;
        this.lastReportedPosY = this.box.minY;
        this.lastReportedPosZ = this.posZ;
        this.positionUpdateTicks = 0;
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

    /** The world as this player sees it: {@code base} with {@link #placed} on top. */
    public BlockWorld world(BlockWorld base) {
        return placed.over(base);
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
        if (inWeb != o.inWeb) return "inWeb";
        if (inWater != o.inWater) return "inWater";
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
        if (!hotbar.equals(o.hotbar)) return "hotbar";
        if (selectedSlot != o.selectedSlot) return "selectedSlot";
        if (rightClickDelay != o.rightClickDelay) return "rightClickDelay";
        if (prevUse != o.prevUse) return "prevUse";
        if (unsupportedAction != o.unsupportedAction) return "unsupportedAction";
        // What the server knows matters only for placing blocks, so it is left out once there are none.
        if (hotbar.isEmpty()) return null;
        if (!same(lastReportedPosX, o.lastReportedPosX)) return "lastReportedPosX";
        if (!same(lastReportedPosY, o.lastReportedPosY)) return "lastReportedPosY";
        if (!same(lastReportedPosZ, o.lastReportedPosZ)) return "lastReportedPosZ";
        if (positionUpdateTicks != o.positionUpdateTicks) return "positionUpdateTicks";
        if (serverSneakState != o.serverSneakState) return "serverSneakState";
        if (sneakReportedTicks != o.sneakReportedTicks) return "sneakReportedTicks";
        return null;
    }

    /**
     * Equal when every field matches bitwise and the same blocks were placed (in any order): such
     * states evolve identically. {@link #firstDifference} leaves {@link #placed} out, since a live
     * client shows placed blocks in its world instead.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlayerState)) {
            return false;
        }
        PlayerState p = (PlayerState) o;
        return firstDifference(p) == null && placed.sameBlocks(p.placed);
    }

    @Override
    public int hashCode() {
        long h = contentHash();
        return (int) (h ^ (h >>> 32));
    }

    /** A 64-bit hash of exactly the fields {@link #equals} compares. */
    public long contentHash() {
        long h = 0xcbf29ce484222325L;
        h = mix(h, Double.doubleToLongBits(posX));
        h = mix(h, Double.doubleToLongBits(posY));
        h = mix(h, Double.doubleToLongBits(posZ));
        h = mix(h, Double.doubleToLongBits(box.minX));
        h = mix(h, Double.doubleToLongBits(box.minY));
        h = mix(h, Double.doubleToLongBits(box.minZ));
        h = mix(h, Double.doubleToLongBits(box.maxX));
        h = mix(h, Double.doubleToLongBits(box.maxY));
        h = mix(h, Double.doubleToLongBits(box.maxZ));
        h = mix(h, Double.doubleToLongBits(motionX));
        h = mix(h, Double.doubleToLongBits(motionY));
        h = mix(h, Double.doubleToLongBits(motionZ));
        h = mix(h, Float.floatToIntBits(rotationYaw));
        h = mix(h, Float.floatToIntBits(rotationPitch));
        h = mix(h, (onGround ? 1 : 0) | (collidedHorizontally ? 2 : 0) | (collidedVertically ? 4 : 0)
                | (inWeb ? 8 : 0) | (inWater ? 16 : 0) | (sprinting ? 32 : 0) | (prevJump ? 64 : 0)
                | (prevSneak ? 128 : 0) | (prevUse ? 256 : 0) | (unsupportedAction ? 512 : 0));
        h = mix(h, Float.floatToIntBits(fallDistance));
        h = mix(h, Float.floatToIntBits(width));
        h = mix(h, Float.floatToIntBits(height));
        h = mix(h, jumpTicks);
        h = mix(h, Float.floatToIntBits(jumpMovementFactor));
        h = mix(h, sprintToggleTimer);
        h = mix(h, Float.floatToIntBits(prevMoveForward));
        h = mix(h, Float.floatToIntBits(prevMoveStrafe));
        h = mix(h, speedAmplifier);
        h = mix(h, slownessAmplifier);
        h = mix(h, jumpBoostAmplifier);
        h = mix(h, hotbar.hashCode());
        h = mix(h, selectedSlot);
        h = mix(h, rightClickDelay);
        h = mix(h, placed.contentHash());
        if (!hotbar.isEmpty()) {
            h = mix(h, Double.doubleToLongBits(lastReportedPosX));
            h = mix(h, Double.doubleToLongBits(lastReportedPosY));
            h = mix(h, Double.doubleToLongBits(lastReportedPosZ));
            h = mix(h, positionUpdateTicks | (serverSneakState ? 32 : 0) | sneakReportedTicks << 6);
        }
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v;
        h *= 0x100000001b3L;
        return h ^ (h >>> 29);
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
                + " jumpTicks=" + jumpTicks + " " + box
                + (hotbar == Hotbar.EMPTY ? "" : " hotbar=" + hotbar + " slot=" + selectedSlot)
                + (placed.isEmpty() ? "" : " placed=" + placed.size())
                + (rightClickDelay == 0 ? "" : " useDelay=" + rightClickDelay)
                + (unsupportedAction ? " UNSUPPORTED" : "");
    }
}
