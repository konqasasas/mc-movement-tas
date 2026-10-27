package io.github.konqasasas.mctas.problem.provisional;

import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.world.BlockWorld;

import java.util.ArrayList;
import java.util.List;

public final class ReferenceStepper {
    private static final float STEP_HEIGHT = 0.6F;
    private static final double BASE_MOVE_SPEED = 0.10000000149011612D;
    private static final double SPRINT_BOOST = 0.30000001192092896D;
    private static final float SPEED_IN_AIR = 0.02F;
    private static final float[] SIN_TABLE = new float[65536];

    static {
        for (int i = 0; i < 65536; ++i) {
            SIN_TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0D / 65536.0D);
        }
    }

    private final BlockWorld world;

    public ReferenceStepper(BlockWorld world) {
        this.world = world;
    }

    public PlayerState tick(PlayerState prev, TickInput in) {
        PlayerState s = prev.copy();
        s.rotationYaw = in.yaw;
        s.rotationPitch = in.pitch;
        onLivingUpdate(s, in);
        updateSize(s, in.has(Key.SNEAK));
        return s;
    }

    private void onLivingUpdate(PlayerState s, TickInput in) {
        if (s.sprintToggleTimer > 0) --s.sprintToggleTimer;

        boolean prevSneak = s.lastSneak;
        boolean prevForwardFast = s.lastMoveForward >= 0.8F;

        float moveStrafe = 0.0F;
        float moveForward = 0.0F;
        if (in.has(Key.W)) ++moveForward;
        if (in.has(Key.S)) --moveForward;
        if (in.has(Key.A)) ++moveStrafe;
        if (in.has(Key.D)) --moveStrafe;
        boolean jump = in.has(Key.JUMP);
        boolean sneak = in.has(Key.SNEAK);
        if (sneak) {
            moveStrafe = (float) ((double) moveStrafe * 0.3D);
            moveForward = (float) ((double) moveForward * 0.3D);
        }
        s.lastMoveForward = moveForward;
        s.lastSneak = sneak;
        boolean sprintKey = in.has(Key.SPRINT);

        if (s.onGround && !prevSneak && !prevForwardFast && moveForward >= 0.8F && !s.sprinting) {
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

        if (s.jumpTicks > 0) --s.jumpTicks;
        if (Math.abs(s.motionX) < 0.003D) s.motionX = 0.0D;
        if (Math.abs(s.motionY) < 0.003D) s.motionY = 0.0D;
        if (Math.abs(s.motionZ) < 0.003D) s.motionZ = 0.0D;

        if (jump) {
            if (s.onGround && s.jumpTicks == 0) {
                jump(s);
                s.jumpTicks = 10;
            }
        } else {
            s.jumpTicks = 0;
        }

        moveStrafe *= 0.98F;
        moveForward *= 0.98F;
        travel(s, moveStrafe, moveForward, sneak);

        s.jumpMovementFactor = SPEED_IN_AIR;
        if (s.sprinting) {
            s.jumpMovementFactor = (float) ((double) s.jumpMovementFactor + (double) SPEED_IN_AIR * 0.3D);
        }
    }

    private static void jump(PlayerState s) {
        s.motionY = (double) 0.42F;
        if (s.sprinting) {
            float f = s.rotationYaw * 0.017453292F;
            s.motionX -= (double) (sin(f) * 0.2F);
            s.motionZ += (double) (cos(f) * 0.2F);
        }
    }

    private void travel(PlayerState s, float strafe, float forward, boolean sneak) {
        float f6 = 0.91F;
        if (s.onGround) f6 = slipperinessBelow(s) * 0.91F;
        float f7 = 0.16277136F / (f6 * f6 * f6);
        float f8 = s.onGround ? aiMoveSpeed(s) * f7 : s.jumpMovementFactor;
        moveRelative(s, strafe, 0.0F, forward, f8);
        f6 = 0.91F;
        if (s.onGround) f6 = slipperinessBelow(s) * 0.91F;
        move(s, s.motionX, s.motionY, s.motionZ, sneak);
        s.motionY -= 0.08D;
        s.motionY *= 0.9800000190734863D;
        s.motionX *= (double) f6;
        s.motionZ *= (double) f6;
    }

    private static float aiMoveSpeed(PlayerState s) {
        double d1 = BASE_MOVE_SPEED;
        if (s.sprinting) d1 *= 1.0D + SPRINT_BOOST;
        return (float) d1;
    }

    private float slipperinessBelow(PlayerState s) {
        int x = floor(s.posX);
        int y = floor(s.box.minY - 1.0D);
        int z = floor(s.posZ);
        return world.get(x, y, z).slipperiness;
    }

    private static void moveRelative(PlayerState s, float strafe, float up, float forward, float friction) {
        float f = strafe * strafe + up * up + forward * forward;
        if (f >= 1.0E-4F) {
            f = (float) Math.sqrt((double) f);
            if (f < 1.0F) f = 1.0F;
            f = friction / f;
            strafe = strafe * f;
            up = up * f;
            forward = forward * f;
            float f1 = sin(s.rotationYaw * 0.017453292F);
            float f2 = cos(s.rotationYaw * 0.017453292F);
            s.motionX += (double) (strafe * f2 - forward * f1);
            s.motionY += (double) up;
            s.motionZ += (double) (forward * f2 + strafe * f1);
        }
    }

    private void move(PlayerState s, double x, double y, double z, boolean sneaking) {
        double d2 = x;
        double d3 = y;
        double d4 = z;

        if (s.onGround && sneaking) {
            double step = -(double) STEP_HEIGHT;
            for (; x != 0.0D && collisionBoxes(s.box.offset(x, step, 0.0D)).isEmpty(); d2 = x) {
                if (x < 0.05D && x >= -0.05D) x = 0.0D;
                else if (x > 0.0D) x -= 0.05D;
                else x += 0.05D;
            }
            for (; z != 0.0D && collisionBoxes(s.box.offset(0.0D, step, z)).isEmpty(); d4 = z) {
                if (z < 0.05D && z >= -0.05D) z = 0.0D;
                else if (z > 0.0D) z -= 0.05D;
                else z += 0.05D;
            }
            for (; x != 0.0D && z != 0.0D && collisionBoxes(s.box.offset(x, step, z)).isEmpty(); d4 = z) {
                if (x < 0.05D && x >= -0.05D) x = 0.0D;
                else if (x > 0.0D) x -= 0.05D;
                else x += 0.05D;
                d2 = x;
                if (z < 0.05D && z >= -0.05D) z = 0.0D;
                else if (z > 0.0D) z -= 0.05D;
                else z += 0.05D;
            }
        }

        List<Box> list1 = collisionBoxes(s.box.expand(x, y, z));
        Box original = s.box;

        if (y != 0.0D) {
            for (Box b : list1) y = b.calculateYOffset(s.box, y);
            s.box = s.box.offset(0.0D, y, 0.0D);
        }
        if (x != 0.0D) {
            for (Box b : list1) x = b.calculateXOffset(s.box, x);
            if (x != 0.0D) s.box = s.box.offset(x, 0.0D, 0.0D);
        }
        if (z != 0.0D) {
            for (Box b : list1) z = b.calculateZOffset(s.box, z);
            if (z != 0.0D) s.box = s.box.offset(0.0D, 0.0D, z);
        }

        boolean canStep = s.onGround || d3 != y && d3 < 0.0D;
        if (canStep && (d2 != x || d4 != z)) {
            double d14 = x;
            double d6 = y;
            double d7 = z;
            Box afterPlain = s.box;
            s.box = original;
            y = (double) STEP_HEIGHT;
            List<Box> list = collisionBoxes(s.box.expand(d2, y, d4));

            Box bb2 = s.box;
            Box bb3 = bb2.expand(d2, 0.0D, d4);
            double d8 = y;
            for (Box b : list) d8 = b.calculateYOffset(bb3, d8);
            bb2 = bb2.offset(0.0D, d8, 0.0D);
            double d18 = d2;
            for (Box b : list) d18 = b.calculateXOffset(bb2, d18);
            bb2 = bb2.offset(d18, 0.0D, 0.0D);
            double d19 = d4;
            for (Box b : list) d19 = b.calculateZOffset(bb2, d19);
            bb2 = bb2.offset(0.0D, 0.0D, d19);

            Box bb4 = s.box;
            double d20 = y;
            for (Box b : list) d20 = b.calculateYOffset(bb4, d20);
            bb4 = bb4.offset(0.0D, d20, 0.0D);
            double d21 = d2;
            for (Box b : list) d21 = b.calculateXOffset(bb4, d21);
            bb4 = bb4.offset(d21, 0.0D, 0.0D);
            double d22 = d4;
            for (Box b : list) d22 = b.calculateZOffset(bb4, d22);
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
            for (Box b : list) y = b.calculateYOffset(s.box, y);
            s.box = s.box.offset(0.0D, y, 0.0D);

            if (d14 * d14 + d7 * d7 >= x * x + z * z) {
                x = d14;
                y = d6;
                z = d7;
                s.box = afterPlain;
            }
        }

        s.posX = (s.box.minX + s.box.maxX) / 2.0D;
        s.posY = s.box.minY;
        s.posZ = (s.box.minZ + s.box.maxZ) / 2.0D;
        s.collidedHorizontally = d2 != x || d4 != z;
        s.collidedVertically = d3 != y;
        s.onGround = s.collidedVertically && d3 < 0.0D;

        if (d2 != x) s.motionX = 0.0D;
        if (d4 != z) s.motionZ = 0.0D;
        if (d3 != y) s.motionY = 0.0D;
    }

    private void updateSize(PlayerState s, boolean sneaking) {
        float h = sneaking ? 1.65F : 1.8F;
        if (h != s.height) {
            Box b = s.box;
            Box resized = new Box(b.minX, b.minY, b.minZ, b.minX + (double) s.width, b.minY + (double) h, b.minZ + (double) s.width);
            if (collisionBoxes(resized).isEmpty()) {
                s.height = h;
                s.box = resized;
            }
        }
    }

    private List<Box> collisionBoxes(Box q) {
        List<Box> out = new ArrayList<Box>();
        int x0 = floor(q.minX) - 1, x1 = ceil(q.maxX);
        int y0 = floor(q.minY) - 1, y1 = ceil(q.maxY);
        int z0 = floor(q.minZ) - 1, z1 = ceil(q.maxZ);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                for (int by = y0; by <= y1; by++) {
                    if (!world.isSolid(bx, by, bz)) continue;
                    Box b = new Box(bx, by, bz, bx + 1, by + 1, bz + 1);
                    if (b.intersects(q)) out.add(b);
                }
            }
        }
        return out;
    }

    static float sin(float value) {
        return SIN_TABLE[(int) (value * 10430.378F) & 65535];
    }

    static float cos(float value) {
        return SIN_TABLE[(int) (value * 10430.378F + 16384.0F) & 65535];
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < (double) i ? i - 1 : i;
    }

    private static int ceil(double v) {
        int i = (int) v;
        return v > (double) i ? i + 1 : i;
    }
}
