package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MovementInput;
import net.minecraft.util.math.AxisAlignedBB;

import java.lang.reflect.Field;

/** Reads the real client player into a {@link PlayerState}, and the input it just used. */
public final class PlayerSnapshot {

    // Private fields, by MCP name in a development run and by SRG name in a normal game.
    private static final Field JUMP_TICKS = field(EntityLivingBase.class, "jumpTicks", "field_70773_bE");
    private static final Field SPRINT_TOGGLE_TIMER = field(EntityPlayerSP.class, "sprintToggleTimer", "field_71156_d");
    private static final Field IN_WEB = field(net.minecraft.entity.Entity.class, "isInWeb", "field_70134_J");
    private static final Field IN_WATER = field(net.minecraft.entity.Entity.class, "inWater", "field_70171_ac");

    private PlayerSnapshot() {
    }

    private static Field field(Class<?> c, String... names) {
        for (String n : names) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the next name
            }
        }
        throw new IllegalStateException("no field " + names[0] + " in " + c.getName());
    }

    private static int getInt(Field f, Object o) {
        try {
            return f.getInt(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean getBoolean(Field f, Object o) {
        try {
            return f.getBoolean(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PlayerState read(EntityPlayerSP p) {
        PlayerState s = new PlayerState();
        s.width = p.width;
        s.height = p.height;
        s.posX = p.posX;
        s.posY = p.posY;
        s.posZ = p.posZ;
        AxisAlignedBB b = p.getEntityBoundingBox();
        s.box = new Aabb(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        s.motionX = p.motionX;
        s.motionY = p.motionY;
        s.motionZ = p.motionZ;
        s.rotationYaw = p.rotationYaw;
        s.rotationPitch = p.rotationPitch;
        s.onGround = p.onGround;
        s.collidedHorizontally = p.collidedHorizontally;
        s.collidedVertically = p.collidedVertically;
        s.fallDistance = p.fallDistance;
        s.inWeb = getBoolean(IN_WEB, p);
        s.inWater = getBoolean(IN_WATER, p);
        s.sprinting = p.isSprinting();
        s.jumpTicks = getInt(JUMP_TICKS, p);
        s.jumpMovementFactor = p.jumpMovementFactor;
        s.sprintToggleTimer = getInt(SPRINT_TOGGLE_TIMER, p);
        MovementInput mi = p.movementInput;
        s.prevMoveForward = mi.moveForward;
        s.prevMoveStrafe = mi.moveStrafe;
        s.prevJump = mi.jump;
        s.prevSneak = mi.sneak;
        s.speedAmplifier = amplifier(p, MobEffects.SPEED);
        s.slownessAmplifier = amplifier(p, MobEffects.SLOWNESS);
        s.jumpBoostAmplifier = amplifier(p, MobEffects.JUMP_BOOST);
        return s;
    }

    private static int amplifier(EntityPlayerSP p, Potion potion) {
        PotionEffect e = p.getActivePotionEffect(potion);
        return e == null ? -1 : e.getAmplifier();
    }

    /**
     * The input the player used in the tick that just ran, read back from its movement input and
     * the sprint key. For recording a person's own play.
     */
    public static PlayerInput lastInput(EntityPlayerSP p) {
        MovementInput mi = p.movementInput;
        int keys = 0;
        if (mi.forwardKeyDown) keys |= PlayerInput.W;
        if (mi.backKeyDown) keys |= PlayerInput.S;
        if (mi.leftKeyDown) keys |= PlayerInput.A;
        if (mi.rightKeyDown) keys |= PlayerInput.D;
        if (mi.jump) keys |= PlayerInput.JUMP;
        if (mi.sneak) keys |= PlayerInput.SNEAK;
        if (Minecraft.getMinecraft().gameSettings.keyBindSprint.isKeyDown()) keys |= PlayerInput.SPRINT;
        return new PlayerInput(keys, p.rotationYaw, p.rotationPitch);
    }
}
