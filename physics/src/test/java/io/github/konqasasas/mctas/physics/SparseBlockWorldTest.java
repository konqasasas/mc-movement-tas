package io.github.konqasasas.mctas.physics;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The lookup index of {@link SparseBlockWorld} answers exactly as a plain map of positions would. */
public class SparseBlockWorldTest {

    private static final BlockType[] TYPES = {BlockType.STONE, BlockType.ICE, BlockType.PACKED_ICE, BlockType.AIR};

    @Test
    public void smallCourseMatchesMap() {
        check(1, 40, 20);
    }

    @Test
    public void spreadOutCourseFallsBackToSectionsAndMatchesMap() {
        // Far enough apart that the flat box would be too large.
        check(2, 3000, 120);
    }

    @Test
    public void positionsBeyondTheKeyRangeShareABlock() {
        SparseBlockWorld w = new SparseBlockWorld();
        w.setBlock(5, 10, -7, BlockType.STONE);
        assertSame(BlockType.STONE, w.getBlock(5 + (1 << 26), 10, -7));
        assertSame(BlockType.STONE, w.getBlock(5, 10, -7 - (1 << 26)));
        assertSame(BlockType.AIR, w.getBlock(5, 11, -7));
    }

    @Test
    public void tracksLiquids() {
        SparseBlockWorld w = new SparseBlockWorld();
        assertFalse(w.mayContainLiquid());
        BlockType water = VanillaBlocks.require("minecraft:water[level=0]");
        w.setBlock(0, 64, 0, water);
        assertTrue(w.mayContainLiquid());
        SparseBlockWorld copy = w.copy();
        w.setBlock(0, 64, 0, BlockType.STONE);
        assertFalse(w.mayContainLiquid());
        assertTrue(copy.mayContainLiquid());
        assertSame(water, copy.getBlock(0, 64, 0));
    }

    private static void check(long seed, int spread, int height) {
        Random r = new Random(seed);
        SparseBlockWorld w = new SparseBlockWorld();
        Map<Long, BlockType> map = new HashMap<Long, BlockType>();
        SparseBlockWorld snapshot = null;
        Map<Long, BlockType> snapshotMap = null;
        for (int i = 0; i < 20000; i++) {
            int x = r.nextInt(spread) - spread / 2;
            int y = 60 + r.nextInt(height) - height / 2;
            int z = r.nextInt(spread) - spread / 2;
            BlockType t = TYPES[r.nextInt(TYPES.length)];
            w.setBlock(x, y, z, t);
            if (t == BlockType.AIR) {
                map.remove(SparseBlockWorld.key(x, y, z));
            } else {
                map.put(SparseBlockWorld.key(x, y, z), t);
            }
            if (i == 10000) {
                snapshot = w.copy();
                snapshotMap = new HashMap<Long, BlockType>(map);
            }
        }
        assertEquals(map.size(), w.size());
        compare(r, w, map, spread, height);
        compare(r, snapshot, snapshotMap, spread, height);
    }

    private static void compare(Random r, SparseBlockWorld w, Map<Long, BlockType> map, int spread, int height) {
        for (Map.Entry<Long, BlockType> e : map.entrySet()) {
            long k = e.getKey();
            assertSame(e.getValue(), w.getBlock((int) (k >> 38), (int) (k & 0xFFFL), (int) (k << 26 >> 38)));
        }
        for (int i = 0; i < 50000; i++) {
            int x = r.nextInt(spread + 40) - spread / 2 - 20;
            int y = r.nextInt(300) - 20;
            int z = r.nextInt(spread + 40) - spread / 2 - 20;
            BlockType expected = y < 0 || y >= 256 ? null : map.get(SparseBlockWorld.key(x, y, z));
            assertSame(expected == null ? BlockType.AIR : expected, w.getBlock(x, y, z));
        }
    }
}
