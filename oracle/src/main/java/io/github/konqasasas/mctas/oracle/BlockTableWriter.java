package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.FaceShape;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockObserver;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.block.BlockRedstoneTorch;
import net.minecraft.block.BlockTripWire;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockCactus;
import net.minecraft.block.BlockChorusFlower;
import net.minecraft.block.BlockCocoa;
import net.minecraft.block.BlockDragonEgg;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockFarmland;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFire;
import net.minecraft.block.BlockFrostedIce;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockPistonExtension;
import net.minecraft.block.BlockReed;
import net.minecraft.block.BlockShulkerBox;
import net.minecraft.block.BlockSponge;
import net.minecraft.block.BlockStem;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes {@code VanillaBlocks.TABLE}: every block of the game that has no hand-written entry, with the
 * collision boxes, face shapes and flags of each state, read from the game itself.
 *
 * <p>Only blocks whose collision depends on nothing but their own state go in the table. Each block is
 * checked among random neighbours; one whose boxes or faces change with them is left out and listed,
 * since it needs a hand-written entry. The names keep only the properties that change something here.
 *
 * <p>Usage: {@code BlockTableWriter <out file>}.
 */
public final class BlockTableWriter {

    /** Teleporting blocks and the moving piston, whose shape lives in a tile entity. */
    private static final String[] LEFT_OUT = {"minecraft:portal", "minecraft:end_portal", "minecraft:end_gateway",
            "minecraft:piston_extension"};

    private static final AxisAlignedBB EVERYTHING = new AxisAlignedBB(-100, -100, -100, 100, 400, 100);

    private BlockTableWriter() {
    }

    public static void main(String[] args) throws IOException {
        new VanillaOracle(new SparseBlockWorld());
        OracleWorld world = new OracleWorld(new SparseBlockWorld());
        List<IBlockState> everyState = new ArrayList<IBlockState>();
        for (Block b : Block.REGISTRY) {
            everyState.addAll(b.getBlockState().getValidStates());
        }
        Random r = new Random(12);
        List<String> lines = new ArrayList<String>();
        List<String> dependent = new ArrayList<String>();
        TreeSet<ResourceLocation> ids = new TreeSet<ResourceLocation>(new java.util.Comparator<ResourceLocation>() {
            @Override
            public int compare(ResourceLocation a, ResourceLocation b) {
                return a.toString().compareTo(b.toString());
            }
        });
        ids.addAll(Block.REGISTRY.getKeys());
        for (ResourceLocation key : ids) {
            String id = key.toString();
            if (VanillaBlocks.isCoded(id) || java.util.Arrays.asList(LEFT_OUT).contains(id)) {
                continue;
            }
            Block block = Block.REGISTRY.getObject(key);
            // Only the states a world can hold: a state sent to the client or saved comes back as the
            // registered state for its id, which drops the moving piston head's "short".
            Collection<IBlockState> states = new ArrayList<IBlockState>();
            for (IBlockState s : block.getBlockState().getValidStates()) {
                if (Block.BLOCK_STATE_IDS.getByValue(Block.BLOCK_STATE_IDS.get(s)) == s) {
                    states.add(s);
                }
            }
            Map<IBlockState, String> records = new LinkedHashMap<IBlockState, String>();
            Map<IBlockState, String> behaviour = new LinkedHashMap<IBlockState, String>();
            boolean neighbourDependent = false;
            for (IBlockState s : states) {
                world.clear();
                world.set(BlockPos.ORIGIN, s);
                records.put(s, record(world, BlockPos.ORIGIN, s));
                behaviour.put(s, records.get(s) + "\t" + interaction(world, BlockPos.ORIGIN, s));
                BlockPos centre = new BlockPos(0, 1, 0);
                world.clear();
                world.set(centre, s);
                String alone = record(world, centre, s);
                for (int trial = 0; trial < 40 && !neighbourDependent; trial++) {
                    world.clear();
                    for (int x = -1; x <= 1; x++) {
                        for (int y = 0; y <= 2; y++) {
                            for (int z = -1; z <= 1; z++) {
                                double roll = r.nextDouble();
                                IBlockState n = roll < 0.3 ? Blocks.AIR.getDefaultState()
                                        : roll < 0.6 ? pick(states, r) : everyState.get(r.nextInt(everyState.size()));
                                world.set(new BlockPos(x, y, z), n);
                            }
                        }
                    }
                    world.set(centre, s);
                    if (!record(world, centre, s).equals(alone)) {
                        neighbourDependent = true;
                    }
                }
                if (neighbourDependent) {
                    break;
                }
            }
            if (neighbourDependent) {
                dependent.add(id);
                continue;
            }
            List<String> keep = relevantProperties(block, behaviour);
            lines.add("props\t" + id + "\t" + join(keep, ","));
            Map<String, String> byName = new TreeMap<String, String>();
            for (Map.Entry<IBlockState, String> e : records.entrySet()) {
                String name = name(id, e.getKey(), keep);
                String prev = byName.put(name, e.getValue());
                if (prev != null && !prev.equals(e.getValue())) {
                    throw new IllegalStateException(name + " is not determined by " + keep);
                }
            }
            for (Map.Entry<String, String> e : byName.entrySet()) {
                lines.add(e.getKey() + "\t" + e.getValue());
            }
        }
        PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(args[0]), StandardCharsets.UTF_8));
        try {
            w.println("# Generated by io.github.konqasasas.mctas.oracle.BlockTableWriter from the 1.12.2 server jar.");
            w.println("# Left out, shape depends on the neighbours (hand-written or unsupported): " + join(dependent, " "));
            w.println("# Left out, teleports or tile-entity shape: " + join(java.util.Arrays.asList(LEFT_OUT), " "));
            for (String l : lines) {
                w.println(l);
            }
        } finally {
            w.close();
        }
        System.out.println(lines.size() + " lines; neighbour-dependent: " + dependent);
    }

    private static IBlockState pick(Collection<IBlockState> states, Random r) {
        int i = r.nextInt(states.size());
        for (IBlockState s : states) {
            if (i-- == 0) {
                return s;
            }
        }
        throw new AssertionError();
    }

    /** Everything the engine reads from a state, as one table line after the name. */
    static String record(OracleWorld world, BlockPos pos, IBlockState s) {
        Block b = s.getBlock();
        List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
        s.addCollisionBoxToList(world, pos, EVERYTHING, boxes, null, false);
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(Float.floatToIntBits(b.slipperiness))).append('\t');
        sb.append(s.isNormalCube() ? 1 : 0).append('\t');
        sb.append(s.getMaterial() == Material.AIR ? 1 : 0).append('\t');
        sb.append(s.getMaterial().blocksMovement() ? 1 : 0).append('\t');
        sb.append(flags(b)).append('\t');
        for (EnumFacing f : new EnumFacing[] {EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH,
                EnumFacing.WEST, EnumFacing.EAST}) {
            sb.append(FaceShape.valueOf(s.getBlockFaceShape(world, pos, f).name()).ordinal());
        }
        sb.append('\t');
        for (int i = 0; i < boxes.size(); i++) {
            AxisAlignedBB a = boxes.get(i).offset(-pos.getX(), -pos.getY(), -pos.getZ());
            if (i > 0) {
                sb.append(';');
            }
            sb.append(Double.toHexString(a.minX)).append(',').append(Double.toHexString(a.minY)).append(',')
                    .append(Double.toHexString(a.minZ)).append(',').append(Double.toHexString(a.maxX)).append(',')
                    .append(Double.toHexString(a.maxY)).append(',').append(Double.toHexString(a.maxZ));
        }
        return sb.toString();
    }

    /**
     * What else the engine reads from a state, which the name must therefore keep though the table line
     * leaves it out: the selection box the view ray hits (a button's facing and whether it is pressed),
     * and the side a shulker box opens to.
     */
    private static String interaction(OracleWorld world, BlockPos pos, IBlockState s) {
        AxisAlignedBB a = s.getBoundingBox(world, pos);
        String sel = a == null || a == Block.NULL_AABB ? "-" : a.toString();
        if (s.getBlock() instanceof BlockShulkerBox) {
            sel += "\tlid " + s.getValue(BlockShulkerBox.FACING);
        }
        if (s.getBlock() instanceof net.minecraft.block.BlockLever) {
            // The power it gives a trapdoor or a door (Mechanisms).
            sel += "\tpowered " + s.getValue(net.minecraft.block.BlockLever.POWERED);
        }
        return sel;
    }

    static int flags(Block b) {
        int f = 0;
        if (call(BlockFence.class, "isExcepBlockForAttachWithPiston", b)) {
            f |= 1;
        }
        if (call(BlockPane.class, "isExcepBlockForAttachWithPiston", b)) {
            f |= 2;
        }
        if (call(Block.class, "isExceptBlockForAttachWithPiston", b)) {
            f |= 4;
        }
        if (b.getDefaultState().getMaterial() == Material.ICE) {
            f |= 8;
        }
        if (b instanceof BlockFalling || b instanceof BlockAnvil || b instanceof BlockDragonEgg || b instanceof BlockLeaves
                || b instanceof BlockFrostedIce || b instanceof BlockFarmland || b instanceof BlockCactus
                || b instanceof BlockReed || b instanceof BlockChorusFlower || b instanceof BlockCocoa
                || b instanceof BlockBush || b instanceof BlockFire || b instanceof BlockStem || b instanceof BlockSponge
                || b instanceof BlockPistonBase || b instanceof BlockPistonExtension
                // The player powers these, or they pulse or notify their neighbours when placed; powered
                // rails also break when placed without support.
                || b instanceof BlockBasePressurePlate || b instanceof BlockTripWire || b instanceof BlockRailBase
                || b instanceof BlockObserver || b instanceof BlockRedstoneTorch || b instanceof BlockRedstoneOre) {
            f |= 16;
        }
        return f;
    }

    private static boolean call(Class<?> owner, String name, Block b) {
        try {
            Method m = owner.getDeclaredMethod(name, Block.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(null, b);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The properties that change the record of some state when changed alone, sorted. */
    private static List<String> relevantProperties(Block block, Map<IBlockState, String> records) {
        List<String> keep = new ArrayList<String>();
        for (IProperty<?> p : block.getBlockState().getProperties()) {
            boolean relevant = false;
            for (IBlockState s : records.keySet()) {
                for (IBlockState t : records.keySet()) {
                    if (differOnlyIn(s, t, p) && !records.get(s).equals(records.get(t))) {
                        relevant = true;
                        break;
                    }
                }
                if (relevant) {
                    break;
                }
            }
            if (relevant) {
                keep.add(p.getName());
            }
        }
        java.util.Collections.sort(keep);
        return keep;
    }

    private static boolean differOnlyIn(IBlockState s, IBlockState t, IProperty<?> p) {
        if (s.getValue(p).equals(t.getValue(p))) {
            return false;
        }
        for (IProperty<?> q : s.getPropertyKeys()) {
            if (q != p && !s.getValue(q).equals(t.getValue(q))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String name(String id, IBlockState s, List<String> keep) {
        Map<String, String> props = new HashMap<String, String>();
        for (IProperty p : s.getPropertyKeys()) {
            if (keep.contains(p.getName())) {
                props.put(p.getName(), p.getName(s.getValue(p)));
            }
        }
        return VanillaBlocks.name(id, props);
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
