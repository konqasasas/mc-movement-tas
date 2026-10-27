package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.BlockWorld;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * The live client world as a {@link BlockWorld}. Each block state is mapped once to the engine's
 * block types. States the engine cannot model yet are remembered in {@link #unsupported()} and
 * read as air, so a run that touched them is reported as not comparable.
 */
final class ClientWorldBlocks implements BlockWorld {

    private static final Aabb FULL = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
    private static final BlockType FULL_NOT_NORMAL =
            new BlockType("full_not_normal_cube", Collections.singletonList(FULL), 0.6F, false, false);
    /** No collision box and no movement effect: grass, flowers, torches, signs, ... */
    private static final BlockType PASSABLE =
            new BlockType("passable", Collections.<Aabb>emptyList(), 0.6F, false, false);

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

    private BlockType map(IBlockState state, BlockPos pos) {
        Block block = state.getBlock();
        if (state.getMaterial() == Material.AIR) {
            return BlockType.AIR;
        }
        if (block == Blocks.ICE) {
            return BlockType.ICE;
        }
        if (block == Blocks.PACKED_ICE) {
            return BlockType.PACKED_ICE;
        }
        AxisAlignedBB box = state.getCollisionBoundingBox(world, pos);
        boolean special = block == Blocks.WEB || block == Blocks.LADDER || block == Blocks.VINE
                || block == Blocks.WATER || block == Blocks.FLOWING_WATER
                || block == Blocks.LAVA || block == Blocks.FLOWING_LAVA
                || block == Blocks.PORTAL || block == Blocks.END_PORTAL
                || block == Blocks.SLIME_BLOCK || block == Blocks.SOUL_SAND;
        if (!special && box == null) {
            return PASSABLE;
        }
        // isFullCube rules out stairs, whose single bounding box is full but whose collision is not.
        if (!special && state.isFullCube() && Block.FULL_BLOCK_AABB.equals(box) && block.slipperiness == 0.6F) {
            return state.isNormalCube() ? BlockType.STONE : FULL_NOT_NORMAL;
        }
        unsupported.add(String.valueOf(Block.REGISTRY.getNameForObject(block)));
        return BlockType.AIR;
    }
}
