package io.github.konqasasas.mctas.problem.cli.importer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * A 1.12.2 save in the Anvil format: {@code region/r.X.Z.mca} files of 32×32 chunks, each chunk a
 * compressed NBT tree whose {@code Level.Sections} hold 16×16×16 blocks as {@code Blocks} (low 8 bits of
 * the id), {@code Add} (high 4 bits, optional) and {@code Data} (metadata), indexed {@code y << 8 | z << 4 | x}.
 * Chunks are read on first use and kept.
 */
final class AnvilWorld implements BlockSource {

    private final Path regionDir;
    private final String description;
    private final Map<Long, Chunk> chunks = new HashMap<Long, Chunk>();

    /**
     * @param save      the save folder (the one with {@code level.dat}), or its {@code region} folder
     * @param dimension 0 for the overworld, -1 for the nether, 1 for the end
     */
    AnvilWorld(Path save, int dimension) throws IOException {
        Path dir;
        if (Files.isDirectory(save.resolve("region")) || Files.isRegularFile(save.resolve("level.dat"))) {
            dir = dimension == 0 ? save.resolve("region") : save.resolve("DIM" + dimension).resolve("region");
        } else if (dimension == 0 && save.getFileName() != null && save.getFileName().toString().equals("region")) {
            dir = save;
        } else {
            throw new IOException("not a save folder (no level.dat or region/): " + save);
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException("no region folder for dimension " + dimension + ": " + dir);
        }
        this.regionDir = dir;
        this.description = save.toString();
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public int get(int x, int y, int z) throws IOException {
        if (y < 0 || y >= 256) {
            return 0;
        }
        Chunk c = chunk(x >> 4, z >> 4);
        if (c == null) {
            return -1;
        }
        return c.get(x & 15, y, z & 15);
    }

    private Chunk chunk(int cx, int cz) throws IOException {
        long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
        if (chunks.containsKey(key)) {
            return chunks.get(key);
        }
        Chunk c = readChunk(cx, cz);
        chunks.put(key, c);
        return c;
    }

    private Chunk readChunk(int cx, int cz) throws IOException {
        Path file = regionDir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        byte[] data;
        int compression;
        RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
        try {
            raf.seek(4L * ((cx & 31) + (cz & 31) * 32));
            int loc = raf.readInt();
            int sector = loc >>> 8;
            if (sector == 0 || (loc & 0xFF) == 0) {
                return null;
            }
            raf.seek(sector * 4096L);
            int length = raf.readInt();
            if (length <= 1 || length > (loc & 0xFF) * 4096) {
                throw new IOException("bad chunk length in " + file + " for chunk " + cx + "," + cz);
            }
            compression = raf.readUnsignedByte();
            data = new byte[length - 1];
            raf.readFully(data);
        } finally {
            raf.close();
        }
        InputStream raw = new ByteArrayInputStream(data);
        InputStream in;
        if (compression == 1) {
            in = new GZIPInputStream(raw);
        } else if (compression == 2) {
            in = new InflaterInputStream(raw);
        } else {
            throw new IOException("chunk " + cx + "," + cz + " uses compression " + compression
                + ", which 1.12.2 does not write (a newer save?)");
        }
        Map<String, Object> root;
        DataInputStream din = new DataInputStream(in);
        try {
            root = Nbt.readRoot(din);
        } finally {
            din.close();
        }
        return Chunk.of(Nbt.compound(root, "Level"), cx, cz);
    }

    /** The sections of one chunk, by section y (0 to 15); null entries are all air. */
    private static final class Chunk {
        final byte[][] blocks = new byte[16][];
        final byte[][] add = new byte[16][];
        final byte[][] meta = new byte[16][];

        @SuppressWarnings("unchecked")
        static Chunk of(Map<String, Object> level, int cx, int cz) throws IOException {
            Chunk c = new Chunk();
            Object sections = level.get("Sections");
            if (!(sections instanceof List)) {
                return c;
            }
            for (Object o : (List<Object>) sections) {
                Map<String, Object> s = (Map<String, Object>) o;
                if (!s.containsKey("Blocks")) {
                    if (s.containsKey("Palette") || s.containsKey("BlockStates")) {
                        throw new IOException("chunk " + cx + "," + cz + " is in the 1.13+ format; only 1.12.2 saves are supported");
                    }
                    continue;
                }
                int sy = Nbt.integer(s, "Y");
                if (sy < 0 || sy > 15) {
                    continue;
                }
                byte[] b = Nbt.bytes(s, "Blocks", true);
                byte[] d = Nbt.bytes(s, "Data", true);
                byte[] a = Nbt.bytes(s, "Add", false);
                if (b.length != 4096 || d.length != 2048 || (a != null && a.length != 2048)) {
                    throw new IOException("chunk " + cx + "," + cz + " section " + sy + " has arrays of the wrong size");
                }
                c.blocks[sy] = b;
                c.meta[sy] = d;
                c.add[sy] = a;
            }
            return c;
        }

        int get(int x, int y, int z) {
            int sy = y >> 4;
            byte[] b = blocks[sy];
            if (b == null) {
                return 0;
            }
            int i = (y & 15) << 8 | z << 4 | x;
            int id = b[i] & 0xFF;
            if (add[sy] != null) {
                id |= nibble(add[sy], i) << 8;
            }
            return id << 4 | nibble(meta[sy], i);
        }
    }

    /** {@code NibbleArray.get}: the low half of the byte for even indices. */
    static int nibble(byte[] a, int i) {
        int v = a[i >> 1];
        return (i & 1) == 0 ? v & 15 : v >> 4 & 15;
    }
}
