package io.github.konqasasas.mctas.problem.world;

import java.util.HashMap;
import java.util.Map;

public final class BlockWorld {
    private final Map<Long, BlockType> blocks = new HashMap<Long, BlockType>();

    public BlockType get(int x, int y, int z) {
        BlockType t = blocks.get(key(x, y, z));
        return t == null ? BlockType.AIR : t;
    }

    public void set(int x, int y, int z, BlockType type) {
        if (type == BlockType.AIR) blocks.remove(key(x, y, z));
        else blocks.put(key(x, y, z), type);
    }

    public void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockType type) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                    set(x, y, z, type);
    }

    public boolean isSolid(int x, int y, int z) {
        return get(x, y, z).fullCube;
    }

    public int size() {
        return blocks.size();
    }

    public BlockWorld copy() {
        BlockWorld w = new BlockWorld();
        w.blocks.putAll(blocks);
        return w;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }
}
