package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBeacon;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockBrewingStand;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockChorusPlant;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.BlockVine;
import net.minecraft.block.BlockWall;
import net.minecraft.block.BlockDaylightDetector;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockDragonEgg;
import net.minecraft.block.BlockEnchantmentTable;
import net.minecraft.block.BlockEnderChest;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockFurnace;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.BlockJukebox;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockNote;
import net.minecraft.block.BlockRedstoneDiode;
import net.minecraft.block.BlockShulkerBox;
import net.minecraft.block.BlockSign;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWorkbench;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.Item;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Writes {@code VanillaBlocks.INTERACTION_TABLE}: for every state in the catalog, what right-clicking
 * reads from it. The columns are: whether the view ray can hit it ({@code canCollideCheck}), whether its
 * material is replaceable, whether right-clicking it without sneaking activates it instead of placing,
 * whether its item places a plain full block, whether the block is replaceable ({@code Block.isReplaceable}),
 * and its selection box ({@code getBoundingBox}); the last two are {@code dyn} when they depend on the
 * neighbours, which the engine then computes by hand or reports as unsupported. A flower's box is
 * marked {@code xz:}: the engine moves it by {@code Block.getOffset} for the position.
 *
 * <p>Usage: {@code InteractionTableWriter <out file>}.
 */
public final class InteractionTableWriter {

    /**
     * Full blocks the engine does not place: survival players cannot use command and structure blocks
     * or barriers, a spawner spawns mobs, and a redstone block powers what is around it.
     */
    private static final String[] NOT_PLACED = {"minecraft:command_block", "minecraft:chain_command_block",
            "minecraft:repeating_command_block", "minecraft:structure_block", "minecraft:barrier",
            "minecraft:mob_spawner", "minecraft:redstone_block"};

    private InteractionTableWriter() {
    }

    public static void main(String[] args) throws IOException {
        new VanillaOracle(new SparseBlockWorld());
        OracleWorld world = new OracleWorld(new SparseBlockWorld());
        List<BlockType> all = new ArrayList<BlockType>(VanillaBlocks.all());
        Random r = new Random(13);
        BlockPos centre = new BlockPos(0, 1, 0);
        List<String> lines = new ArrayList<String>();
        List<String> dynamic = new ArrayList<String>();
        for (BlockType t : all) {
            IBlockState s = VanillaStates.of(t);
            world.clear();
            world.set(centre, s);
            String alone = s.getBlock() instanceof BlockFlower ? "xz:" + flowerBox() : selection(world, centre, s);
            boolean aloneReplaceable = s.getBlock().isReplaceable(world, centre);
            boolean dyn = false;
            boolean replaceableDyn = false;
            for (int trial = 0; trial < 40; trial++) {
                world.clear();
                for (int x = -1; x <= 1; x++) {
                    for (int y = 0; y <= 2; y++) {
                        for (int z = -1; z <= 1; z++) {
                            if (r.nextInt(3) != 0) {
                                world.set(new BlockPos(x, y, z), VanillaStates.of(all.get(r.nextInt(all.size()))));
                            }
                        }
                    }
                }
                world.set(centre, s);
                if (!(s.getBlock() instanceof BlockFlower) && !selection(world, centre, s).equals(alone)) {
                    dyn = true;
                }
                if (s.getBlock().isReplaceable(world, centre) != aloneReplaceable) {
                    replaceableDyn = true;
                }
            }
            Block b = s.getBlock();
            // Random neighbours rarely line up a door's other half or a wall next to a gate.
            dyn |= b instanceof BlockFence || b instanceof BlockFenceGate || b instanceof BlockWall
                    || b instanceof BlockPane || b instanceof BlockChest || b instanceof BlockDoor
                    || b instanceof BlockChorusPlant || b instanceof BlockVine || b instanceof BlockRedstoneWire;
            // The upper half's variant comes from the lower half.
            replaceableDyn |= b instanceof BlockDoublePlant;
            if (dyn || replaceableDyn) {
                dynamic.add(t.name());
            }
            lines.add(t.name() + "\t" + (b.canCollideCheck(s, false) ? 1 : 0)
                    + "\t" + (s.getMaterial().isReplaceable() ? 1 : 0)
                    + "\t" + (activates(s) ? 1 : 0)
                    + "\t" + (placeable(t, s) ? 1 : 0)
                    + "\t" + (replaceableDyn ? "dyn" : aloneReplaceable ? "1" : "0")
                    + "\t" + (dyn ? "dyn" : alone));
        }
        PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(args[0]), StandardCharsets.UTF_8));
        try {
            w.println("# Generated by io.github.konqasasas.mctas.oracle.InteractionTableWriter from the 1.12.2 server jar.");
            w.println("# name, ray can hit, material replaceable, activates, places a full block, block replaceable, selection box; dyn = depends on the neighbours");
            for (String l : lines) {
                w.println(l);
            }
        } finally {
            w.close();
        }
        System.out.println(lines.size() + " states; selection depends on neighbours: " + dynamic);
    }

    /** {@code BlockBush.BUSH_AABB}, which {@code BlockFlower.getBoundingBox} moves by the position's offset. */
    private static String flowerBox() {
        try {
            java.lang.reflect.Field f = net.minecraft.block.BlockBush.class.getDeclaredField("BUSH_AABB");
            f.setAccessible(true);
            return box((AxisAlignedBB) f.get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The selection box, block-local, or {@code -} for none. */
    private static String selection(OracleWorld world, BlockPos pos, IBlockState s) {
        AxisAlignedBB a = s.getBoundingBox(world, pos);
        return a == Block.NULL_AABB || a == null ? "-" : box(a);
    }

    private static String box(AxisAlignedBB a) {
        return Double.toHexString(a.minX) + ',' + Double.toHexString(a.minY) + ',' + Double.toHexString(a.minZ) + ','
                + Double.toHexString(a.maxX) + ',' + Double.toHexString(a.maxY) + ',' + Double.toHexString(a.maxZ);
    }

    /**
     * True if the client's {@code onBlockActivated} returns true for a survival player holding a full
     * block with a full food bar: right-clicking it then does its own thing instead of placing. Blocks
     * that return false for such a player (cauldrons, empty flower pots and jukeboxes, cakes when not
     * hungry, command and structure blocks, iron doors and trapdoors, redstone ore, TNT) are not listed.
     */
    static boolean activates(IBlockState s) {
        Block b = s.getBlock();
        if (b instanceof BlockDoor || b instanceof BlockTrapDoor) {
            return s.getMaterial() != Material.IRON;
        }
        if (b instanceof BlockJukebox) {
            return s.getValue(BlockJukebox.HAS_RECORD);
        }
        return b instanceof BlockWorkbench || b instanceof BlockChest || b instanceof BlockEnderChest
                || b instanceof BlockFurnace || b instanceof BlockDispenser || b instanceof BlockHopper
                || b instanceof BlockBrewingStand || b instanceof BlockEnchantmentTable || b instanceof BlockAnvil
                || b instanceof BlockBeacon || b instanceof BlockShulkerBox || b instanceof BlockBed
                || b instanceof BlockSign || b instanceof BlockNote || b instanceof BlockDaylightDetector
                || b instanceof BlockRedstoneDiode || b instanceof BlockLever || b instanceof BlockButton
                || b instanceof BlockFenceGate || b instanceof BlockDragonEgg;
    }

    /**
     * True if this is the state a plain full block is placed as: the block has an item, the item places
     * this catalog entry whatever the side, hit point and look, and the block is one full collision box
     * that stays put and needs no support.
     */
    static boolean placeable(BlockType t, IBlockState s) {
        Block b = s.getBlock();
        if (Item.getItemFromBlock(b) == net.minecraft.init.Items.AIR || t.isExcept(BlockType.UNSTABLE)) {
            return false;
        }
        String id = VanillaBlocks.blockId(t.name());
        if (java.util.Arrays.asList(NOT_PLACED).contains(id)) {
            return false;
        }
        List<String> props = VanillaBlocks.properties(id);
        if (props != null && !props.isEmpty()) {
            return false;
        }
        if (!t.name().equals(id)) {
            return false;
        }
        List<io.github.konqasasas.mctas.physics.Aabb> boxes = new ArrayList<io.github.konqasasas.mctas.physics.Aabb>();
        t.addCollisionBoxes(new SparseBlockWorld(), 0, 64, 0,
                new io.github.konqasasas.mctas.physics.Aabb(-1, 63, -1, 2, 66, 2), boxes);
        if (boxes.size() != 1 || !boxes.get(0).equals(new io.github.konqasasas.mctas.physics.Aabb(0, 64, 0, 1, 65, 1))) {
            return false;
        }
        AxisAlignedBB placedBox = b.getDefaultState().getCollisionBoundingBox(new OracleWorld(new SparseBlockWorld()), BlockPos.ORIGIN);
        return Block.FULL_BLOCK_AABB.equals(placedBox)
                && !overrides(b, "canPlaceBlockAt") && !overrides(b, "canPlaceBlockOnSide");
    }

    private static boolean overrides(Block block, String name) {
        for (Class<?> c = block.getClass(); c != null && c != Block.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
