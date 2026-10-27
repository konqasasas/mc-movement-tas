package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.WorldInfo;

import com.google.common.base.Predicate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A vanilla {@link World} whose blocks come from a {@link SparseBlockWorld}. Every chunk counts as
 * loaded, there are no other entities, and nothing is ever saved.
 */
final class OracleWorld extends World {

    private final Map<BlockPos, IBlockState> blocks = new HashMap<BlockPos, IBlockState>();

    OracleWorld(SparseBlockWorld source) {
        super(null, new WorldInfo(new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.FLAT), "oracle"),
                new WorldProviderSurface(), new Profiler(), false);
        this.provider.setWorld(this);
        source.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                blocks.put(new BlockPos(x, y, z), vanilla(type));
            }
        });
    }

    static IBlockState vanilla(BlockType type) {
        return VanillaStates.of(type);
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        return null;
    }

    @Override
    protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
        return true;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        // World.isOutsideBuildHeight is private.
        if (pos.getY() < 0 || pos.getY() >= 256) {
            return Blocks.AIR.getDefaultState();
        }
        IBlockState state = blocks.get(pos.toImmutable());
        return state == null ? Blocks.AIR.getDefaultState() : state;
    }

    @Override
    public List<Entity> getEntitiesWithinAABBExcludingEntity(Entity entityIn, AxisAlignedBB bb) {
        return Collections.emptyList();
    }

    @Override
    public List<Entity> getEntitiesInAABBexcluding(Entity entityIn, AxisAlignedBB boundingBox, Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }
}
