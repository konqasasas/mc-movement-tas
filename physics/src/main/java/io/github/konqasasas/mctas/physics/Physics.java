package io.github.konqasasas.mctas.physics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * One client tick of a survival player in 1.12.2, without rendering, sound or networking.
 *
 * <p>The order of operations follows the client's {@code EntityPlayerSP.onUpdate} call chain:
 * {@code EntityPlayerSP.onLivingUpdate} (movement keys, push out of blocks, sprint),
 * {@code EntityLivingBase.onLivingUpdate} (velocity cutoff, jump, {@code travel}),
 * {@code EntityPlayer.onLivingUpdate} (air acceleration) and the size update at the end of
 * {@code EntityPlayer.onUpdate}. Each step names the method it reproduces.
 *
 * <p>Not modelled yet: liquids, elytra, flying, riding, using an item, other entities and the world
 * border. Worlds that contain only blocks from {@link VanillaBlocks} are fully covered.
 */
public final class Physics {

    /** {@code EntityPlayer} base movement speed, {@code (double) 0.1F}. */
    private static final double BASE_MOVEMENT_SPEED = 0.10000000149011612D;
    private static final float SPEED_IN_AIR = 0.02F;
    private static final float STEP_HEIGHT = 0.6F;

    private static final UUID SPRINT_ID = UUID.fromString("662A6B8D-DA3E-4C1C-8813-96EA6097278D");
    private static final UUID SPEED_ID = UUID.fromString("91AEAA56-376B-4498-935B-2F7F68070635");
    private static final UUID SLOWNESS_ID = UUID.fromString("7107DE5E-7CE8-4030-940E-514C1F160890");
    /**
     * The game multiplies the operation-2 modifiers in {@code HashSet} iteration order. These three
     * ids sit in distinct buckets of a 16-bucket set, so the order is the same however they were
     * added; it is computed here rather than hard-coded.
     */
    private static final UUID[] MULTIPLY_ORDER = iterationOrder(SPRINT_ID, SPEED_ID, SLOWNESS_ID);

    private Physics() {
    }

    /** Returns the state after one tick. {@code state} is not modified. */
    public static PlayerState tick(BlockWorld world, PlayerState state, PlayerInput input) {
        PlayerState s = state.copy();
        step(world, s, input);
        return s;
    }

    /**
     * Advances {@code s} by one tick in place. {@code world} is the base world; blocks the player places
     * go to {@link PlayerState#placed}, never into {@code world}.
     */
    public static void step(BlockWorld base, PlayerState s, PlayerInput input) {
        // The view turns between ticks (Minecraft.runTick -> mouse), so the tick sees the new angles.
        s.rotationYaw = input.yaw;
        s.rotationPitch = input.pitch;

        // Minecraft.runTick: the right-click delay, the hotbar keys and right-clicks, before the player moves.
        Placement.beforeMove(base, s, input);
        BlockWorld world = s.world(base);

        // Entity.onEntityUpdate
        handleWaterMovement(world, s);
        if (isInLava(world, s)) {
            s.fallDistance *= 0.5F;
        }

        // EntityPlayerSP.onLivingUpdate
        if (s.sprintToggleTimer > 0) {
            --s.sprintToggleTimer;
        }
        boolean prevSneak = s.prevSneak;
        boolean prevForwardFull = s.prevMoveForward >= 0.8F;

        // MovementInputFromOptions.updatePlayerMoveState
        float moveStrafe = 0.0F;
        float moveForward = 0.0F;
        if (input.has(PlayerInput.W)) {
            ++moveForward;
        }
        if (input.has(PlayerInput.S)) {
            --moveForward;
        }
        if (input.has(PlayerInput.A)) {
            ++moveStrafe;
        }
        if (input.has(PlayerInput.D)) {
            --moveStrafe;
        }
        boolean jump = input.has(PlayerInput.JUMP);
        boolean sneak = input.has(PlayerInput.SNEAK);
        if (sneak) {
            moveStrafe = (float) ((double) moveStrafe * 0.3D);
            moveForward = (float) ((double) moveForward * 0.3D);
        }
        s.prevMoveForward = moveForward;
        s.prevMoveStrafe = moveStrafe;
        s.prevJump = jump;
        s.prevSneak = sneak;

        Aabb bb = s.box;
        double w35 = (double) s.width * 0.35D;
        pushOutOfBlocks(world, s, s.posX - w35, bb.minY + 0.5D, s.posZ + w35);
        pushOutOfBlocks(world, s, s.posX - w35, bb.minY + 0.5D, s.posZ - w35);
        pushOutOfBlocks(world, s, s.posX + w35, bb.minY + 0.5D, s.posZ - w35);
        pushOutOfBlocks(world, s, s.posX + w35, bb.minY + 0.5D, s.posZ + w35);

        // Food level 20, no item in use, no blindness.
        boolean sprintKey = input.has(PlayerInput.SPRINT);
        if (s.onGround && !prevSneak && !prevForwardFull && moveForward >= 0.8F && !s.sprinting) {
            if (s.sprintToggleTimer <= 0 && !sprintKey) {
                s.sprintToggleTimer = 7;
            } else {
                s.sprinting = true;
            }
        }
        if (!s.sprinting && moveForward >= 0.8F && sprintKey) {
            s.sprinting = true;
        }
        if (s.sprinting && (moveForward < 0.8F || s.collidedHorizontally)) {
            s.sprinting = false;
        }

        // EntityLivingBase.onLivingUpdate
        if (s.jumpTicks > 0) {
            --s.jumpTicks;
        }
        if (Math.abs(s.motionX) < 0.003D) {
            s.motionX = 0.0D;
        }
        if (Math.abs(s.motionY) < 0.003D) {
            s.motionY = 0.0D;
        }
        if (Math.abs(s.motionZ) < 0.003D) {
            s.motionZ = 0.0D;
        }
        // updateEntityActionState copies the movement input.
        if (jump) {
            if (s.inWater || isInLava(world, s)) {
                // handleJumpWater, handleJumpLava
                s.motionY += 0.03999999910593033D;
            } else if (s.onGround && s.jumpTicks == 0) {
                jump(s);
                s.jumpTicks = 10;
            }
        } else {
            s.jumpTicks = 0;
        }
        float strafe = moveStrafe * 0.98F;
        float forward = moveForward * 0.98F;
        travel(world, s, strafe, forward);

        // EntityPlayer.onLivingUpdate, after super.onLivingUpdate
        s.jumpMovementFactor = SPEED_IN_AIR;
        if (s.sprinting) {
            s.jumpMovementFactor = (float) ((double) s.jumpMovementFactor + (double) SPEED_IN_AIR * 0.3D);
        }

        // EntityPlayer.onUpdate, after super.onUpdate
        double cx = McMath.clamp(s.posX, -2.9999999E7D, 2.9999999E7D);
        double cz = McMath.clamp(s.posZ, -2.9999999E7D, 2.9999999E7D);
        if (cx != s.posX || cz != s.posZ) {
            s.setPosition(cx, s.posY, cz);
        }
        updateSize(world, s);
        if (!s.isSneaking() && s.height == 1.65F) {
            // Released sneak under a low ceiling: the client stays short, but the server checks for room
            // at the position it was last sent and may stand its player up, then pull the client back
            // when the taller box hits the ceiling (F-013).
            s.unsupportedAction = true;
        }

        // EntityPlayerSP.onUpdate, after super.onUpdate: what the server is told.
        Placement.afterMove(s);
    }

    /** {@code EntityLivingBase.jump}. */
    private static void jump(PlayerState s) {
        s.motionY = (double) 0.42F;
        if (s.jumpBoostAmplifier >= 0) {
            s.motionY += (double) ((float) (s.jumpBoostAmplifier + 1) * 0.1F);
        }
        if (s.sprinting) {
            float f = s.rotationYaw * 0.017453292F;
            s.motionX -= (double) (McMath.sin(f) * 0.2F);
            s.motionZ += (double) (McMath.cos(f) * 0.2F);
        }
    }

    /** {@code EntityLivingBase.travel}. */
    private static void travel(BlockWorld world, PlayerState s, float strafe, float forward) {
        if (s.inWater) {
            // No depth strider.
            double d0 = s.posY;
            moveRelative(s, strafe, 0.0F, forward, 0.02F);
            move(world, s, s.motionX, s.motionY, s.motionZ);
            s.motionX *= (double) 0.8F;
            s.motionY *= 0.800000011920929D;
            s.motionZ *= (double) 0.8F;
            s.motionY -= 0.02D;
            if (s.collidedHorizontally && isOffsetPositionInLiquid(world, s, s.motionX, s.motionY + 0.6000000238418579D - s.posY + d0, s.motionZ)) {
                s.motionY = 0.30000001192092896D;
            }
        } else if (isInLava(world, s)) {
            double d4 = s.posY;
            moveRelative(s, strafe, 0.0F, forward, 0.02F);
            move(world, s, s.motionX, s.motionY, s.motionZ);
            s.motionX *= 0.5D;
            s.motionY *= 0.5D;
            s.motionZ *= 0.5D;
            s.motionY -= 0.02D;
            if (s.collidedHorizontally && isOffsetPositionInLiquid(world, s, s.motionX, s.motionY + 0.6000000238418579D - s.posY + d4, s.motionZ)) {
                s.motionY = 0.30000001192092896D;
            }
        } else {
            walk(world, s, strafe, forward);
        }
    }

    /** {@code EntityLivingBase.travel}, the walking branch. */
    private static void walk(BlockWorld world, PlayerState s, float strafe, float forward) {
        float f6 = 0.91F;
        if (s.onGround) {
            f6 = blockBelow(world, s).slipperiness() * 0.91F;
        }
        float f7 = 0.16277136F / (f6 * f6 * f6);
        float f8;
        if (s.onGround) {
            f8 = movementSpeed(s) * f7;
        } else {
            f8 = s.jumpMovementFactor;
        }
        moveRelative(s, strafe, 0.0F, forward, f8);
        f6 = 0.91F;
        if (s.onGround) {
            f6 = blockBelow(world, s).slipperiness() * 0.91F;
        }
        if (isOnLadder(world, s)) {
            s.motionX = McMath.clamp(s.motionX, -0.15000000596046448D, 0.15000000596046448D);
            s.motionZ = McMath.clamp(s.motionZ, -0.15000000596046448D, 0.15000000596046448D);
            s.fallDistance = 0.0F;
            if (s.motionY < -0.15D) {
                s.motionY = -0.15D;
            }
            if (s.isSneaking() && s.motionY < 0.0D) {
                s.motionY = 0.0D;
            }
        }
        move(world, s, s.motionX, s.motionY, s.motionZ);
        if (s.collidedHorizontally && isOnLadder(world, s)) {
            s.motionY = 0.2D;
        }
        s.motionY -= 0.08D;
        s.motionY *= 0.9800000190734863D;
        s.motionX *= (double) f6;
        s.motionZ *= (double) f6;
    }

    /** The block at {@code BlockPos(posX, box.minY - 1, posZ)}, which sets ground friction. */
    private static BlockType blockBelow(BlockWorld world, PlayerState s) {
        return world.getBlock(McMath.floor(s.posX), McMath.floor(s.box.minY - 1.0D), McMath.floor(s.posZ));
    }

    /** {@code EntityPlayer.getAIMoveSpeed}: the movement speed attribute as a float. */
    static float movementSpeed(PlayerState s) {
        double d1 = BASE_MOVEMENT_SPEED;
        for (UUID id : MULTIPLY_ORDER) {
            if (id == SPRINT_ID && s.sprinting) {
                d1 *= 1.0D + 0.30000001192092896D;
            } else if (id == SPEED_ID && s.speedAmplifier >= 0) {
                d1 *= 1.0D + 0.20000000298023224D * (double) (s.speedAmplifier + 1);
            } else if (id == SLOWNESS_ID && s.slownessAmplifier >= 0) {
                d1 *= 1.0D + -0.15000000596046448D * (double) (s.slownessAmplifier + 1);
            }
        }
        // RangedAttribute clamp for generic.movementSpeed.
        return (float) McMath.clamp(d1, 0.0D, 1024.0D);
    }

    /** {@code Entity.moveRelative}. */
    private static void moveRelative(PlayerState s, float strafe, float up, float forward, float friction) {
        float f = strafe * strafe + up * up + forward * forward;
        if (f >= 1.0E-4F) {
            f = McMath.sqrt(f);
            if (f < 1.0F) {
                f = 1.0F;
            }
            f = friction / f;
            strafe = strafe * f;
            up = up * f;
            forward = forward * f;
            float f1 = McMath.sin(s.rotationYaw * 0.017453292F);
            float f2 = McMath.cos(s.rotationYaw * 0.017453292F);
            s.motionX += (double) (strafe * f2 - forward * f1);
            s.motionY += (double) up;
            s.motionZ += (double) (forward * f2 + strafe * f1);
        }
    }

    /** {@code Entity.move} with {@code MoverType.SELF}. */
    static void move(BlockWorld world, PlayerState s, double x, double y, double z) {
        if (s.inWeb) {
            s.inWeb = false;
            x *= 0.25D;
            y *= 0.05000000074505806D;
            z *= 0.25D;
            s.motionX = 0.0D;
            s.motionY = 0.0D;
            s.motionZ = 0.0D;
        }
        double d2 = x;
        double d3 = y;
        double d4 = z;

        if (s.onGround && s.isSneaking()) {
            for (; x != 0.0D && collisionBoxes(world, s.box.offset(x, (double) (-STEP_HEIGHT), 0.0D)).isEmpty(); d2 = x) {
                x = sneakStep(x);
            }
            for (; z != 0.0D && collisionBoxes(world, s.box.offset(0.0D, (double) (-STEP_HEIGHT), z)).isEmpty(); d4 = z) {
                z = sneakStep(z);
            }
            for (; x != 0.0D && z != 0.0D && collisionBoxes(world, s.box.offset(x, (double) (-STEP_HEIGHT), z)).isEmpty(); d4 = z) {
                x = sneakStep(x);
                d2 = x;
                z = sneakStep(z);
            }
        }

        List<Aabb> list1 = collisionBoxes(world, s.box.expand(x, y, z));
        Aabb start = s.box;

        if (y != 0.0D) {
            for (int k = 0, l = list1.size(); k < l; ++k) {
                y = list1.get(k).calculateYOffset(s.box, y);
            }
            s.box = s.box.offset(0.0D, y, 0.0D);
        }
        if (x != 0.0D) {
            for (int k = 0, l = list1.size(); k < l; ++k) {
                x = list1.get(k).calculateXOffset(s.box, x);
            }
            if (x != 0.0D) {
                s.box = s.box.offset(x, 0.0D, 0.0D);
            }
        }
        if (z != 0.0D) {
            for (int k = 0, l = list1.size(); k < l; ++k) {
                z = list1.get(k).calculateZOffset(s.box, z);
            }
            if (z != 0.0D) {
                s.box = s.box.offset(0.0D, 0.0D, z);
            }
        }

        boolean flag = s.onGround || d3 != y && d3 < 0.0D;
        if (flag && (d2 != x || d4 != z)) {
            double d14 = x;
            double d6 = y;
            double d7 = z;
            Aabb noStep = s.box;
            s.box = start;
            y = (double) STEP_HEIGHT;
            List<Aabb> list = collisionBoxes(world, s.box.expand(d2, y, d4));

            Aabb bb2 = s.box;
            Aabb bb3 = bb2.expand(d2, 0.0D, d4);
            double d8 = y;
            for (int k = 0, l = list.size(); k < l; ++k) {
                d8 = list.get(k).calculateYOffset(bb3, d8);
            }
            bb2 = bb2.offset(0.0D, d8, 0.0D);
            double d18 = d2;
            for (int k = 0, l = list.size(); k < l; ++k) {
                d18 = list.get(k).calculateXOffset(bb2, d18);
            }
            bb2 = bb2.offset(d18, 0.0D, 0.0D);
            double d19 = d4;
            for (int k = 0, l = list.size(); k < l; ++k) {
                d19 = list.get(k).calculateZOffset(bb2, d19);
            }
            bb2 = bb2.offset(0.0D, 0.0D, d19);

            Aabb bb4 = s.box;
            double d20 = y;
            for (int k = 0, l = list.size(); k < l; ++k) {
                d20 = list.get(k).calculateYOffset(bb4, d20);
            }
            bb4 = bb4.offset(0.0D, d20, 0.0D);
            double d21 = d2;
            for (int k = 0, l = list.size(); k < l; ++k) {
                d21 = list.get(k).calculateXOffset(bb4, d21);
            }
            bb4 = bb4.offset(d21, 0.0D, 0.0D);
            double d22 = d4;
            for (int k = 0, l = list.size(); k < l; ++k) {
                d22 = list.get(k).calculateZOffset(bb4, d22);
            }
            bb4 = bb4.offset(0.0D, 0.0D, d22);

            double d23 = d18 * d18 + d19 * d19;
            double d9 = d21 * d21 + d22 * d22;
            if (d23 > d9) {
                x = d18;
                z = d19;
                y = -d8;
                s.box = bb2;
            } else {
                x = d21;
                z = d22;
                y = -d20;
                s.box = bb4;
            }
            for (int k = 0, l = list.size(); k < l; ++k) {
                y = list.get(k).calculateYOffset(s.box, y);
            }
            s.box = s.box.offset(0.0D, y, 0.0D);

            if (d14 * d14 + d7 * d7 >= x * x + z * z) {
                x = d14;
                y = d6;
                z = d7;
                s.box = noStep;
            }
        }

        // resetPositionToBB
        s.posX = (s.box.minX + s.box.maxX) / 2.0D;
        s.posY = s.box.minY;
        s.posZ = (s.box.minZ + s.box.maxZ) / 2.0D;
        s.collidedHorizontally = d2 != x || d4 != z;
        s.collidedVertically = d3 != y;
        s.onGround = s.collidedVertically && d3 < 0.0D;

        // The block the player stands on: under the feet, or a fence, wall or gate sticking up into them.
        int bx = McMath.floor(s.posX);
        int by = McMath.floor(s.posY - 0.20000000298023224D);
        int bz = McMath.floor(s.posZ);
        BlockType block = world.getBlock(bx, by, bz);
        if (block.isAir()) {
            BlockType below = world.getBlock(bx, by - 1, bz);
            if (below instanceof VanillaBlocks.Fence || below instanceof VanillaBlocks.Wall
                    || below instanceof VanillaBlocks.FenceGate) {
                block = below;
            }
        }

        // EntityLivingBase.updateFallState; Block.onFallenUpon only deals damage, which is not modelled.
        if (!s.inWater) {
            handleWaterMovement(world, s);
        }
        if (s.onGround) {
            s.fallDistance = 0.0F;
        } else if (y < 0.0D) {
            s.fallDistance = (float) ((double) s.fallDistance - y);
        }

        if (d2 != x) {
            s.motionX = 0.0D;
        }
        if (d4 != z) {
            s.motionZ = 0.0D;
        }
        if (d3 != y) {
            block.onLanded(s);
        }
        if (s.onGround && !s.isSneaking()) {
            block.onEntityWalk(s);
        }
        doBlockCollisions(world, s);
    }

    /** {@code Entity.doBlockCollisions}: every block the box overlaps, shrunk by 0.001. */
    private static void doBlockCollisions(BlockWorld world, PlayerState s) {
        Aabb bb = s.box;
        int x0 = McMath.floor(bb.minX + 0.001D);
        int y0 = McMath.floor(bb.minY + 0.001D);
        int z0 = McMath.floor(bb.minZ + 0.001D);
        int x1 = McMath.floor(bb.maxX - 0.001D);
        int y1 = McMath.floor(bb.maxY - 0.001D);
        int z1 = McMath.floor(bb.maxZ - 0.001D);
        for (int i = x0; i <= x1; ++i) {
            for (int j = y0; j <= y1; ++j) {
                for (int k = z0; k <= z1; ++k) {
                    world.getBlock(i, j, k).onEntityCollision(s);
                }
            }
        }
    }

    /** {@code Entity.handleWaterMovement}: sets {@code inWater} and adds the current of the water around the player. */
    private static void handleWaterMovement(BlockWorld world, PlayerState s) {
        Aabb bb = s.box.grow(0.0D, -0.4000000059604645D, 0.0D).grow(-0.001D, -0.001D, -0.001D);
        if (handleWaterAcceleration(world, bb, s)) {
            s.fallDistance = 0.0F;
            s.inWater = true;
        } else {
            s.inWater = false;
        }
    }

    /** {@code World.handleMaterialAcceleration} for {@code Material.WATER}. */
    private static boolean handleWaterAcceleration(BlockWorld world, Aabb bb, PlayerState s) {
        int x0 = McMath.floor(bb.minX);
        int x1 = McMath.ceil(bb.maxX);
        int y0 = McMath.floor(bb.minY);
        int y1 = McMath.ceil(bb.maxY);
        int z0 = McMath.floor(bb.minZ);
        int z1 = McMath.ceil(bb.maxZ);
        boolean flag = false;
        double vx = 0.0D;
        double vy = 0.0D;
        double vz = 0.0D;
        for (int x = x0; x < x1; ++x) {
            for (int y = y0; y < y1; ++y) {
                for (int z = z0; z < z1; ++z) {
                    BlockType t = world.getBlock(x, y, z);
                    if (t instanceof VanillaBlocks.Liquid && !((VanillaBlocks.Liquid) t).lava) {
                        VanillaBlocks.Liquid liquid = (VanillaBlocks.Liquid) t;
                        double d0 = (double) ((float) (y + 1) - VanillaBlocks.Liquid.heightPercent(liquid.level));
                        if ((double) y1 >= d0) {
                            flag = true;
                            double[] f = liquid.flow(world, x, y, z);
                            vx += f[0];
                            vy += f[1];
                            vz += f[2];
                        }
                    }
                }
            }
        }
        if ((double) McMath.sqrt(vx * vx + vy * vy + vz * vz) > 0.0D) {
            double[] v = VanillaBlocks.Liquid.normalize(new double[] {vx, vy, vz});
            s.motionX += v[0] * 0.014D;
            s.motionY += v[1] * 0.014D;
            s.motionZ += v[2] * 0.014D;
        }
        return flag;
    }

    /** {@code Entity.isInLava}. */
    static boolean isInLava(BlockWorld world, PlayerState s) {
        Aabb bb = s.box.grow(-0.10000000149011612D, -0.4000000059604645D, -0.10000000149011612D);
        int x1 = McMath.ceil(bb.maxX);
        int y1 = McMath.ceil(bb.maxY);
        int z1 = McMath.ceil(bb.maxZ);
        for (int x = McMath.floor(bb.minX); x < x1; ++x) {
            for (int y = McMath.floor(bb.minY); y < y1; ++y) {
                for (int z = McMath.floor(bb.minZ); z < z1; ++z) {
                    BlockType t = world.getBlock(x, y, z);
                    if (t instanceof VanillaBlocks.Liquid && ((VanillaBlocks.Liquid) t).lava) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** {@code Entity.isOffsetPositionInLiquid}: despite the name, true when the moved box is free of blocks and liquids. */
    private static boolean isOffsetPositionInLiquid(BlockWorld world, PlayerState s, double x, double y, double z) {
        Aabb bb = s.box.offset(x, y, z);
        return collisionBoxes(world, bb).isEmpty() && !containsAnyLiquid(world, bb);
    }

    /** {@code World.containsAnyLiquid}. */
    private static boolean containsAnyLiquid(BlockWorld world, Aabb bb) {
        int x1 = McMath.ceil(bb.maxX);
        int y1 = McMath.ceil(bb.maxY);
        int z1 = McMath.ceil(bb.maxZ);
        for (int x = McMath.floor(bb.minX); x < x1; ++x) {
            for (int y = McMath.floor(bb.minY); y < y1; ++y) {
                for (int z = McMath.floor(bb.minZ); z < z1; ++z) {
                    if (world.getBlock(x, y, z) instanceof VanillaBlocks.Liquid) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** {@code EntityLivingBase.isOnLadder}: the block at the feet is a ladder, vine or a trapdoor over a ladder. */
    static boolean isOnLadder(BlockWorld world, PlayerState s) {
        int x = McMath.floor(s.posX);
        int y = McMath.floor(s.box.minY);
        int z = McMath.floor(s.posZ);
        return world.getBlock(x, y, z).isLadder(world, x, y, z);
    }

    private static double sneakStep(double v) {
        if (v < 0.05D && v >= -0.05D) {
            return 0.0D;
        } else if (v > 0.0D) {
            return v - 0.05D;
        } else {
            return v + 0.05D;
        }
    }

    /** {@code World.getCollisionBoxes(entity, aabb)} without entities or the world border. */
    static List<Aabb> collisionBoxes(BlockWorld world, Aabb aabb) {
        List<Aabb> out = new ArrayList<Aabb>();
        collect(world, aabb, out, false);
        return out;
    }

    /** {@code World.collidesWithAnyBlock}. */
    static boolean collidesWithAnyBlock(BlockWorld world, Aabb aabb) {
        return collect(world, aabb, new ArrayList<Aabb>(1), true);
    }

    /**
     * The private {@code World.getCollisionBoxes}: scans one block beyond the box, skipping the four
     * corner columns and the top layer of the edge columns, in x, z, y order.
     */
    private static boolean collect(BlockWorld world, Aabb aabb, List<Aabb> out, boolean stopAtFirst) {
        int i = McMath.floor(aabb.minX) - 1;
        int j = McMath.ceil(aabb.maxX) + 1;
        int k = McMath.floor(aabb.minY) - 1;
        int l = McMath.ceil(aabb.maxY) + 1;
        int i1 = McMath.floor(aabb.minZ) - 1;
        int j1 = McMath.ceil(aabb.maxZ) + 1;
        for (int k1 = i; k1 < j; ++k1) {
            for (int l1 = i1; l1 < j1; ++l1) {
                boolean flag2 = k1 == i || k1 == j - 1;
                boolean flag3 = l1 == i1 || l1 == j1 - 1;
                if (flag2 && flag3) {
                    continue;
                }
                for (int i2 = k; i2 < l; ++i2) {
                    if (!flag2 && !flag3 || i2 != l - 1) {
                        if (stopAtFirst && (k1 < -30000000 || k1 >= 30000000 || l1 < -30000000 || l1 >= 30000000)) {
                            return true;
                        }
                        world.getBlock(k1, i2, l1).addCollisionBoxes(world, k1, i2, l1, aabb, out);
                        if (stopAtFirst && !out.isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        return !out.isEmpty();
    }

    /** {@code EntityPlayerSP.pushOutOfBlocks}. */
    private static void pushOutOfBlocks(BlockWorld world, PlayerState s, double x, double y, double z) {
        int bx = McMath.floor(x);
        int by = McMath.floor(y);
        int bz = McMath.floor(z);
        double d0 = x - (double) bx;
        double d1 = z - (double) bz;
        if (!isOpenBlockSpace(world, bx, by, bz)) {
            int i = -1;
            double d2 = 9999.0D;
            if (isOpenBlockSpace(world, bx - 1, by, bz) && d0 < d2) {
                d2 = d0;
                i = 0;
            }
            if (isOpenBlockSpace(world, bx + 1, by, bz) && 1.0D - d0 < d2) {
                d2 = 1.0D - d0;
                i = 1;
            }
            if (isOpenBlockSpace(world, bx, by, bz - 1) && d1 < d2) {
                d2 = d1;
                i = 4;
            }
            if (isOpenBlockSpace(world, bx, by, bz + 1) && 1.0D - d1 < d2) {
                i = 5;
            }
            if (i == 0) {
                s.motionX = -0.10000000149011612D;
            }
            if (i == 1) {
                s.motionX = 0.10000000149011612D;
            }
            if (i == 4) {
                s.motionZ = -0.10000000149011612D;
            }
            if (i == 5) {
                s.motionZ = 0.10000000149011612D;
            }
        }
    }

    private static boolean isOpenBlockSpace(BlockWorld world, int x, int y, int z) {
        return !world.getBlock(x, y, z).isNormalCube() && !world.getBlock(x, y + 1, z).isNormalCube();
    }

    /** {@code EntityPlayer.updateSize} for a player that is neither sleeping nor gliding. */
    private static void updateSize(BlockWorld world, PlayerState s) {
        float f = 0.6F;
        float f1 = s.isSneaking() ? 1.65F : 1.8F;
        if (f != s.width || f1 != s.height) {
            Aabb bb = s.box;
            Aabb resized = new Aabb(bb.minX, bb.minY, bb.minZ, bb.minX + (double) f, bb.minY + (double) f1, bb.minZ + (double) f);
            if (!collidesWithAnyBlock(world, resized)) {
                setSize(world, s, f, f1);
            }
        }
    }

    /** {@code Entity.setSize}. The width never changes for a walking player. */
    private static void setSize(BlockWorld world, PlayerState s, float width, float height) {
        float f = s.width;
        s.width = width;
        s.height = height;
        if (s.width < f) {
            double d0 = (double) width / 2.0D;
            s.box = new Aabb(s.posX - d0, s.posY, s.posZ - d0, s.posX + d0, s.posY + (double) s.height, s.posZ + d0);
            return;
        }
        Aabb bb = s.box;
        s.box = new Aabb(bb.minX, bb.minY, bb.minZ, bb.minX + (double) s.width, bb.minY + (double) s.height, bb.minZ + (double) s.width);
        if (s.width > f) {
            move(world, s, (double) (f - s.width), 0.0D, (double) (f - s.width));
        }
    }

    private static UUID[] iterationOrder(UUID... ids) {
        HashSet<UUID> set = new HashSet<UUID>();
        for (UUID id : ids) {
            set.add(id);
        }
        return set.toArray(new UUID[0]);
    }
}
