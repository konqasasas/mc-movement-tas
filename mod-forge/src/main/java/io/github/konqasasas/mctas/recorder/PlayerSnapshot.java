package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Hotbar;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
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
    private static final Field RIGHT_CLICK_DELAY = field(Minecraft.class, "rightClickDelayTimer", "field_71467_ac");
    private static final Field LAST_REPORTED_X = field(EntityPlayerSP.class, "lastReportedPosX", "field_175172_bI");
    private static final Field LAST_REPORTED_Y = field(EntityPlayerSP.class, "lastReportedPosY", "field_175166_bJ");
    private static final Field LAST_REPORTED_Z = field(EntityPlayerSP.class, "lastReportedPosZ", "field_175167_bK");
    private static final Field POSITION_UPDATE_TICKS = field(EntityPlayerSP.class, "positionUpdateTicks", "field_175168_bP");
    private static final Field SERVER_SNEAK_STATE = field(EntityPlayerSP.class, "serverSneakState", "field_175170_bN");
    private static final Field SHULKER_PROGRESS = field(net.minecraft.tileentity.TileEntityShulkerBox.class, "progress", "field_190600_j");
    private static final Field SHULKER_PROGRESS_OLD = field(net.minecraft.tileentity.TileEntityShulkerBox.class, "progressOld", "field_190601_k");

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

    private static double getDouble(Field f, Object o) {
        try {
            return f.getDouble(o);
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

    /**
     * The client's side of the shulker box {@code ref} (the engine) opened: its tile entity's lid and
     * whether its screen is open. What the server has yet to send cannot be seen and is taken from
     * {@code ref}; a box the engine did not open is not looked at (an unexpected screen is caught when
     * it opens).
     */
    public static void readShulker(Minecraft mc, PlayerState real, PlayerState ref) {
        io.github.konqasasas.mctas.physics.Shulker e = ref.shulker;
        real.shulker = io.github.konqasasas.mctas.physics.Shulker.NONE;
        if (e.isNone()) {
            return;
        }
        net.minecraft.tileentity.TileEntity te = mc.world.getTileEntity(new net.minecraft.util.math.BlockPos(e.x, e.y, e.z));
        if (!(te instanceof net.minecraft.tileentity.TileEntityShulkerBox)) {
            return;
        }
        net.minecraft.tileentity.TileEntityShulkerBox b = (net.minecraft.tileentity.TileEntityShulkerBox) te;
        try {
            real.shulker = io.github.konqasasas.mctas.physics.Shulker.of(e.x, e.y, e.z, e.block, e.facing,
                    io.github.konqasasas.mctas.physics.Shulker.Status.valueOf(b.getAnimationStatus().name()),
                    SHULKER_PROGRESS.getFloat(b), SHULKER_PROGRESS_OLD.getFloat(b),
                    mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiShulkerBox, e.event);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
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

    /**
     * Fills in the hotbar, the selected slot, the right-click delay and what the client last told the
     * server ({@code sneakReportedTicks} is left as it is). A slot holding something that is
     * not a block the engine places reads as empty, so the run shows a difference there.
     */
    public static void readPlacement(EntityPlayerSP p, PlayerState s) {
        Hotbar h = Hotbar.EMPTY;
        for (int i = 0; i < Hotbar.SLOTS; i++) {
            ItemStack st = p.inventory.getStackInSlot(i);
            if (st.isEmpty() || !(st.getItem() instanceof ItemBlock)) {
                continue;
            }
            Block b = ((ItemBlock) st.getItem()).getBlock();
            String name = ClientWorldBlocks.catalogName(b.getStateFromMeta(st.getMetadata()));
            BlockType t = name == null ? null : VanillaBlocks.get(name);
            if (t != null && t.isPlaceable() && st.getCount() <= 64) {
                h = h.with(i, t, st.getCount());
            }
        }
        s.hotbar = h;
        s.selectedSlot = p.inventory.currentItem;
        s.rightClickDelay = getInt(RIGHT_CLICK_DELAY, Minecraft.getMinecraft());
        s.lastReportedPosX = getDouble(LAST_REPORTED_X, p);
        s.lastReportedPosY = getDouble(LAST_REPORTED_Y, p);
        s.lastReportedPosZ = getDouble(LAST_REPORTED_Z, p);
        s.positionUpdateTicks = getInt(POSITION_UPDATE_TICKS, p);
        s.serverSneakState = getBoolean(SERVER_SNEAK_STATE, p);
    }

    /**
     * True when the server has the player where it stands, not sneaking, and the periodic resend has just
     * happened: the state {@code PlayerState.standing} starts a problem in.
     */
    public static boolean reportedAtRest(EntityPlayerSP p) {
        return getInt(POSITION_UPDATE_TICKS, p) == 0 && !getBoolean(SERVER_SNEAK_STATE, p)
                && getDouble(LAST_REPORTED_X, p) == p.posX && getDouble(LAST_REPORTED_Y, p) == p.getEntityBoundingBox().minY
                && getDouble(LAST_REPORTED_Z, p) == p.posZ;
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
