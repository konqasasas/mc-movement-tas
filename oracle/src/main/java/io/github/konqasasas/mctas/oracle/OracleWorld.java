package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
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
    /** Every {@link #setBlockState}, in order, for the server's copy to tell which plate changed what. */
    final List<BlockPos> changes = new java.util.ArrayList<BlockPos>();
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
        if (state != blocks.get(pos.toImmutable())) {
            shulkers.remove(pos.toImmutable());
        }
        if (state.getBlock() == Blocks.AIR) {
            blocks.remove(pos.toImmutable());
        } else {
            blocks.put(pos.toImmutable(), state);
        }
    }

    void clear() {
        blocks.clear();
        shulkers.clear();
    }

    /** The shulker boxes' tile entities, made when first asked for and kept while the block stays. */
    private final Map<BlockPos, net.minecraft.tileentity.TileEntityShulkerBox> shulkers =
            new HashMap<BlockPos, net.minecraft.tileentity.TileEntityShulkerBox>();

    @Override
    public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos pos) {
        // A placed shulker box always has its tile entity, closed unless someone opens it.
        if (getBlockState(pos).getBlock() instanceof net.minecraft.block.BlockShulkerBox) {
            BlockPos p = pos.toImmutable();
            net.minecraft.tileentity.TileEntityShulkerBox te = shulkers.get(p);
            if (te == null) {
                te = new net.minecraft.tileentity.TileEntityShulkerBox();
                te.setWorld(this);
                te.setPos(p);
                shulkers.put(p, te);
            }
            return te;
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
        changes.add(p);
        if (!placedLog.contains(p)) {
            placedLog.add(p);
        }
        return true;
    }

    /**
     * True for the server's copy of the world ({@link OracleServer}): block updates are scheduled for real and
     * the player is found by every entity query.
     */
    boolean server;
    /** {@code WorldInfo.getWorldTotalTime} of the server's copy. */
    long time;
    /** The server's pending block updates: position, block, due time and order, as {@code NextTickListEntry}. */
    final List<Object[]> ticks = new java.util.ArrayList<Object[]>();
    private long tickOrder;

    @Override
    public void scheduleUpdate(BlockPos pos, net.minecraft.block.Block blockIn, int delay) {
        scheduled++;
        if (server) {
            // WorldServer.updateBlockTick: one entry per position and block.
            for (Object[] t : ticks) {
                if (t[0].equals(pos) && t[1] == blockIn) {
                    return;
                }
            }
            ticks.add(new Object[] {pos.toImmutable(), blockIn, time + delay, tickOrder++});
        }
    }

    /** {@code WorldServer.tickUpdates}: the updates due by now, in the order of their time, then of scheduling. */
    void tickUpdates() {
        List<Object[]> due = new java.util.ArrayList<Object[]>();
        for (Object[] t : ticks) {
            if ((Long) t[2] <= time) {
                due.add(t);
            }
        }
        java.util.Collections.sort(due, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                int c = Long.compare((Long) a[2], (Long) b[2]);
                return c != 0 ? c : Long.compare((Long) a[3], (Long) b[3]);
            }
        });
        ticks.removeAll(due);
        for (Object[] t : due) {
            BlockPos pos = (BlockPos) t[0];
            IBlockState state = getBlockState(pos);
            if (state.getMaterial() != net.minecraft.block.material.Material.AIR
                    && net.minecraft.block.Block.isEqualTo(state.getBlock(), (net.minecraft.block.Block) t[1])) {
                state.getBlock().updateTick(this, pos, state, this.rand);
            }
        }
    }

    /** Set when a neighbour notification reaches a block the engine does not model reacting (D-702). */
    boolean reachedUnmodelled;

    private boolean givesPower(BlockPos p) {
        return io.github.konqasasas.mctas.physics.Mechanisms.isOtherSource(
                String.valueOf(net.minecraft.block.Block.REGISTRY.getNameForObject(getBlockState(p).getBlock())));
    }

    @Override
    public void neighborChanged(BlockPos pos, net.minecraft.block.Block blockIn, BlockPos fromPos) {
        if (server) {
            IBlockState st = getBlockState(pos);
            if (givesPower(pos)) {
                // A daylight detector has no neighborChanged; the engine lets it be.
                if (!io.github.konqasasas.mctas.physics.Mechanisms.notifyInert(
                        VanillaBlocks.require(VanillaOracle.catalogName(st)))) {
                    reachedUnmodelled = true;
                    return;
                }
            } else if (st.getBlock() instanceof net.minecraft.block.BlockTrapDoor) {
                // The engine's rule: any other power source the trapdoor's power depends on is unsupported.
                for (net.minecraft.util.EnumFacing f : net.minecraft.util.EnumFacing.values()) {
                    BlockPos n = pos.offset(f);
                    if (getBlockState(n).isNormalCube()) {
                        for (net.minecraft.util.EnumFacing g : net.minecraft.util.EnumFacing.values()) {
                            reachedUnmodelled |= givesPower(n.offset(g));
                        }
                    } else {
                        reachedUnmodelled |= givesPower(n);
                    }
                }
            } else if (!(st.getBlock() instanceof net.minecraft.block.BlockBasePressurePlate)) {
                BlockType t = VanillaBlocks.require(VanillaOracle.catalogName(st));
                if (t.reactsToPower() || t.isExcept(BlockType.UNSTABLE)) {
                    reachedUnmodelled = true;
                    return;
                }
            }
        }
        super.neighborChanged(pos, blockIn, fromPos);
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
        if (!server && entityIn == null && isPressureBox(bb)) {
            // A wooden pressure plate looking for entities: only the server runs plates.
            return Collections.emptyList();
        }
        if (player != null && entityIn != player && player.getEntityBoundingBox().intersects(bb)) {
            return Collections.singletonList(player);
        }
        return Collections.emptyList();
    }

    /** {@code BlockBasePressurePlate.PRESSURE_AABB} at a pressure plate. */
    private boolean isPressureBox(AxisAlignedBB bb) {
        BlockPos pos = new BlockPos(Math.floor(bb.minX), Math.floor(bb.minY), Math.floor(bb.minZ));
        return getBlockState(pos).getBlock() instanceof net.minecraft.block.BlockBasePressurePlate
                && bb.equals(new AxisAlignedBB(0.125D, 0.0D, 0.125D, 0.875D, 0.25D, 0.875D).offset(pos));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Entity> List<T> getEntitiesWithinAABB(Class<? extends T> clazz, AxisAlignedBB aabb,
                                                           Predicate<? super T> filter) {
        // Buttons, pressure plates and tripwires look for entities on them; on the client there are none.
        if (server && player != null && clazz.isInstance(player) && player.getEntityBoundingBox().intersects(aabb)
                && (filter == null || filter.apply((T) player))) {
            return Collections.singletonList((T) player);
        }
        return Collections.emptyList();
    }

    @Override
    public List<Entity> getEntitiesInAABBexcluding(Entity entityIn, AxisAlignedBB boundingBox, Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }
}
