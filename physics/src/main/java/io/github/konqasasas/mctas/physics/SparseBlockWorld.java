package io.github.konqasasas.mctas.physics;

import java.util.HashMap;
import java.util.Map;

/**
 * A mutable world that stores only non-air blocks. {@link #copy()} gives an independent world, so a
 * search can branch on block placement later.
 *
 * <p>Lookups go through {@link Index}; the {@code HashMap} is kept beside it only so {@link #forEach}
 * visits blocks in the order it always has.
 */
public final class SparseBlockWorld implements BlockWorld {

    private final HashMap<Long, BlockType> blocks;
    private final Index index;
    private int liquids;
    private int plates;

    public SparseBlockWorld() {
        this.blocks = new HashMap<Long, BlockType>();
        this.index = new Index();
    }

    private SparseBlockWorld(HashMap<Long, BlockType> blocks, Index index, int liquids, int plates) {
        this.blocks = blocks;
        this.index = index;
        this.liquids = liquids;
        this.plates = plates;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    @Override
    public BlockType getBlock(int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return BlockType.AIR;
        }
        // The packed key keeps 26 bits of x and z, so positions that far apart share a block.
        BlockType b = index.get(x << 6 >> 6, y, z << 6 >> 6);
        return b == null ? BlockType.AIR : b;
    }

    public SparseBlockWorld setBlock(int x, int y, int z, BlockType type) {
        if (y < 0 || y >= 256) {
            throw new IllegalArgumentException("y out of build height: " + y);
        }
        long k = key(x, y, z);
        BlockType old = blocks.get(k);
        if (old instanceof VanillaBlocks.Liquid) {
            liquids--;
        }
        if (type instanceof VanillaBlocks.Liquid) {
            liquids++;
        }
        if (old != null && Mechanisms.isPlate(old)) {
            plates--;
        }
        if (Mechanisms.isPlate(type)) {
            plates++;
        }
        if (type == BlockType.AIR) {
            blocks.remove(k);
            index.remove(x << 6 >> 6, y, z << 6 >> 6);
        } else {
            blocks.put(k, type);
            index.put(x << 6 >> 6, y, z << 6 >> 6, type);
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

    @Override
    public boolean mayContainLiquid() {
        return liquids > 0;
    }

    @Override
    public boolean mayContainPlates() {
        return plates > 0;
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
        return new SparseBlockWorld((HashMap<Long, BlockType>) blocks.clone(), index.copy(), liquids, plates);
    }

    /**
     * The blocks again, for lookups: a flat array over the box that holds every block, or 16x16x16
     * sections through an open-addressing table once that box would be too large. Boxed {@code Long}
     * keys of nearby blocks collide in {@code HashMap} (the hash folds x onto z), which made lookups the
     * slowest part of a tick. Most cells a tick reads are air above or around the course, which the box
     * answers without touching memory.
     */
    private static final class Index {

        /** Cells of the flat array at most; past this, sections are used. 4 bytes each. */
        private static final long MAX_CELLS = 1L << 23;
        /** Air added on each side when the box grows, so building a course does not regrow it every block. */
        private static final int MARGIN = 8;

        // Flat array, while cells != null. Index (dx * sizeZ + dz) * sizeY + dy.
        private int minX;
        private int minY;
        private int minZ;
        private int sizeX;
        private int sizeY;
        private int sizeZ;
        private BlockType[] cells;
        // Sections, once cells == null.
        private long[] keys;
        private BlockType[][] sections;
        private int size;

        Index() {
            cells = new BlockType[0];
        }

        private static long sectionKey(int x, int y, int z) {
            return ((long) (x >> 4) & 0x3FFFFFL) << 26 | ((long) (z >> 4) & 0x3FFFFFL) << 4 | (long) (y >> 4);
        }

        private static int within(int x, int y, int z) {
            return (x & 15) << 8 | (z & 15) << 4 | (y & 15);
        }

        private static int slot(long k, int mask) {
            k *= 0x9E3779B97F4A7C15L;
            return (int) (k ^ (k >>> 32)) & mask;
        }

        BlockType get(int x, int y, int z) {
            BlockType[] c = cells;
            if (c != null) {
                int dx = x - minX;
                int dy = y - minY;
                int dz = z - minZ;
                if ((dx | dy | dz | sizeX - 1 - dx | sizeY - 1 - dy | sizeZ - 1 - dz) < 0) {
                    return null;
                }
                return c[(dx * sizeZ + dz) * sizeY + dy];
            }
            BlockType[] sec = section(sectionKey(x, y, z));
            return sec == null ? null : sec[within(x, y, z)];
        }

        void put(int x, int y, int z, BlockType type) {
            if (cells != null) {
                int dx = x - minX;
                int dy = y - minY;
                int dz = z - minZ;
                if ((dx | dy | dz | sizeX - 1 - dx | sizeY - 1 - dy | sizeZ - 1 - dz) >= 0) {
                    cells[(dx * sizeZ + dz) * sizeY + dy] = type;
                    return;
                }
                growBox(x, y, z);
                if (cells != null) {
                    put(x, y, z, type);
                    return;
                }
            }
            long k = sectionKey(x, y, z);
            BlockType[] sec = section(k);
            if (sec == null) {
                sec = addSection(k);
            }
            sec[within(x, y, z)] = type;
        }

        void remove(int x, int y, int z) {
            if (cells != null) {
                int dx = x - minX;
                int dy = y - minY;
                int dz = z - minZ;
                if ((dx | dy | dz | sizeX - 1 - dx | sizeY - 1 - dy | sizeZ - 1 - dz) >= 0) {
                    cells[(dx * sizeZ + dz) * sizeY + dy] = null;
                }
                return;
            }
            // An emptied section stays in the table.
            BlockType[] sec = section(sectionKey(x, y, z));
            if (sec != null) {
                sec[within(x, y, z)] = null;
            }
        }

        /** Grows the box to hold (x, y, z), or moves every block to sections when it would be too large. */
        private void growBox(int x, int y, int z) {
            boolean empty = cells.length == 0;
            int x0 = empty ? x : Math.min(minX, x);
            int y0 = empty ? y : Math.min(minY, y);
            int z0 = empty ? z : Math.min(minZ, z);
            int x1 = empty ? x : Math.max(minX + sizeX - 1, x);
            int y1 = empty ? y : Math.max(minY + sizeY - 1, y);
            int z1 = empty ? z : Math.max(minZ + sizeZ - 1, z);
            long nx = (long) x1 - x0 + 1 + 2 * MARGIN;
            long nz = (long) z1 - z0 + 1 + 2 * MARGIN;
            int ny0 = Math.max(0, y0 - MARGIN);
            int ny1 = Math.min(255, y1 + MARGIN);
            long ny = ny1 - ny0 + 1;
            if (nx * ny * nz > MAX_CELLS) {
                toSections();
                return;
            }
            BlockType[] old = cells;
            int ox = minX;
            int oy = minY;
            int oz = minZ;
            int sx = sizeX;
            int sy = sizeY;
            int sz = sizeZ;
            minX = x0 - MARGIN;
            minY = ny0;
            minZ = z0 - MARGIN;
            sizeX = (int) nx;
            sizeY = (int) ny;
            sizeZ = (int) nz;
            cells = new BlockType[sizeX * sizeY * sizeZ];
            for (int i = 0; i < sx; i++) {
                for (int k = 0; k < sz; k++) {
                    System.arraycopy(old, (i * sz + k) * sy, cells,
                            ((ox + i - minX) * sizeZ + (oz + k - minZ)) * sizeY + (oy - minY), sy);
                }
            }
        }

        private void toSections() {
            BlockType[] old = cells;
            cells = null;
            keys = new long[16];
            sections = new BlockType[16][];
            for (int i = 0; i < sizeX; i++) {
                for (int k = 0; k < sizeZ; k++) {
                    for (int j = 0; j < sizeY; j++) {
                        BlockType t = old[(i * sizeZ + k) * sizeY + j];
                        if (t != null) {
                            put(minX + i, minY + j, minZ + k, t);
                        }
                    }
                }
            }
        }

        private BlockType[] section(long k) {
            long[] ks = keys;
            BlockType[][] ss = sections;
            int mask = ks.length - 1;
            for (int i = slot(k, mask); ; i = (i + 1) & mask) {
                BlockType[] sec = ss[i];
                if (sec == null || ks[i] == k) {
                    return sec;
                }
            }
        }

        private BlockType[] addSection(long k) {
            if ((size + 1) * 2 > keys.length) {
                long[] oldKeys = keys;
                BlockType[][] oldSections = sections;
                keys = new long[oldKeys.length * 2];
                sections = new BlockType[oldKeys.length * 2][];
                for (int j = 0; j < oldKeys.length; j++) {
                    if (oldSections[j] != null) {
                        insert(oldKeys[j], oldSections[j]);
                    }
                }
            }
            BlockType[] sec = new BlockType[4096];
            insert(k, sec);
            size++;
            return sec;
        }

        private void insert(long k, BlockType[] sec) {
            int mask = keys.length - 1;
            int i = slot(k, mask);
            while (sections[i] != null) {
                i = (i + 1) & mask;
            }
            keys[i] = k;
            sections[i] = sec;
        }

        Index copy() {
            Index o = new Index();
            o.minX = minX;
            o.minY = minY;
            o.minZ = minZ;
            o.sizeX = sizeX;
            o.sizeY = sizeY;
            o.sizeZ = sizeZ;
            o.size = size;
            if (cells != null) {
                o.cells = cells.clone();
            } else {
                o.cells = null;
                o.keys = keys.clone();
                o.sections = new BlockType[sections.length][];
                for (int i = 0; i < sections.length; i++) {
                    if (sections[i] != null) {
                        o.sections[i] = sections[i].clone();
                    }
                }
            }
            return o;
        }
    }

    public interface Visitor {
        void visit(int x, int y, int z, BlockType type);
    }
}
