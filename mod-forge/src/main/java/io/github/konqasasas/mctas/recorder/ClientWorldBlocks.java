package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The live client world as a {@link BlockWorld}. Each block state is mapped once to its entry in
 * {@link VanillaBlocks}. The few blocks the catalog leaves out (portals, end gateways, moving pistons)
 * are remembered in {@link #unsupported()} and read as air, so a run that touched them is reported as
 * not comparable.
 */
final class ClientWorldBlocks implements BlockWorld {

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
            t = map(state);
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

    private BlockType map(IBlockState state) {
        String name = catalogName(state);
        BlockType known = name == null ? null : VanillaBlocks.get(name);
        if (known != null) {
            return known;
        }
        unsupported.add(String.valueOf(Block.REGISTRY.getNameForObject(state.getBlock())));
        return BlockType.AIR;
    }
}
