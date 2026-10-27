package io.github.konqasasas.mctas.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * A world stored as a flat array over a fixed box, with air outside it. Faster to read than
 * {@link SparseBlockWorld}; meant for search, where the course has known bounds.
 */
public final class DenseBlockWorld implements BlockWorld {

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<BlockType> palette;
    private final byte[] cells;

    private DenseBlockWorld(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                            List<BlockType> palette, byte[] cells) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.cells = cells;
    }

    /** Copies {@code source} into a box that holds all its blocks plus {@code margin} air on each side. */
    public static DenseBlockWorld of(SparseBlockWorld source, int margin) {
        final int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        source.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                b[0] = Math.min(b[0], x);
                b[1] = Math.min(b[1], y);
                b[2] = Math.min(b[2], z);
                b[3] = Math.max(b[3], x);
                b[4] = Math.max(b[4], y);
                b[5] = Math.max(b[5], z);
            }
        });
        if (source.size() == 0) {
            b[0] = b[1] = b[2] = b[3] = b[4] = b[5] = 0;
        }
        int y0 = Math.max(0, b[1] - margin);
        int y1 = Math.min(255, b[4] + margin);
        final DenseBlockWorld w = new DenseBlockWorld(b[0] - margin, y0, b[2] - margin,
                b[3] - b[0] + 1 + 2 * margin, y1 - y0 + 1, b[5] - b[2] + 1 + 2 * margin,
                new ArrayList<BlockType>(), null);
        final DenseBlockWorld filled = new DenseBlockWorld(w.minX, w.minY, w.minZ, w.sizeX, w.sizeY, w.sizeZ,
                w.palette, new byte[w.sizeX * w.sizeY * w.sizeZ]);
        filled.palette.add(BlockType.AIR);
        source.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                filled.setBlock(x, y, z, type);
            }
        });
        return filled;
    }

    @Override
    public BlockType getBlock(int x, int y, int z) {
        int dx = x - minX;
        int dy = y - minY;
        int dz = z - minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) {
            return BlockType.AIR;
        }
        return palette.get(cells[(dx * sizeZ + dz) * sizeY + dy]);
    }

    /** Sets a block inside the box. Throws when (x, y, z) is outside it. */
    public DenseBlockWorld setBlock(int x, int y, int z, BlockType type) {
        int dx = x - minX;
        int dy = y - minY;
        int dz = z - minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) {
            throw new IllegalArgumentException("outside the world box: " + x + ", " + y + ", " + z);
        }
        int id = palette.indexOf(type);
        if (id < 0) {
            if (palette.size() >= 127) {
                throw new IllegalStateException("too many block types");
            }
            palette.add(type);
            id = palette.size() - 1;
        }
        cells[(dx * sizeZ + dz) * sizeY + dy] = (byte) id;
        return this;
    }

    /** An independent copy. */
    public DenseBlockWorld copy() {
        return new DenseBlockWorld(minX, minY, minZ, sizeX, sizeY, sizeZ, new ArrayList<BlockType>(palette), cells.clone());
    }
}
