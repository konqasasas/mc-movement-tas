package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Mechanisms;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import io.github.konqasasas.mctas.physics.WorldDelta;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The integrated server's side of pressure plates, on the game's own code: a second copy of the world whose
 * player stands where the client last said ({@code processPlayer}), collides with the blocks there
 * ({@code Entity.doBlockCollisions}, so {@code BlockBasePressurePlate.onEntityCollision}), and whose
 * scheduled updates run in its world phase ({@code updateTick}); the plates notify their neighbours and
 * {@code BlockTrapDoor.neighborChanged} decides. What changes reaches the client's world at the start of the
 * next client tick. The network phase, which the engine only bounds, is checked by the engine's rule.
 */
final class OracleServer {

    private static final double EDGE = 1.0E-6D;

    final OracleWorld world;
    private final OraclePlayer entity;
    private final OracleWorld client;
    private final SparseBlockWorld base;
    /** Every position the server changed, in order. */
    private final Set<BlockPos> touched = new LinkedHashSet<BlockPos>();
    private List<BlockPos> maybe = new ArrayList<BlockPos>();
    double drift;

    OracleServer(SparseBlockWorld blocks, OracleWorld client) {
        this.base = blocks;
        this.client = client;
        this.world = new OracleWorld(blocks);
        this.world.server = true;
        this.entity = new OraclePlayer(world);
        this.world.player = entity;
        this.world.settleDoors();
    }

    void load(PlayerState s) {
        for (WorldDelta e : s.placed.entries()) {
            world.set(new BlockPos(e.x, e.y, e.z), VanillaStates.of(e.type));
        }
        Mechanisms m = s.mech;
        for (int i = 0; i < m.size(); i++) {
            BlockPos p = new BlockPos(m.x(i), m.y(i), m.z(i));
            world.set(p, VanillaStates.of(m.server(i)));
            client.set(p, VanillaStates.of(m.client(i)));
            touched.add(p);
            if (m.due(i) > 0) {
                world.scheduleUpdate(p, world.getBlockState(p).getBlock(), m.due(i));
            }
        }
        int[] mb = m.maybe();
        for (int i = 0; i < mb.length; i += 3) {
            maybe.add(new BlockPos(mb[i], mb[i + 1], mb[i + 2]));
        }
        drift = s.serverDrift;
        world.settleDoors();
    }

    /**
     * The start of a client tick: the client takes in what the server changed. True if a block that arrives
     * has a collision box that overlaps the player's (the engine's rule).
     */
    boolean arrive(AxisAlignedBB playerBox) {
        // Every block first, then the boxes: a door's upper half takes its shape from the lower half.
        List<BlockPos> arrived = new ArrayList<BlockPos>();
        for (BlockPos p : touched) {
            IBlockState sv = world.getBlockState(p);
            IBlockState cl = client.getBlockState(p);
            if (sv != cl) {
                if (!VanillaOracle.catalogName(sv).equals(VanillaOracle.catalogName(cl))) {
                    // Not a door's upper half that only took POWERED, which the engine does not name.
                    arrived.add(p);
                }
                client.set(p, sv);
            }
        }
        boolean hit = false;
        List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
        for (BlockPos p : arrived) {
            IBlockState sv = client.getBlockState(p);
            sv.addCollisionBoxToList(client, p, playerBox, out, null, false);
            if (sv.getBlock() instanceof net.minecraft.block.BlockDoor
                    && sv.getValue(net.minecraft.block.BlockDoor.HALF) == net.minecraft.block.BlockDoor.EnumDoorHalf.LOWER) {
                client.getBlockState(p.up()).addCollisionBoxToList(client, p.up(), playerBox, out, null, false);
            }
            hit |= !out.isEmpty();
        }
        return hit;
    }

    /**
     * The server tick after a client tick. Returns true if the engine's rules call the tick unsupported.
     *
     * @param reported the position last sent, y being the box's minY
     * @param startBox the client's box at the start of the tick, {@code endBox} at its end
     * @param jumped   {@code processPlayer} jumps its player: on the ground before, off it now, and higher
     */
    boolean tick(double rx, double ry, double rz, float width, float height, AxisAlignedBB startBox, AxisAlignedBB endBox,
                 boolean use, boolean jumped, boolean sprinting, boolean onGround, double motionY) {
        boolean unsupported = false;
        if (jumped && sprinting) {
            drift += 0.2001D;
        }
        double hw = (double) width / 2.0D;
        AxisAlignedBB box = new AxisAlignedBB(rx - hw, ry, rz - hw, rx + hw, ry + (double) height, rz + hw);
        entity.width = width;
        entity.height = height;
        entity.setEntityBoundingBox(box);

        // Packets: the player at the box collides with the blocks there.
        for (BlockPos p : cells(box)) {
            if (plateOff(p) && onEdge(box, p)) {
                unsupported = true;
            }
        }
        world.changes.clear();
        world.reachedUnmodelled = false;
        entity.collideWithBlocks();
        Map<BlockPos, List<BlockPos>> pressedNow = new LinkedHashMap<BlockPos, List<BlockPos>>();
        List<BlockPos> current = null;
        for (BlockPos c : world.changes) {
            if (world.getBlockState(c).getBlock() instanceof BlockBasePressurePlate) {
                current = new ArrayList<BlockPos>();
                pressedNow.put(c, current);
            }
            current.add(c);
            touched.add(c);
        }
        AxisAlignedBB swept = startBox.union(endBox);
        for (BlockPos m : maybe) {
            List<BlockPos> changed = pressedNow.get(m);
            if (changed == null) {
                unsupported = true;
                continue;
            }
            if (use) {
                unsupported = true;
            }
            for (BlockPos c : changed) {
                IBlockState cs = world.getBlockState(c);
                if (cs.getBlock() instanceof net.minecraft.block.BlockDoor) {
                    // A door counts as both halves when it opens or shuts; the upper half's POWERED alone is nothing.
                    if (cs.getValue(net.minecraft.block.BlockDoor.HALF) == net.minecraft.block.BlockDoor.EnumDoorHalf.UPPER) {
                        continue;
                    }
                    if (swept.intersects(new AxisAlignedBB(c.up()))) {
                        unsupported = true;
                    }
                }
                if (!c.equals(m) && swept.intersects(new AxisAlignedBB(c))) {
                    unsupported = true;
                }
            }
        }

        // World: the scheduled updates.
        world.time++;
        for (Object[] t : world.ticks) {
            BlockPos p = (BlockPos) t[0];
            if ((Long) t[2] <= world.time && world.getBlockState(p).getBlock() instanceof BlockBasePressurePlate && onEdge(box, p)) {
                unsupported = true;
            }
        }
        world.changes.clear();
        world.tickUpdates();
        touched.addAll(world.changes);
        if (world.reachedUnmodelled) {
            unsupported = true;
        }

        // Network: the engine's bound on the server player's own move.
        double fall = Math.max(0.0D, -motionY);
        AxisAlignedBB reach = new AxisAlignedBB(box.minX - drift, box.minY - fall, box.minZ - drift,
                box.maxX + drift, box.maxY, box.maxZ + drift);
        maybe = new ArrayList<BlockPos>();
        for (BlockPos p : cells(reach)) {
            if (plateOff(p) && touchesExactly(reach.grow(EDGE), p)) {
                maybe.add(p);
            }
        }
        float friction = onGround ? world.getBlockState(new BlockPos(rx, box.minY - 1.0D, rz)).getBlock().slipperiness * 0.91F : 0.91F;
        drift *= (double) friction;
        if (drift < 0.003D) {
            drift = 0.0D;
        }
        return unsupported;
    }

    private boolean plateOff(BlockPos p) {
        IBlockState st = world.getBlockState(p);
        if (!(st.getBlock() instanceof BlockBasePressurePlate)) {
            return false;
        }
        for (net.minecraft.block.properties.IProperty<?> prop : st.getPropertyKeys()) {
            Object v = st.getValue(prop);
            if (v.equals(Boolean.TRUE) || v instanceof Integer && (Integer) v > 0) {
                return false;
            }
        }
        return true;
    }

    /** {@code doBlockCollisions}' blocks: those the box overlaps, shrunk by 0.001, in its order. */
    private static List<BlockPos> cells(AxisAlignedBB bb) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        BlockPos a = new BlockPos(bb.minX + 0.001D, bb.minY + 0.001D, bb.minZ + 0.001D);
        BlockPos b = new BlockPos(bb.maxX - 0.001D, bb.maxY - 0.001D, bb.maxZ - 0.001D);
        for (int x = a.getX(); x <= b.getX(); x++) {
            for (int y = a.getY(); y <= b.getY(); y++) {
                for (int z = a.getZ(); z <= b.getZ(); z++) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    private static boolean touchesExactly(AxisAlignedBB bb, BlockPos p) {
        return cells(bb).contains(p)
                && bb.intersects(new AxisAlignedBB(0.125D, 0.0D, 0.125D, 0.875D, 0.25D, 0.875D).offset(p));
    }

    /** The engine's rule: whether the plate finds the box on it changes if the box moves by {@link #EDGE}. */
    private static boolean onEdge(AxisAlignedBB box, BlockPos p) {
        return touchesExactly(box.grow(-EDGE), p) != touchesExactly(box.grow(EDGE), p);
    }

    /** The blocks the server and the client have that differ from the base world, and the pending updates. */
    Mechanisms snapshot() {
        Set<BlockPos> at = new LinkedHashSet<BlockPos>(touched);
        for (Object[] t : world.ticks) {
            at.add((BlockPos) t[0]);
        }
        List<int[]> pos = new ArrayList<int[]>();
        List<BlockType> sv = new ArrayList<BlockType>();
        List<BlockType> cl = new ArrayList<BlockType>();
        List<Integer> due = new ArrayList<Integer>();
        for (BlockPos p : at) {
            BlockType b = base.getBlock(p.getX(), p.getY(), p.getZ());
            BlockType s = VanillaBlocks.require(VanillaOracle.catalogName(world.getBlockState(p)));
            BlockType c = VanillaBlocks.require(VanillaOracle.catalogName(client.getBlockState(p)));
            int d = 0;
            for (Object[] t : world.ticks) {
                if (t[0].equals(p)) {
                    d = (int) ((Long) t[2] - world.time);
                }
            }
            if (!s.name().equals(b.name()) || !c.name().equals(b.name()) || d > 0) {
                pos.add(new int[] {p.getX(), p.getY(), p.getZ()});
                sv.add(s.name().equals(b.name()) ? b : s);
                cl.add(c.name().equals(b.name()) ? b : c);
                due.add(d);
            }
        }
        int[] xyz = new int[3 * pos.size()];
        int[] d = new int[pos.size()];
        for (int i = 0; i < pos.size(); i++) {
            System.arraycopy(pos.get(i), 0, xyz, 3 * i, 3);
            d[i] = due.get(i);
        }
        int[] mb = new int[3 * maybe.size()];
        for (int i = 0; i < maybe.size(); i++) {
            mb[3 * i] = maybe.get(i).getX();
            mb[3 * i + 1] = maybe.get(i).getY();
            mb[3 * i + 2] = maybe.get(i).getZ();
        }
        return Mechanisms.of(xyz, sv.toArray(new BlockType[0]), cl.toArray(new BlockType[0]), d, mb);
    }
}
