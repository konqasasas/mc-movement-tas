package io.github.konqasasas.mctas.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Blocks placed on top of a base world, as an immutable list that shares its tail: adding a block is
 * O(1) and never copies, so states of a search can branch freely. Each position appears at most once
 * (a placed full block is not replaceable). Iterates in the order the blocks were placed.
 */
public final class WorldDelta {

    public static final WorldDelta EMPTY = new WorldDelta();

    public final int x;
    public final int y;
    public final int z;
    /** The placed block, or null for {@link #EMPTY}. */
    public final BlockType type;
    private final WorldDelta prev;
    private final int size;
    private final long hash;
    // Bounds of every position in this list, so lookups far from the placed blocks stop at once.
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    private WorldDelta() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.type = null;
        this.prev = null;
        this.size = 0;
        this.hash = 0L;
        this.minX = Integer.MAX_VALUE;
        this.minY = Integer.MAX_VALUE;
        this.minZ = Integer.MAX_VALUE;
        this.maxX = Integer.MIN_VALUE;
        this.maxY = Integer.MIN_VALUE;
        this.maxZ = Integer.MIN_VALUE;
    }

    private WorldDelta(WorldDelta prev, int x, int y, int z, BlockType type) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.prev = prev;
        this.size = prev.size + 1;
        this.hash = prev.hash + entryHash(x, y, z, type);
        this.minX = Math.min(prev.minX, x);
        this.minY = Math.min(prev.minY, y);
        this.minZ = Math.min(prev.minZ, z);
        this.maxX = Math.max(prev.maxX, x);
        this.maxY = Math.max(prev.maxY, y);
        this.maxZ = Math.max(prev.maxZ, z);
    }

    /** This list with one more block. */
    public WorldDelta with(int x, int y, int z, BlockType type) {
        if (get(x, y, z) != null) {
            throw new IllegalArgumentException("a block was already placed at " + x + " " + y + " " + z);
        }
        return new WorldDelta(this, x, y, z, type);
    }

    /** The block placed at (x, y, z), or null if none was. */
    public BlockType get(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return null;
        }
        for (WorldDelta d = this; d.size > 0; d = d.prev) {
            if (d.x == x && d.y == y && d.z == z) {
                return d.type;
            }
        }
        return null;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** The placed blocks, oldest first. Each element is one node, read through {@link #x}, {@link #y}, {@link #z}, {@link #type}. */
    public List<WorldDelta> entries() {
        List<WorldDelta> out = new ArrayList<WorldDelta>(size);
        for (WorldDelta d = this; d.size > 0; d = d.prev) {
            out.add(d);
        }
        Collections.reverse(out);
        return out;
    }

    /** Order-independent: two lists with the same blocks hash alike whatever order they were placed in. */
    public long contentHash() {
        return hash;
    }

    /** Same blocks at the same positions, in any order. */
    public boolean sameBlocks(WorldDelta o) {
        if (this == o) {
            return true;
        }
        if (size != o.size || hash != o.hash) {
            return false;
        }
        for (WorldDelta d = this; d.size > 0; d = d.prev) {
            if (o.get(d.x, d.y, d.z) != d.type) {
                return false;
            }
        }
        return true;
    }

    /** {@code base} with these blocks on top. */
    public BlockWorld over(final BlockWorld base) {
        if (size == 0) {
            return base;
        }
        return new BlockWorld() {
            @Override
            public BlockType getBlock(int bx, int by, int bz) {
                BlockType t = get(bx, by, bz);
                return t != null ? t : base.getBlock(bx, by, bz);
            }
        };
    }

    private static long entryHash(int x, int y, int z, BlockType type) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ type.name().hashCode();
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }
}
