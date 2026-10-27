package io.github.konqasasas.mctas.recorder;

import com.google.common.base.Optional;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.HashMap;
import java.util.Map;

/** Turns the engine's block names back into vanilla block states. */
final class VanillaStates {

    private static final Map<BlockType, IBlockState> CACHE = new HashMap<BlockType, IBlockState>();

    private VanillaStates() {
    }

    static synchronized IBlockState of(BlockType type) {
        IBlockState s = CACHE.get(type);
        if (s == null) {
            s = parse(type.name());
            CACHE.put(type, s);
        }
        return s;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static IBlockState parse(String name) {
        String id = VanillaBlocks.blockId(name);
        Block block = Block.getBlockFromName(id);
        if (block == null) {
            throw new IllegalArgumentException("no vanilla block " + id);
        }
        IBlockState state = block.getDefaultState();
        for (Map.Entry<String, String> e : VanillaBlocks.parseProperties(name).entrySet()) {
            IProperty p = block.getBlockState().getProperty(e.getKey());
            if (p == null) {
                throw new IllegalArgumentException(id + " has no property " + e.getKey());
            }
            Optional v = p.parseValue(e.getValue());
            if (!v.isPresent()) {
                throw new IllegalArgumentException(id + ": bad value " + e);
            }
            state = state.withProperty(p, (Comparable) v.get());
        }
        return state;
    }
}
