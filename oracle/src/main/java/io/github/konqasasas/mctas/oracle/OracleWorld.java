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
    /** Positions changed through {@link #setBlockState}, in order: the blocks the player placed. */
    final List<BlockPos> placedLog = new java.util.ArrayList<BlockPos>();
    /** Block updates scheduled and block events added, for tests that ask whether a block reacts. */
    int scheduled;
    /** The one entity in the world, for {@code World.mayPlace}'s entity check; null until set. */
    Entity player;

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

    /** Replaces one block, for tests that build many small layouts in one world. */
    void set(BlockPos pos, IBlockState state) {
        if (state.getBlock() == Blocks.AIR) {
            blocks.remove(pos.toImmutable());
        } else {
            blocks.put(pos.toImmutable(), state);
        }
    }

    void clear() {
        blocks.clear();
    }

    @Override
    public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos pos) {
        // A placed shulker box always has its tile entity, closed unless someone opens it.
        if (getBlockState(pos).getBlock() instanceof net.minecraft.block.BlockShulkerBox) {
            return new net.minecraft.tileentity.TileEntityShulkerBox();
        }
        return null;
    }

    /**
     * As the client world's {@code setBlockState} for a placed block: the state changes and nothing
     * else happens (the client neither notifies neighbours nor calls {@code onBlockAdded}).
     */
    @Override
    public boolean setBlockState(BlockPos pos, IBlockState newState, int flags) {
        if (pos.getY() < 0 || pos.getY() >= 256) {
            return false;
        }
        BlockPos p = pos.toImmutable();
        set(p, newState);
        if (!placedLog.contains(p)) {
            placedLog.add(p);
        }
        return true;
    }

    @Override
    public void scheduleUpdate(BlockPos pos, net.minecraft.block.Block blockIn, int delay) {
        scheduled++;
    }

    @Override
    public void updateBlockTick(BlockPos pos, net.minecraft.block.Block blockIn, int delay, int priority) {
        scheduled++;
    }

    @Override
    public void scheduleBlockUpdate(BlockPos pos, net.minecraft.block.Block blockIn, int delay, int priority) {
        scheduled++;
    }

    @Override
    public void addBlockEvent(BlockPos pos, net.minecraft.block.Block blockIn, int eventID, int eventParam) {
        scheduled++;
    }

    /** Only the player, and only for callers that do not exclude it ({@code World.checkNoEntityCollision}). */
    @Override
    public List<Entity> getEntitiesWithinAABBExcludingEntity(Entity entityIn, AxisAlignedBB bb) {
        if (player != null && entityIn != player && player.getEntityBoundingBox().intersects(bb)) {
            return Collections.singletonList(player);
        }
        return Collections.emptyList();
    }

    @Override
    public <T extends Entity> List<T> getEntitiesWithinAABB(Class<? extends T> clazz, AxisAlignedBB aabb,
                                                           Predicate<? super T> filter) {
        // Buttons, pressure plates and tripwires look for entities on them; there are none.
        return Collections.emptyList();
    }

    @Override
    public List<Entity> getEntitiesInAABBexcluding(Entity entityIn, AxisAlignedBB boundingBox, Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }
}
