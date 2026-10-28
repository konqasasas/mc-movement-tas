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
    /** Buttons pressed and maybe still on, kept as the engine keeps them (the server side is not run). */
    private io.github.konqasasas.mctas.physics.Pressed pressed = io.github.konqasasas.mctas.physics.Pressed.NONE;

    // EntityPlayerSP's record of what the server was told, and the server's copy of the player.
    private double lastReportedPosX;
    private double lastReportedPosY;
    private double lastReportedPosZ;
    private int positionUpdateTicks;
    private boolean serverSneakState;
    private int sneakReportedTicks;
    private final OraclePlayer serverPlayer;

    public VanillaOracle(SparseBlockWorld blocks) {
        this.world = new OracleWorld(blocks);
        this.player = new OraclePlayer(world);
        this.serverPlayer = new OraclePlayer(world);
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
        pressed = s.pressed;
        for (int i = 0; i < pressed.size(); i++) {
            BlockType on = pressed.shown(i);
            // A button that may be off already is kept on; rightClickMouse refuses rays that reach it.
            world.set(new BlockPos(pressed.x(i), pressed.y(i), pressed.z(i)),
                    VanillaStates.of(on.name().contains("powered=") ? on : powered(pressed.off(i))));
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
        lastReportedPosX = s.lastReportedPosX;
        lastReportedPosY = s.lastReportedPosY;
        lastReportedPosZ = s.lastReportedPosZ;
        positionUpdateTicks = s.positionUpdateTicks;
        serverSneakState = s.serverSneakState;
        sneakReportedTicks = s.sneakReportedTicks;
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
        s.pressed = pressed;
        s.lastReportedPosX = lastReportedPosX;
        s.lastReportedPosY = lastReportedPosY;
        s.lastReportedPosZ = lastReportedPosZ;
        s.positionUpdateTicks = positionUpdateTicks;
        s.serverSneakState = serverSneakState;
        s.sneakReportedTicks = sneakReportedTicks;
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
        Vec3d end = eye.add(look.x * reach, look.y * reach, look.z * reach);
        if (reachesUncertainButton(eye, end)) {
            unsupportedAction = true;
            return;
        }
        RayTraceResult r = world.rayTraceBlocks(eye, end, false, false, true);
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
            activate(pos, st, stack, r.sideHit, f, f1, f2);
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
        // ItemBlock.onItemUse's own position and check, then the same on the server.
        BlockPos q = st.getBlock().isReplaceable(world, pos) ? pos : pos.offset(r.sideHit);
        if (world.mayPlace(item.getBlock(), q, false, r.sideHit, null) && q.getY() >= 0 && q.getY() < 256
                && !serverPlaces(item.getBlock(), pos, r.sideHit, q)) {
            unsupportedAction = true;
            return;
        }
        stack.onItemUse(player, world, pos, EnumHand.MAIN_HAND, r.sideHit, f, f1, f2);
    }

    /**
     * Whether the view ray reaches a button that the client may or may not show off again: with a full
     * block in its place, the ray stops there.
     */
    private boolean reachesUncertainButton(Vec3d eye, Vec3d end) {
        for (int i = 0; i < pressed.size(); i++) {
            if (pressed.age(i) <= io.github.konqasasas.mctas.physics.Pressed.certainTicks(pressed.off(i))) {
                continue;
            }
            BlockPos at = new BlockPos(pressed.x(i), pressed.y(i), pressed.z(i));
            IBlockState was = world.getBlockState(at);
            world.set(at, Blocks.STONE.getDefaultState());
            RayTraceResult r = world.rayTraceBlocks(eye, end, false, false, true);
            world.set(at, was);
            if (r != null && r.typeOfHit == RayTraceResult.Type.BLOCK && r.getBlockPos().equals(at)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The client's {@code onBlockActivated} on the game's own code for buttons and levers, after the
     * engine's rules for what it does not model: the server must see the same click (not sneaking with a
     * block in hand), and nothing around the block or the one it hangs on may react to power
     * (the interaction table). Any other block is unsupported. Checks that the button's own code changed
     * nothing else.
     */
    private void activate(BlockPos pos, IBlockState st, ItemStack stack, EnumFacing side, float f, float f1, float f2) {
        Block b = st.getBlock();
        if (!(b instanceof net.minecraft.block.BlockButton) && !(b instanceof net.minecraft.block.BlockLever)) {
            unsupportedAction = true;
            return;
        }
        if (!stack.isEmpty() && (serverSneakState || sneakReportedTicks < io.github.konqasasas.mctas.physics.Placement.SNEAK_SETTLE)) {
            unsupportedAction = true;
            return;
        }
        EnumFacing facing = b instanceof net.minecraft.block.BlockLever
                ? st.getValue(net.minecraft.block.BlockLever.FACING).getFacing()
                : st.getValue(net.minecraft.block.BlockButton.FACING);
        BlockPos attached = pos.offset(facing.getOpposite());
        for (EnumFacing n : EnumFacing.values()) {
            for (BlockPos q : new BlockPos[] {pos.offset(n), attached.offset(n)}) {
                if (q.equals(pos)) {
                    continue;
                }
                BlockType t = VanillaBlocks.require(catalogName(world.getBlockState(q)));
                if (t.reactsToPower() || t.isExcept(BlockType.UNSTABLE)) {
                    unsupportedAction = true;
                    return;
                }
            }
        }
        if (b instanceof net.minecraft.block.BlockLever) {
            // BlockLever.onBlockActivated returns at once on the client.
            return;
        }
        boolean wasOn = st.getValue(net.minecraft.block.BlockButton.POWERED);
        int logged = world.placedLog.size();
        int scheduled = world.scheduled;
        // The button's own code, as the client runs it (its notifyNeighbors reach the same blocks).
        boolean took;
        try {
            took = b.onBlockActivated(world, pos, st, player, EnumHand.MAIN_HAND, side, f, f1, f2);
        } catch (RuntimeException e) {
            StringBuilder around = new StringBuilder();
            for (EnumFacing n : EnumFacing.values()) {
                around.append(' ').append(world.getBlockState(pos.offset(n))).append(' ').append(world.getBlockState(attached.offset(n)));
            }
            throw new IllegalStateException("pressing the button at " + pos + " failed; around:" + around, e);
        }
        if (!took) {
            throw new IllegalStateException("a button did not take the click at " + pos);
        }
        if (!wasOn) {
            if (world.placedLog.size() != logged + 1 || world.scheduled != scheduled + 1) {
                StringBuilder changed = new StringBuilder();
                for (BlockPos q : world.placedLog.subList(logged, world.placedLog.size())) {
                    changed.append(' ').append(q).append('=').append(world.getBlockState(q));
                }
                for (EnumFacing n : EnumFacing.values()) {
                    changed.append(" | ").append(world.getBlockState(pos.offset(n))).append(' ').append(world.getBlockState(attached.offset(n)));
                }
                throw new IllegalStateException("pressing the button at " + pos + " changed more than the button:"
                        + changed + ", " + (world.scheduled - scheduled) + " updates scheduled");
            }
            world.placedLog.remove(world.placedLog.size() - 1);
            pressed = pressed.with(pos.getX(), pos.getY(), pos.getZ(), VanillaBlocks.require(catalogName(st)));
        }
    }

    /** The button on. */
    private static BlockType powered(BlockType off) {
        java.util.Map<String, String> props = VanillaBlocks.parseProperties(off.name());
        props.put("powered", "true");
        return VanillaBlocks.require(VanillaBlocks.name(VanillaBlocks.blockId(off.name()), props));
    }

    /** One tick later for the pressed buttons; the ones surely off again turn off. */
    private void agePressed() {
        if (pressed.isEmpty()) {
            return;
        }
        io.github.konqasasas.mctas.physics.Pressed next = pressed.aged();
        for (int i = 0; i < pressed.size(); i++) {
            if (!next.has(pressed.x(i), pressed.y(i), pressed.z(i))) {
                world.set(new BlockPos(pressed.x(i), pressed.y(i), pressed.z(i)), VanillaStates.of(pressed.off(i)));
            }
        }
        pressed = next;
    }

    /**
     * {@code NetHandlerPlayServer.processTryUseItemOnBlock} and the server's {@code ItemBlock.onItemUse}
     * check, on a player put where the client last said it was ({@code Entity.setPosition}); with either
     * height while the server may not have caught up with a sneak change.
     */
    private boolean serverPlaces(Block block, BlockPos pos, EnumFacing facing, BlockPos q) {
        if (!(pos.getY() < 255 || facing != EnumFacing.UP && pos.getY() < 256)) {
            return false;
        }
        float[] heights = sneakReportedTicks < io.github.konqasasas.mctas.physics.Placement.SNEAK_SETTLE
                ? new float[] {1.65F, 1.8F} : new float[] {serverSneakState ? 1.65F : 1.8F};
        try {
            for (float h : heights) {
                serverPlayer.width = 0.6F;
                serverPlayer.height = h;
                serverPlayer.setPosition(lastReportedPosX, lastReportedPosY, lastReportedPosZ);
                if (!(serverPlayer.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) < 64.0D)) {
                    return false;
                }
                world.player = serverPlayer;
                if (!world.mayPlace(block, q, false, facing, null)) {
                    return false;
                }
            }
            return true;
        } finally {
            world.player = player;
        }
    }

    /**
     * {@code EntityPlayerSP.onUpdateWalkingPlayer}, which the server jar does not have: the sneak state
     * and the position the client sends at the end of its tick.
     */
    private void onUpdateWalkingPlayer() {
        boolean sneaking = player.isSneaking();
        if (sneaking != serverSneakState) {
            serverSneakState = sneaking;
            sneakReportedTicks = 0;
        } else if (sneakReportedTicks < io.github.konqasasas.mctas.physics.Placement.SNEAK_SETTLE) {
            ++sneakReportedTicks;
        }
        AxisAlignedBB axisalignedbb = player.getEntityBoundingBox();
        double d0 = player.posX - lastReportedPosX;
        double d1 = axisalignedbb.minY - lastReportedPosY;
        double d2 = player.posZ - lastReportedPosZ;
        ++positionUpdateTicks;
        boolean flag2 = d0 * d0 + d1 * d1 + d2 * d2 > 9.0E-4D || positionUpdateTicks >= 20;
        if (flag2) {
            lastReportedPosX = player.posX;
            lastReportedPosY = axisalignedbb.minY;
            lastReportedPosZ = player.posZ;
            positionUpdateTicks = 0;
        }
    }

    /** One client tick: the view turns to the input's angles, then the player updates. */
    public PlayerState tick(PlayerInput input) {
        player.input = input;
        player.rotationYaw = input.yaw;
        player.rotationPitch = input.pitch;
        beforeUpdate(input);
        syncEquipment();
        player.onUpdate();
        if (!player.isSneaking() && player.height == 1.65F) {
            // Stuck short under a ceiling; the server may stand up where the client cannot.
            unsupportedAction = true;
        }
        onUpdateWalkingPlayer();
        agePressed();
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
