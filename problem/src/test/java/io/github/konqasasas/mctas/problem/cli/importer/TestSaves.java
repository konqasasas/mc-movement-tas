package io.github.konqasasas.mctas.problem.cli.importer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Writes small 1.12.2 saves and schematics in the game's layout, for the importer tests. */
final class TestSaves {

    /** Numeric blocks by position, {@code id << 4 | meta}. */
    final Map<List<Integer>, Integer> blocks = new LinkedHashMap<List<Integer>, Integer>();

    TestSaves set(int x, int y, int z, int id, int meta) {
        List<Integer> k = new ArrayList<Integer>();
        k.add(x);
        k.add(y);
        k.add(z);
        blocks.put(k, id << 4 | meta);
        return this;
    }

    TestSaves fill(int x1, int y1, int z1, int x2, int y2, int z2, int id, int meta) {
        for (int x = x1; x <= x2; x++)
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++)
                    set(x, y, z, id, meta);
        return this;
    }

    /**
     * Writes {@code save/level.dat} (empty) and {@code save/region/*.mca} with every chunk that holds a
     * block. Chunk 0,0 is zlib-compressed (as the game writes), the others gzip.
     */
    void writeSave(Path save) throws IOException {
        Files.createDirectories(save.resolve("region"));
        Files.write(save.resolve("level.dat"), new byte[0]);
        Map<List<Integer>, Map<Integer, byte[][]>> chunks = new HashMap<List<Integer>, Map<Integer, byte[][]>>();
        for (Map.Entry<List<Integer>, Integer> e : blocks.entrySet()) {
            int x = e.getKey().get(0), y = e.getKey().get(1), z = e.getKey().get(2);
            List<Integer> ck = pair(x >> 4, z >> 4);
            Map<Integer, byte[][]> sections = chunks.get(ck);
            if (sections == null) chunks.put(ck, sections = new HashMap<Integer, byte[][]>());
            byte[][] s = sections.get(y >> 4);
            if (s == null) sections.put(y >> 4, s = new byte[][] {new byte[4096], new byte[2048], new byte[2048]});
            int i = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
            int id = e.getValue() >> 4;
            s[0][i] = (byte) id;
            setNibble(s[1], i, id >> 8);
            setNibble(s[2], i, e.getValue() & 15);
        }
        Map<List<Integer>, byte[][]> regions = new HashMap<List<Integer>, byte[][]>();
        for (Map.Entry<List<Integer>, Map<Integer, byte[][]>> c : chunks.entrySet()) {
            int cx = c.getKey().get(0), cz = c.getKey().get(1);
            List<Integer> rk = pair(cx >> 5, cz >> 5);
            byte[][] r = regions.get(rk);
            if (r == null) regions.put(rk, r = new byte[1024][]);
            r[(cx & 31) + (cz & 31) * 32] = chunk(cx, cz, c.getValue(), cx == 0 && cz == 0);
        }
        for (Map.Entry<List<Integer>, byte[][]> r : regions.entrySet()) {
            writeRegion(save.resolve("region").resolve("r." + r.getKey().get(0) + "." + r.getKey().get(1) + ".mca"), r.getValue());
        }
    }

    /** Writes an MCEdit schematic of the box min..max (corner 0,0,0 of the schematic at min). */
    void writeSchematic(Path file, int[] min, int[] max) throws IOException {
        int w = max[0] - min[0] + 1, h = max[1] - min[1] + 1, l = max[2] - min[2] + 1;
        byte[] b = new byte[w * h * l];
        byte[] d = new byte[w * h * l];
        byte[] add = new byte[(w * h * l + 1) / 2];
        boolean anyAdd = false;
        for (Map.Entry<List<Integer>, Integer> e : blocks.entrySet()) {
            int x = e.getKey().get(0) - min[0], y = e.getKey().get(1) - min[1], z = e.getKey().get(2) - min[2];
            int i = (y * l + z) * w + x;
            b[i] = (byte) (e.getValue() >> 4);
            d[i] = (byte) (e.getValue() & 15);
            if (e.getValue() >> 12 != 0) {
                setNibble(add, i, e.getValue() >> 12);
                anyAdd = true;
            }
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("Width", (short) w);
        root.put("Height", (short) h);
        root.put("Length", (short) l);
        root.put("Materials", "Alpha");
        root.put("Blocks", b);
        root.put("Data", d);
        if (anyAdd) root.put("AddBlocks", add);
        root.put("Entities", new ArrayList<Object>());
        OutputStream out = new GZIPOutputStream(Files.newOutputStream(file));
        try {
            writeRoot(new DataOutputStream(out), "Schematic", root);
        } finally {
            out.close();
        }
    }

    private static List<Integer> pair(int a, int b) {
        List<Integer> k = new ArrayList<Integer>();
        k.add(a);
        k.add(b);
        return k;
    }

    private static void setNibble(byte[] a, int i, int v) {
        if ((i & 1) == 0) a[i >> 1] = (byte) (a[i >> 1] & 0xF0 | v & 15);
        else a[i >> 1] = (byte) (a[i >> 1] & 0x0F | (v & 15) << 4);
    }

    private static byte[] chunk(int cx, int cz, Map<Integer, byte[][]> sections, boolean zlib) throws IOException {
        List<Object> list = new ArrayList<Object>();
        for (Map.Entry<Integer, byte[][]> s : sections.entrySet()) {
            Map<String, Object> sec = new LinkedHashMap<String, Object>();
            sec.put("Y", (byte) (int) s.getKey());
            sec.put("Blocks", s.getValue()[0]);
            boolean anyAdd = false;
            for (byte v : s.getValue()[1]) anyAdd |= v != 0;
            if (anyAdd) sec.put("Add", s.getValue()[1]);
            sec.put("Data", s.getValue()[2]);
            sec.put("BlockLight", new byte[2048]);
            sec.put("SkyLight", new byte[2048]);
            list.add(sec);
        }
        Map<String, Object> level = new LinkedHashMap<String, Object>();
        level.put("xPos", cx);
        level.put("zPos", cz);
        level.put("Sections", list);
        level.put("HeightMap", new int[256]);
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("DataVersion", 1343);
        root.put("Level", level);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream out = zlib ? new DeflaterOutputStream(bytes) : new GZIPOutputStream(bytes);
        writeRoot(new DataOutputStream(out), "", root);
        out.close();
        byte[] body = bytes.toByteArray();
        ByteArrayOutputStream withHeader = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(withHeader);
        h.writeInt(body.length + 1);
        h.writeByte(zlib ? 2 : 1);
        h.write(body);
        return withHeader.toByteArray();
    }

    private static void writeRegion(Path file, byte[][] chunks) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] loc = new int[1024];
        int sector = 2;
        for (int i = 0; i < 1024; i++) {
            if (chunks[i] == null) continue;
            int sectors = (chunks[i].length + 4095) / 4096;
            loc[i] = sector << 8 | sectors;
            data.write(chunks[i]);
            data.write(new byte[sectors * 4096 - chunks[i].length]);
            sector += sectors;
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(all);
        for (int l : loc) out.writeInt(l);
        out.write(new byte[4096]);
        out.write(data.toByteArray());
        Files.write(file, all.toByteArray());
    }

    static void writeRoot(DataOutputStream out, String name, Map<String, Object> root) throws IOException {
        out.writeByte(10);
        out.writeUTF(name);
        compound(out, root);
        out.flush();
    }

    @SuppressWarnings("unchecked")
    private static void compound(DataOutputStream out, Map<String, Object> m) throws IOException {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.writeByte(type(e.getValue()));
            out.writeUTF(e.getKey());
            payload(out, e.getValue());
        }
        out.writeByte(0);
    }

    private static int type(Object o) {
        if (o instanceof Byte) return 1;
        if (o instanceof Short) return 2;
        if (o instanceof Integer) return 3;
        if (o instanceof byte[]) return 7;
        if (o instanceof String) return 8;
        if (o instanceof List) return 9;
        if (o instanceof Map) return 10;
        if (o instanceof int[]) return 11;
        throw new IllegalArgumentException(String.valueOf(o));
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Object o) throws IOException {
        if (o instanceof Byte) out.writeByte((Byte) o);
        else if (o instanceof Short) out.writeShort((Short) o);
        else if (o instanceof Integer) out.writeInt((Integer) o);
        else if (o instanceof byte[]) {
            out.writeInt(((byte[]) o).length);
            out.write((byte[]) o);
        } else if (o instanceof String) out.writeUTF((String) o);
        else if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            out.writeByte(l.isEmpty() ? 0 : type(l.get(0)));
            out.writeInt(l.size());
            for (Object e : l) payload(out, e);
        } else if (o instanceof Map) compound(out, (Map<String, Object>) o);
        else if (o instanceof int[]) {
            out.writeInt(((int[]) o).length);
            for (int v : (int[]) o) out.writeInt(v);
        }
    }
}
