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
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;

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
        }
    }

    /** Boxes and faces of a block among random neighbours, two blocks out so stairs see their neighbours' shapes. */
    @Test
    public void boxesAndFacesMatchVanillaAmongRandomNeighbours() {
        List<BlockType> all = new ArrayList<BlockType>(VanillaBlocks.all());
        Random r = new Random(1);
        AxisAlignedBB everything = new AxisAlignedBB(-100, -100, -100, 100, 400, 100);
        Aabb everythingE = new Aabb(-100, -100, -100, 100, 400, 100);
        for (int trial = 0; trial < 20000; trial++) {
            SparseBlockWorld w = new SparseBlockWorld();
            // Few kinds per trial, so connections between like blocks are common.
            List<BlockType> pick = new ArrayList<BlockType>();
            for (int i = 0; i < 1 + r.nextInt(4); i++) {
                pick.add(all.get(r.nextInt(all.size())));
            }
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (r.nextInt(4) != 0) {
                        w.setBlock(x, 64, z, pick.get(r.nextInt(pick.size())));
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
