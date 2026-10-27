package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.FaceShape;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockShulkerBox;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The live client world as a {@link BlockWorld}. Each block state is mapped once: to its entry in
 * {@link VanillaBlocks} when there is one, otherwise to a plain full block or a block without
 * collision when it is one of those. Anything else is remembered in {@link #unsupported()} and read
 * as air, so a run that touched it is reported as not comparable.
 */
final class ClientWorldBlocks implements BlockWorld {

    private static final Aabb FULL = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

    private final World world;
    private final Map<IBlockState, BlockType> cache = new HashMap<IBlockState, BlockType>();
    private final TreeSet<String> unsupported = new TreeSet<String>();

    ClientWorldBlocks(World world) {
        this.world = world;
    }

    TreeSet<String> unsupported() {
        return unsupported;
    }

    @Override
    public BlockType getBlock(int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return BlockType.AIR;
        }
        BlockPos pos = new BlockPos(x, y, z);
        IBlockState state = world.getBlockState(pos);
        BlockType t = cache.get(state);
        if (t == null) {
            t = map(state, pos);
            cache.put(state, t);
        }
        return t;
    }

    /** The catalog name of a state, or null if the block is not in the catalog. */
    static String catalogName(IBlockState state) {
        String id = String.valueOf(Block.REGISTRY.getNameForObject(state.getBlock()));
        List<String> keys = VanillaBlocks.properties(id);
        if (keys == null) {
            return null;
        }
        Map<String, String> props = new HashMap<String, String>();
        for (IProperty<?> p : state.getPropertyKeys()) {
            if (keys.contains(p.getName())) {
                props.put(p.getName(), valueName(p, state));
            }
        }
        return VanillaBlocks.name(id, props);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(IProperty p, IBlockState s) {
        return p.getName(s.getValue(p));
    }

    private BlockType map(IBlockState state, BlockPos pos) {
        String name = catalogName(state);
        BlockType known = name == null ? null : VanillaBlocks.get(name);
        if (known != null) {
            return known;
        }
        Block block = state.getBlock();
        String id = String.valueOf(Block.REGISTRY.getNameForObject(block));
        AxisAlignedBB box = state.getCollisionBoundingBox(world, pos);
        boolean special = block == Blocks.WEB || block == Blocks.LADDER || block == Blocks.VINE
                || block == Blocks.WATER || block == Blocks.FLOWING_WATER
                || block == Blocks.LAVA || block == Blocks.FLOWING_LAVA
                || block == Blocks.PORTAL || block == Blocks.END_PORTAL
                || block == Blocks.SLIME_BLOCK || block == Blocks.SOUL_SAND || block == Blocks.BED
                || block == Blocks.FARMLAND || block == Blocks.CACTUS || block == Blocks.MAGMA
                || block.slipperiness != 0.6F || state.getMaterial() == Material.AIR;
        // Only blocks whose collision is the plain getCollisionBoundingBox can be described by it.
        boolean plainCollision = !overrides(block, "addCollisionBoxToList", "func_185477_a");
        if (!special && plainCollision && box == null) {
            return generic(id + "#passable", Collections.<Aabb>emptyList(), state, pos);
        }
        if (!special && plainCollision && state.isFullCube() && Block.FULL_BLOCK_AABB.equals(box)) {
            return generic(id + "#full", Collections.singletonList(FULL), state, pos);
        }
        unsupported.add(id);
        return BlockType.AIR;
    }

    /** A block with fixed boxes whose faces and flags are read from the game. */
    private BlockType generic(String name, List<Aabb> boxes, IBlockState state, BlockPos pos) {
        FaceShape[] faces = new FaceShape[6];
        for (EnumFacing f : EnumFacing.values()) {
            faces[io.github.konqasasas.mctas.physics.Facing.valueOf(f.name()).ordinal()] =
                    FaceShape.valueOf(state.getBlockFaceShape(world, pos, f).name());
        }
        Block b = state.getBlock();
        boolean leavesLike = b instanceof BlockLeaves || b instanceof BlockShulkerBox || b == Blocks.BEACON
                || b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN || b == Blocks.BARRIER
                || b == Blocks.MELON_BLOCK || b == Blocks.PUMPKIN || b == Blocks.LIT_PUMPKIN
                || b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.PISTON_HEAD || b == Blocks.CAULDRON
                || b == Blocks.ICE;
        int except = (leavesLike ? BlockType.EXCEPT_FENCE_WALL | BlockType.EXCEPT_PANE : 0)
                | (b == Blocks.GLASS || b == Blocks.STAINED_GLASS || b instanceof net.minecraft.block.BlockTrapDoor
                ? BlockType.EXCEPT_FENCE_WALL : 0);
        return new BlockType(name, boxes, b.slipperiness, state.isNormalCube(), false, faces, except);
    }

    /** True if the block's class or a superclass below Block declares the method. */
    private static boolean overrides(Block block, String mcpName, String srgName) {
        for (Class<?> c = block.getClass(); c != null && c != Block.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(mcpName) || m.getName().equals(srgName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
