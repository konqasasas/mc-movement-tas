package io.github.konqasasas.mctas.problem.cli.importer;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A reader for Minecraft's NBT, enough for chunks and schematics. Compounds become {@code Map<String, Object>},
 * lists {@code List<Object>}, arrays {@code byte[]}, {@code int[]} and {@code long[]}, numbers their boxed
 * types and strings {@code String}.
 */
final class Nbt {
    private Nbt() {
    }

    /** Reads the root tag (a named compound) from an uncompressed stream. */
    static Map<String, Object> readRoot(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type != 10) {
            throw new IOException("NBT root is not a compound (tag " + type + ")");
        }
        in.readUTF();
        return compound(in, 0);
    }

    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 512) {
            throw new IOException("NBT nested too deeply");
        }
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] a = new byte[length(in)];
                in.readFully(a);
                return a;
            }
            case 8: return in.readUTF();
            case 9: {
                int elem = in.readUnsignedByte();
                int n = length(in);
                List<Object> list = new ArrayList<Object>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) {
                    list.add(payload(in, elem, depth + 1));
                }
                return list;
            }
            case 10: return compound(in, depth + 1);
            case 11: {
                int[] a = new int[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default:
                throw new IOException("unknown NBT tag " + type);
        }
    }

    private static Map<String, Object> compound(DataInputStream in, int depth) throws IOException {
        Map<String, Object> map = new HashMap<String, Object>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, payload(in, type, depth));
        }
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 64 * 1024 * 1024) {
            throw new IOException("bad NBT length " + n);
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Map<String, Object> m, String key) throws IOException {
        Object o = m.get(key);
        if (!(o instanceof Map)) {
            throw new IOException("NBT: missing compound " + key);
        }
        return (Map<String, Object>) o;
    }

    static byte[] bytes(Map<String, Object> m, String key, boolean required) throws IOException {
        Object o = m.get(key);
        if (o instanceof byte[]) {
            return (byte[]) o;
        }
        if (required) {
            throw new IOException("NBT: missing byte array " + key);
        }
        return null;
    }

    static int integer(Map<String, Object> m, String key) throws IOException {
        Object o = m.get(key);
        if (!(o instanceof Number)) {
            throw new IOException("NBT: missing number " + key);
        }
        return ((Number) o).intValue();
    }
}
