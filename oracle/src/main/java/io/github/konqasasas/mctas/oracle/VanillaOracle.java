package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Hotbar;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import io.github.konqasasas.mctas.physics.WorldDelta;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.AxisAlignedBB;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs player ticks on the game's own 1.12.2 code, for differential tests of
 * {@link io.github.konqasasas.mctas.physics.Physics}.
 *
 * <p>Needs the 1.12.2 server jar remapped to MCP names on the classpath; see the oracle README.
 */
public final class VanillaOracle {

    private static final Field JUMP_TICKS;
    private static final Field HAND_INVENTORY;

    static {
        Bootstrap.register();
        try {
            JUMP_TICKS = EntityLivingBase.class.getDeclaredField("jumpTicks");
            JUMP_TICKS.setAccessible(true);
            HAND_INVENTORY = EntityLivingBase.class.getDeclaredField("handInventory");
            HAND_INVENTORY.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final OracleWorld world;
    private final OraclePlayer player;

    // Minecraft's own fields around the player: the right-click delay and whether the button was held.
    private int rightClickDelay;
    private boolean prevUse;
    private boolean unsupportedAction;

    public VanillaOracle(SparseBlockWorld blocks) {
        this.world = new OracleWorld(blocks);
        this.player = new OraclePlayer(world);
        this.world.player = player;
    }

    /** Puts the vanilla player into {@code s}. Status effects are not supported yet. */
    public void load(PlayerState s) {
        if (s.speedAmplifier >= 0 || s.slownessAmplifier >= 0 || s.jumpBoostAmplifier >= 0) {
            throw new UnsupportedOperationException("status effects are not wired into the oracle yet");
        }
        OraclePlayer p = player;
        p.width = s.width;
        p.height = s.height;
        p.posX = s.posX;
        p.posY = s.posY;
        p.posZ = s.posZ;
        Aabb b = s.box;
        p.setEntityBoundingBox(new AxisAlignedBB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        p.motionX = s.motionX;
        p.motionY = s.motionY;
        p.motionZ = s.motionZ;
        p.rotationYaw = s.rotationYaw;
        p.rotationPitch = s.rotationPitch;
        p.onGround = s.onGround;
        p.collidedHorizontally = s.collidedHorizontally;
        p.collidedVertically = s.collidedVertically;
        p.fallDistance = s.fallDistance;
        p.setInWebFlag(s.inWeb);
        p.setInWaterFlag(s.inWater);
        p.setSprinting(s.sprinting);
        setJumpTicks(s.jumpTicks);
        p.jumpMovementFactor = s.jumpMovementFactor;
        p.sprintToggleTimer = s.sprintToggleTimer;
        p.miForward = s.prevMoveForward;
        p.miStrafe = s.prevMoveStrafe;
        p.miJump = s.prevJump;
        p.miSneak = s.prevSneak;
        for (WorldDelta e : s.placed.entries()) {
            world.setBlockState(new BlockPos(e.x, e.y, e.z), VanillaStates.of(e.type), 11);
        }
        p.inventory.clear();
        for (int i = 0; i < Hotbar.SLOTS; i++) {
            BlockType item = s.hotbar.item(i);
            if (item != null) {
                IBlockState st = VanillaStates.of(item);
                p.inventory.setInventorySlotContents(i,
                        new ItemStack(st.getBlock(), s.hotbar.count(i), st.getBlock().getMetaFromState(st)));
            }
        }
        p.inventory.currentItem = s.selectedSlot;
        rightClickDelay = s.rightClickDelay;
        prevUse = s.prevUse;
        unsupportedAction = s.unsupportedAction;
    }

    /** The vanilla player's current state. */
    public PlayerState snapshot() {
        OraclePlayer p = player;
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
        s.inWeb = p.inWebFlag();
        s.inWater = p.inWaterFlag();
        s.sprinting = p.isSprinting();
        s.jumpTicks = jumpTicks();
        s.jumpMovementFactor = p.jumpMovementFactor;
        s.sprintToggleTimer = p.sprintToggleTimer;
        s.prevMoveForward = p.miForward;
        s.prevMoveStrafe = p.miStrafe;
        s.prevJump = p.miJump;
        s.prevSneak = p.miSneak;
        WorldDelta placed = WorldDelta.EMPTY;
        for (BlockPos pos : world.placedLog) {
            placed = placed.with(pos.getX(), pos.getY(), pos.getZ(), VanillaBlocks.require(catalogName(world.getBlockState(pos))));
        }
        s.placed = placed;
        Hotbar hotbar = Hotbar.EMPTY;
        for (int i = 0; i < Hotbar.SLOTS; i++) {
            ItemStack stack = p.inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                Block blk = ((ItemBlock) stack.getItem()).getBlock();
                hotbar = hotbar.with(i, VanillaBlocks.require(catalogName(blk.getStateFromMeta(stack.getMetadata()))), stack.getCount());
            }
        }
        s.hotbar = hotbar;
        s.selectedSlot = p.inventory.currentItem;
        s.rightClickDelay = rightClickDelay;
        s.prevUse = prevUse;
        s.unsupportedAction = unsupportedAction;
        return s;
    }

    /** The catalog name of a vanilla state: its id with the properties the catalog keeps. */
    static String catalogName(IBlockState s) {
        String id = String.valueOf(Block.REGISTRY.getNameForObject(s.getBlock()));
        java.util.Map<String, String> props = new java.util.HashMap<String, String>();
        List<String> keys = VanillaBlocks.properties(id);
        for (IProperty<?> prop : s.getPropertyKeys()) {
            if (keys != null && keys.contains(prop.getName())) {
                props.put(prop.getName(), valueName(prop, s));
            }
        }
        return VanillaBlocks.name(id, props);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(IProperty p, IBlockState s) {
        return p.getName(s.getValue(p));
    }

    /**
     * {@code Minecraft.runTick} before the player update: the right-click delay, the hotbar keys and
     * {@code processKeyBinds}' right-clicks.
     */
    private void beforeUpdate(PlayerInput input) {
        if (rightClickDelay > 0) {
            --rightClickDelay;
        }
        if (input.slot >= 0) {
            player.inventory.currentItem = input.slot;
        }
        boolean use = input.has(PlayerInput.USE);
        if (use && !prevUse) {
            rightClickMouse();
        }
        if (use && rightClickDelay == 0) {
            rightClickMouse();
        }
        prevUse = use;
    }

    /**
     * {@code Minecraft.rightClickMouse} and {@code PlayerControllerMP.processRightClickBlock} for a survival
     * player with an empty off hand, on the game's own ray trace, {@code ItemBlock} and {@code World} code.
     * Activating a block is only recorded, as the engine does.
     */
    private void rightClickMouse() {
        rightClickDelay = 4;
        // Entity.rayTrace(4.5, 1.0F), which is client-only.
        Vec3d eye = new Vec3d(player.posX, player.posY + (double) player.getEyeHeight(), player.posZ);
        Vec3d look = player.look();
        double reach = (double) 4.5F;
        RayTraceResult r = world.rayTraceBlocks(eye, eye.add(look.x * reach, look.y * reach, look.z * reach), false, false, true);
        if (r == null || r.typeOfHit != RayTraceResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = r.getBlockPos();
        IBlockState st = world.getBlockState(pos);
        if (st.getMaterial() == Material.AIR) {
            return;
        }
        ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);
        float f = (float) (r.hitVec.x - (double) pos.getX());
        float f1 = (float) (r.hitVec.y - (double) pos.getY());
        float f2 = (float) (r.hitVec.z - (double) pos.getZ());
        if ((!player.isSneaking() || stack.isEmpty()) && InteractionTableWriter.activates(st)) {
            unsupportedAction = true;
            return;
        }
        if (stack.isEmpty()) {
            return;
        }
        ItemBlock item = (ItemBlock) stack.getItem();
        // ItemBlock.canPlaceBlockOnSide, which is client-only.
        BlockPos at = pos;
        EnumFacing side = r.sideHit;
        if (st.getBlock() == Blocks.SNOW_LAYER) {
            side = EnumFacing.UP;
        } else if (!st.getBlock().isReplaceable(world, pos)) {
            at = pos.offset(side);
        }
        if (!world.mayPlace(item.getBlock(), at, false, side, null)) {
            return;
        }
        stack.onItemUse(player, world, pos, EnumHand.MAIN_HAND, r.sideHit, f, f1, f2);
    }

    /** One client tick: the view turns to the input's angles, then the player updates. */
    public PlayerState tick(PlayerInput input) {
        player.input = input;
        player.rotationYaw = input.yaw;
        player.rotationPitch = input.pitch;
        beforeUpdate(input);
        syncEquipment();
        player.onUpdate();
        return snapshot();
    }

    /** Loads {@code start} and returns the state after each input. */
    public List<PlayerState> run(PlayerState start, List<PlayerInput> inputs) {
        load(start);
        List<PlayerState> out = new ArrayList<PlayerState>(inputs.size());
        for (PlayerInput in : inputs) {
            out.add(tick(in));
        }
        return out;
    }

    /**
     * Makes the server-side equipment check in {@code EntityLivingBase.onUpdate} see no change, since it
     * would send the change to other players through a {@code WorldServer} the oracle does not have.
     */
    @SuppressWarnings("unchecked")
    private void syncEquipment() {
        try {
            List<ItemStack> hands = (List<ItemStack>) HAND_INVENTORY.get(player);
            hands.set(0, player.getHeldItemMainhand().copy());
            hands.set(1, player.getHeldItemOffhand().copy());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setJumpTicks(int v) {
        try {
            JUMP_TICKS.setInt(player, v);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private int jumpTicks() {
        try {
            return JUMP_TICKS.getInt(player);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
