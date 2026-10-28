package io.github.konqasasas.mctas.problem.cli.importer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * An MCEdit / WorldEdit {@code .schematic} (the 1.12 format, {@code Materials: "Alpha"}): {@code Blocks},
 * {@code Data} and an optional {@code AddBlocks}, indexed {@code (y * Length + z) * Width + x}. The
 * schematic's corner (0, 0, 0) is placed at {@code origin} in world coordinates; outside the schematic
 * there is no data.
 */
final class Schematic implements BlockSource {

    final int width;
    final int height;
    final int length;
    private final byte[] blocks;
    private final byte[] data;
    private final byte[] add;
    private final int ox;
    private final int oy;
    private final int oz;
    private final String description;

    Schematic(Path file, int[] origin) throws IOException {
        Map<String, Object> root;
        DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))));
        try {
            root = Nbt.readRoot(in);
        } finally {
            in.close();
        }
        if (root.containsKey("Palette") || root.containsKey("BlockData")) {
            throw new IOException("this is a Sponge schematic (.schem, 1.13+); only the 1.12 .schematic format is supported");
        }
        Object materials = root.get("Materials");
        if (materials != null && !"Alpha".equals(materials)) {
            throw new IOException("schematic Materials is \"" + materials + "\", expected \"Alpha\"");
        }
        width = Nbt.integer(root, "Width");
        height = Nbt.integer(root, "Height");
        length = Nbt.integer(root, "Length");
        blocks = Nbt.bytes(root, "Blocks", true);
        data = Nbt.bytes(root, "Data", true);
        add = Nbt.bytes(root, "AddBlocks", false);
        int n = width * height * length;
        if (width <= 0 || height <= 0 || length <= 0 || blocks.length != n || data.length != n) {
            throw new IOException("schematic arrays do not match its size " + width + "x" + height + "x" + length);
        }
        ox = origin[0];
        oy = origin[1];
        oz = origin[2];
        description = file.toString();
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public int get(int x, int y, int z) {
        int lx = x - ox;
        int ly = y - oy;
        int lz = z - oz;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= width || ly >= height || lz >= length) {
            return -1;
        }
        int i = (ly * length + lz) * width + lx;
        int id = blocks[i] & 0xFF;
        if (add != null && (i >> 1) < add.length) {
            // WorldEdit's MCEditSchematicReader: the low nibble for even indices.
            id |= AnvilWorld.nibble(add, i) << 8;
        }
        return id << 4 | (data[i] & 15);
    }

    int[] min() {
        return new int[] {ox, oy, oz};
    }

    int[] max() {
        return new int[] {ox + width - 1, oy + height - 1, oz + length - 1};
    }
}
