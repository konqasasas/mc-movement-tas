package io.github.konqasasas.mctas.physics;

import java.util.HashMap;
import java.util.Map;

/**
 * A mutable world that stores only non-air blocks. {@link #copy()} gives an independent world, so a
 * search can branch on block placement later.
 */
public final class SparseBlockWorld implements BlockWorld {

    private final HashMap<Long, BlockType> blocks;

    public SparseBlockWorld() {
        this.blocks = new HashMap<Long, BlockType>();
    }

    private SparseBlockWorld(HashMap<Long, BlockType> blocks) {
        this.blocks = blocks;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    @Override
    public BlockType getBlock(int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return BlockType.AIR;
        }
        BlockType b = blocks.get(key(x, y, z));
        return b == null ? BlockType.AIR : b;
    }

    public SparseBlockWorld setBlock(int x, int y, int z, BlockType type) {
        if (y < 0 || y >= 256) {
            throw new IllegalArgumentException("y out of build height: " + y);
        }
        if (type == BlockType.AIR) {
            blocks.remove(key(x, y, z));
        } else {
            blocks.put(key(x, y, z), type);
        }
        return this;
    }

    /** Fills the inclusive box [x1..x2] x [y1..y2] x [z1..z2]. */
    public SparseBlockWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockType type) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    setBlock(x, y, z, type);
                }
            }
        }
        return this;
    }

    public int size() {
        return blocks.size();
    }

    /** Calls {@code visitor} once per stored block. */
    public void forEach(Visitor visitor) {
        for (Map.Entry<Long, BlockType> e : blocks.entrySet()) {
            long k = e.getKey();
            int x = (int) (k >> 38);
            int z = (int) (k << 26 >> 38);
            int y = (int) (k & 0xFFFL);
            visitor.visit(x, y, z, e.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    public SparseBlockWorld copy() {
        return new SparseBlockWorld((HashMap<Long, BlockType>) blocks.clone());
    }

    public interface Visitor {
        void visit(int x, int y, int z, BlockType type);
    }
}
