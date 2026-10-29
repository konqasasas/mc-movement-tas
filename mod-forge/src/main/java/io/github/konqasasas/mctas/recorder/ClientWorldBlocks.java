package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The client world as a {@link BlockWorld}: live, or after {@link #freeze} as it was then, so the
 * blocks the player places during a run come only from the engine's own {@code PlayerState.placed}. Each block state is mapped once to its entry in
 * {@link VanillaBlocks}. The few blocks the catalog leaves out (portals, end gateways, moving pistons)
 * are remembered in {@link #unsupported()} and read as air, so a run that touched them is reported as
 * not comparable.
 */
final class ClientWorldBlocks implements BlockWorld {

    private final World world;
    private final Map<IBlockState, BlockType> cache = new HashMap<IBlockState, BlockType>();
    private final TreeSet<String> unsupported = new TreeSet<String>();
    /** The state each position had at {@link #freeze}, for the positions changed since. */
    private final Map<BlockPos, IBlockState> before = new HashMap<BlockPos, IBlockState>();
    private IWorldEventListener listener;
    /** Every change seen since {@link #freeze}, as "x y z: old -> new", until {@link #drainChanges}. */
    private final List<String> changes = new java.util.ArrayList<String>();

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
        IBlockState state = before.get(pos);
        if (state == null) {
            state = world.getBlockState(pos);
        }
        if (state.getBlock() instanceof BlockDoor) {
            // Each half stores only half of the door; the client gets the rest of the state from the
            // server as whatever Block.BLOCK_STATE_IDS holds for that metadata.
            state = state.getActualState(world, pos);
        }
        BlockType t = cache.get(state);
        if (t == null) {
            t = map(state);
            cache.put(state, t);
        }
        return t;
    }

    /** From now until {@link #unfreeze}, reads every block as it is now. */
    void freeze() {
        if (listener != null) {
            return;
        }
        listener = new Changes();
        world.addEventListener(listener);
    }

    void unfreeze() {
        if (listener != null) {
            world.removeEventListener(listener);
            listener = null;
        }
        before.clear();
    }

    /** The changes seen since the last call, oldest first. */
    List<String> drainChanges() {
        List<String> out = new java.util.ArrayList<String>(changes);
        changes.clear();
        return out;
    }

    private static String describe(IBlockState s) {
        String name = catalogName(s);
        return name == null ? String.valueOf(Block.REGISTRY.getNameForObject(s.getBlock())) : name.replace("minecraft:", "");
    }

    /** Records the first old state of every block that changes. */
    private final class Changes implements IWorldEventListener {
        @Override
        public void notifyBlockUpdate(World w, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
            if (!before.containsKey(pos)) {
                before.put(pos.toImmutable(), oldState);
            }
            if (oldState != newState) {
                changes.add(pos.getX() + " " + pos.getY() + " " + pos.getZ() + ": " + describe(oldState) + " -> "
                        + describe(newState));
            }
        }

        @Override public void notifyLightSet(BlockPos pos) { }
        @Override public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) { }
        @Override public void playSoundToAllNearExcept(EntityPlayer player, SoundEvent sound, SoundCategory category,
                                                      double x, double y, double z, float volume, float pitch) { }
        @Override public void playRecord(SoundEvent sound, BlockPos pos) { }
        @Override public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z,
                                            double xSpeed, double ySpeed, double zSpeed, int... parameters) { }
        @Override public void spawnParticle(int id, boolean ignoreRange, boolean minimiseParticleLevel, double x,
                                            double y, double z, double xSpeed, double ySpeed, double zSpeed,
                                            int... parameters) { }
        @Override public void onEntityAdded(Entity entity) { }
        @Override public void onEntityRemoved(Entity entity) { }
        @Override public void broadcastSound(int soundID, BlockPos pos, int data) { }
        @Override public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) { }
        @Override public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) { }
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
