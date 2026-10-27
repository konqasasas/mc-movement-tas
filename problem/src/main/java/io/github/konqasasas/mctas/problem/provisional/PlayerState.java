package io.github.konqasasas.mctas.problem.provisional;

import io.github.konqasasas.mctas.problem.Spawn;

public final class PlayerState {
    public static final double STANDING_MOTION_Y = -0.0784000015258789D;

    double posX;
    double posY;
    double posZ;
    Box box;
    double motionX;
    double motionY;
    double motionZ;
    float width = 0.6F;
    float height = 1.8F;
    boolean onGround;
    boolean collidedHorizontally;
    boolean collidedVertically;
    boolean sprinting;
    int jumpTicks;
    float jumpMovementFactor = 0.02F;
    int sprintToggleTimer;
    float lastMoveForward;
    boolean lastSneak;
    float rotationYaw;
    float rotationPitch;

    private PlayerState() {
    }

    public static PlayerState standingAt(Spawn spawn) {
        PlayerState s = new PlayerState();
        s.setPosition(spawn.pos.x, spawn.pos.y, spawn.pos.z);
        s.motionY = STANDING_MOTION_Y;
        s.onGround = true;
        s.collidedVertically = true;
        s.rotationYaw = spawn.yaw;
        s.rotationPitch = spawn.pitch;
        return s;
    }

    void setPosition(double x, double y, double z) {
        posX = x;
        posY = y;
        posZ = z;
        float f = width / 2.0F;
        box = new Box(x - (double) f, y, z - (double) f, x + (double) f, y + (double) height, z + (double) f);
    }

    public PlayerState copy() {
        PlayerState s = new PlayerState();
        s.posX = posX;
        s.posY = posY;
        s.posZ = posZ;
        s.box = box;
        s.motionX = motionX;
        s.motionY = motionY;
        s.motionZ = motionZ;
        s.width = width;
        s.height = height;
        s.onGround = onGround;
        s.collidedHorizontally = collidedHorizontally;
        s.collidedVertically = collidedVertically;
        s.sprinting = sprinting;
        s.jumpTicks = jumpTicks;
        s.jumpMovementFactor = jumpMovementFactor;
        s.sprintToggleTimer = sprintToggleTimer;
        s.lastMoveForward = lastMoveForward;
        s.lastSneak = lastSneak;
        s.rotationYaw = rotationYaw;
        s.rotationPitch = rotationPitch;
        return s;
    }

    public double x() { return posX; }
    public double y() { return posY; }
    public double z() { return posZ; }
    public double motionX() { return motionX; }
    public double motionY() { return motionY; }
    public double motionZ() { return motionZ; }
    public boolean onGround() { return onGround; }
    public boolean sprinting() { return sprinting; }
    public boolean collidedHorizontally() { return collidedHorizontally; }
    public float yaw() { return rotationYaw; }
}
