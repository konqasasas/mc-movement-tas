package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.FaceShape;
import io.github.konqasasas.mctas.physics.Facing;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Every catalog entry against the vanilla block state it names. */
public class BlockCatalogTest {

    static {
        new VanillaOracle(new SparseBlockWorld());
    }

    @Test
    public void propertiesMatchVanilla() {
        for (BlockType t : VanillaBlocks.all()) {
            IBlockState s = VanillaStates.of(t);
            assertEquals(t + " name", t.name(), nameOf(s));
            assertEquals(t + " normal cube", s.isNormalCube(), t.isNormalCube());
            assertEquals(t + " slipperiness", s.getBlock().slipperiness, t.slipperiness(), 0.0F);
            assertEquals(t + " air", s.getMaterial() == Material.AIR, t.isAir());
            assertEquals(t + " blocks movement", s.getMaterial().blocksMovement(), t.blocksMovement());
            assertEquals(t + " liquid", s.getMaterial().isLiquid(), t instanceof VanillaBlocks.Liquid);
            if (t instanceof VanillaBlocks.Liquid) {
                assertEquals(t + " lava", s.getMaterial() == Material.LAVA, ((VanillaBlocks.Liquid) t).isLava());
            }
            assertEquals(t + " piston exception", exceptWithPiston(s.getBlock()), t.isExcept(BlockType.EXCEPT_PISTON));
            assertEquals(t + " ice material", s.getMaterial() == Material.ICE,
                    t.isIceMaterial());
        }
    }

    /** A state sent to the client as its id comes back with the same stored name. */
    @Test
    public void storedNamesSurviveTheNetwork() {
        for (BlockType t : VanillaBlocks.all()) {
            IBlockState s = VanillaStates.of(t);
            IBlockState back = net.minecraft.block.Block.BLOCK_STATE_IDS.getByValue(net.minecraft.block.Block.BLOCK_STATE_IDS.get(s));
            assertEquals(t + " after the network", VanillaBlocks.storedName(t),
                    VanillaBlocks.storedName(VanillaBlocks.require(nameOf(back))));
        }
    }

    /** {@code BlockLiquid.getFlow} at the centre of random 5x5x3 mixes of liquids and other blocks. */
    @Test
    public void liquidCurrentsMatchVanilla() {
        List<BlockType> liquids = new ArrayList<BlockType>();
        List<BlockType> others = new ArrayList<BlockType>();
        for (BlockType t : VanillaBlocks.all()) {
            (t instanceof VanillaBlocks.Liquid ? liquids : others).add(t);
        }
        Random r = new Random(2);
        int compared = 0;
        for (int trial = 0; trial < 20000; trial++) {
            SparseBlockWorld w = new SparseBlockWorld();
            List<BlockType> pick = new ArrayList<BlockType>();
            for (int i = 0; i < 1 + r.nextInt(3); i++) {
                pick.add(liquids.get(r.nextInt(liquids.size())));
            }
            for (int i = 0; i < r.nextInt(3); i++) {
                pick.add(others.get(r.nextInt(others.size())));
            }
            for (int x = -2; x <= 2; x++) {
                for (int y = 63; y <= 65; y++) {
                    for (int z = -2; z <= 2; z++) {
                        if (r.nextInt(4) != 0) {
                            w.setBlock(x, y, z, pick.get(r.nextInt(pick.size())));
                        }
                    }
                }
            }
            OracleWorld vw = new OracleWorld(w);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockType t = w.getBlock(x, 64, z);
                    if (!(t instanceof VanillaBlocks.Liquid)) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, 64, z);
                    Vec3d v = vw.getBlockState(pos).getBlock().modifyAcceleration(vw, pos, null, Vec3d.ZERO);
                    double[] e = ((VanillaBlocks.Liquid) t).flow(w, x, 64, z);
                    String where = t + " at " + pos + " in trial " + trial;
                    assertEquals(where, v.x, e[0], 0.0D);
                    assertEquals(where, v.y, e[1], 0.0D);
                    assertEquals(where, v.z, e[2], 0.0D);
                    compared++;
                }
            }
        }
        assertTrue(compared > 50000);
    }

    private static boolean exceptWithPiston(net.minecraft.block.Block b) {
        try {
            java.lang.reflect.Method m = net.minecraft.block.Block.class.getDeclaredMethod("isExceptBlockForAttachWithPiston",
                    net.minecraft.block.Block.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(null, b);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Boxes and faces of a block among random neighbours, two blocks out horizontally so stairs see their neighbours' shapes. */
    @Test
    public void boxesAndFacesMatchVanillaAmongRandomNeighbours() {
        compareAmongRandomNeighbours(new ArrayList<BlockType>(VanillaBlocks.all()), 1, 20000);
    }

    /** Doors and chorus plants with the blocks their shapes depend on, so the pairings come up often. */
    @Test
    public void doorsAndChorusPlantsMatchVanilla() {
        List<BlockType> palette = new ArrayList<BlockType>();
        for (BlockType t : VanillaBlocks.all()) {
            String id = VanillaBlocks.blockId(t.name());
            if (id.endsWith("_door") || id.startsWith("minecraft:chorus_") || id.equals("minecraft:end_stone")) {
                palette.add(t);
            }
        }
        compareAmongRandomNeighbours(palette, 3, 20000);
    }

    private static void compareAmongRandomNeighbours(List<BlockType> all, long seed, int trials) {
        Random r = new Random(seed);
        AxisAlignedBB everything = new AxisAlignedBB(-100, -100, -100, 100, 400, 100);
        Aabb everythingE = new Aabb(-100, -100, -100, 100, 400, 100);
        for (int trial = 0; trial < trials; trial++) {
            SparseBlockWorld w = new SparseBlockWorld();
            // Few kinds per trial, so connections between like blocks are common.
            List<BlockType> pick = new ArrayList<BlockType>();
            for (int i = 0; i < 1 + r.nextInt(4); i++) {
                pick.add(all.get(r.nextInt(all.size())));
            }
            // Three layers, so doors find their other half and chorus plants their vertical arms.
            for (int x = -2; x <= 2; x++) {
                for (int y = 63; y <= 65; y++) {
                    for (int z = -2; z <= 2; z++) {
                        if (r.nextInt(4) != 0) {
                            w.setBlock(x, y, z, pick.get(r.nextInt(pick.size())));
                        }
                    }
                }
            }
            OracleWorld vw = new OracleWorld(w);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockType t = w.getBlock(x, 64, z);
                    BlockPos pos = new BlockPos(x, 64, z);
                    IBlockState s = vw.getBlockState(pos);
                    List<AxisAlignedBB> expected = new ArrayList<AxisAlignedBB>();
                    s.addCollisionBoxToList(vw, pos, everything, expected, null, false);
                    List<Aabb> actual = new ArrayList<Aabb>();
                    t.addCollisionBoxes(w, x, 64, z, everythingE, actual);
                    assertEquals(t + " at " + pos + " in trial " + trial, describe(expected), describeE(actual));
                    for (Facing f : Facing.values()) {
                        BlockFaceShape vf = s.getBlockFaceShape(vw, pos, EnumFacing.valueOf(f.name()));
                        FaceShape ef = t.faceShape(w, x, 64, z, f);
                        assertEquals(t + " face " + f + " in trial " + trial, vf.name(), ef.name());
                    }
                }
            }
        }
    }

    private static String nameOf(IBlockState s) {
        String id = String.valueOf(net.minecraft.block.Block.REGISTRY.getNameForObject(s.getBlock()));
        java.util.Map<String, String> props = new java.util.HashMap<String, String>();
        List<String> keys = VanillaBlocks.properties(id);
        for (net.minecraft.block.properties.IProperty<?> p : s.getPropertyKeys()) {
            if (keys != null && keys.contains(p.getName())) {
                props.put(p.getName(), valueName(p, s));
            }
        }
        return VanillaBlocks.name(id, props);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(net.minecraft.block.properties.IProperty p, IBlockState s) {
        return p.getName(s.getValue(p));
    }

    private static String describe(List<AxisAlignedBB> l) {
        StringBuilder sb = new StringBuilder();
        for (AxisAlignedBB b : l) {
            sb.append(new Aabb(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)).append(' ');
        }
        return sb.toString();
    }

    private static String describeE(List<Aabb> l) {
        StringBuilder sb = new StringBuilder();
        for (Aabb b : l) {
            sb.append(b).append(' ');
        }
        return sb.toString();
    }
}
