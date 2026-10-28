package io.github.konqasasas.mctas.problem.cli.importer;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.Spawn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes a world as a problem file with inline fills, and the checks and helpers shared by the save
 * importer and the recorder mod's {@code /mctas export}.
 */
public final class ProblemWriter {

    private ProblemWriter() {
    }

    /** One {@code fills} entry: both corners included. */
    public static final class Fill {
        public final int[] from;
        public final int[] to;
        public final BlockType block;

        Fill(int[] from, int[] to, BlockType block) {
            this.from = from;
            this.to = to;
            this.block = block;
        }
    }

    /**
     * The world's blocks as boxes of one block each: runs along x, grown along z, then along y, in a
     * fixed order so the same world always gives the same file.
     */
    public static List<Fill> fills(SparseBlockWorld world) {
        final List<long[]> keys = new ArrayList<long[]>();
        world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                keys.add(new long[] {y, z, x});
            }
        });
        java.util.Collections.sort(keys, new java.util.Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                for (int i = 0; i < 3; i++) {
                    if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
                }
                return 0;
            }
        });
        Set<Long> used = new HashSet<Long>();
        List<Fill> out = new ArrayList<Fill>();
        for (long[] k : keys) {
            int y = (int) k[0];
            int z = (int) k[1];
            int x = (int) k[2];
            if (used.contains(key(x, y, z))) continue;
            BlockType t = world.getBlock(x, y, z);
            int x2 = x;
            while (same(world, used, x2 + 1, y, z, t)) x2++;
            int z2 = z;
            while (rowSame(world, used, x, x2, y, z2 + 1, t)) z2++;
            int y2 = y;
            while (layerSame(world, used, x, x2, y2 + 1, z, z2, t)) y2++;
            for (int yy = y; yy <= y2; yy++) {
                for (int zz = z; zz <= z2; zz++) {
                    for (int xx = x; xx <= x2; xx++) {
                        used.add(key(xx, yy, zz));
                    }
                }
            }
            out.add(new Fill(new int[] {x, y, z}, new int[] {x2, y2, z2}, t));
        }
        return out;
    }

    private static boolean same(SparseBlockWorld w, Set<Long> used, int x, int y, int z, BlockType t) {
        return y >= 0 && y < 256 && w.getBlock(x, y, z) == t && !used.contains(key(x, y, z));
    }

    private static boolean rowSame(SparseBlockWorld w, Set<Long> used, int x1, int x2, int y, int z, BlockType t) {
        for (int x = x1; x <= x2; x++) {
            if (!same(w, used, x, y, z, t)) return false;
        }
        return true;
    }

    private static boolean layerSame(SparseBlockWorld w, Set<Long> used, int x1, int x2, int y, int z1, int z2, BlockType t) {
        for (int z = z1; z <= z2; z++) {
            if (!rowSame(w, used, x1, x2, y, z, t)) return false;
        }
        return true;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    /**
     * The problem file (format version 1). {@code imported} is a JSON object describing where the world
     * came from, written as {@code world.imported}, which readers ignore; null to leave it out.
     */
    public static String problemJson(String id, String title, SparseBlockWorld world, String imported,
                                     Spawn spawn, Region start, Region goal, int maxTicks, double voidY) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": \"").append(Problem.FORMAT).append("\",\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"id\": ").append(quote(id)).append(",\n");
        if (title != null) {
            sb.append("  \"title\": ").append(quote(title)).append(",\n");
        }
        sb.append("  \"mcVersion\": \"").append(Problem.MC_VERSION).append("\",\n");
        sb.append("  \"world\": {\n    \"source\": \"inline\",\n");
        if (imported != null) {
            sb.append("    \"imported\": ").append(imported).append(",\n");
        }
        sb.append("    \"fills\": [");
        List<Fill> fills = fills(world);
        for (int i = 0; i < fills.size(); i++) {
            Fill f = fills.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("      {\"from\": ").append(ints(f.from));
            if (!Arrays.equals(f.from, f.to)) {
                sb.append(", \"to\": ").append(ints(f.to));
            }
            sb.append(", \"block\": ").append(quote(f.block.name())).append('}');
        }
        sb.append(fills.isEmpty() ? "]\n" : "\n    ]\n");
        sb.append("  },\n");
        sb.append("  \"spawn\": {\"pos\": [").append(num(spawn.pos.x)).append(", ").append(num(spawn.pos.y)).append(", ")
            .append(num(spawn.pos.z)).append("], \"yaw\": ").append(num(spawn.yaw)).append(", \"pitch\": ")
            .append(num(spawn.pitch)).append("},\n");
        sb.append("  \"start\": ").append(region(start)).append(",\n");
        sb.append("  \"goal\": ").append(region(goal)).append(",\n");
        sb.append("  \"limits\": {\"maxTicks\": ").append(maxTicks).append(", \"voidY\": ").append(num(voidY)).append("}\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String region(Region r) {
        return "{\"min\": [" + num(r.minX) + ", " + num(r.minY) + ", " + num(r.minZ) + "], \"max\": ["
            + num(r.maxX) + ", " + num(r.maxY) + ", " + num(r.maxZ) + "]}";
    }

    private static String ints(int[] a) {
        return "[" + a[0] + ", " + a[1] + ", " + a[2] + "]";
    }

    /** The shortest decimal that reads back as the same double ({@link Double#toString}). */
    static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("not a finite number: " + d);
        }
        return Double.toString(d);
    }

    /** As {@link #num(double)}, for a float the reader parses with {@code getAsFloat}. */
    static String num(float f) {
        return Float.toString(f);
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /**
     * The collision boxes of the block at a position, in world coordinates. A fence's box reaches 1.5
     * blocks up, and stairs, fences and panes take their shape from the neighbours.
     */
    public static List<Aabb> boxes(SparseBlockWorld world, int x, int y, int z) {
        List<Aabb> out = new ArrayList<Aabb>();
        Aabb area = new Aabb(x - 1, y - 1, z - 1, x + 2, y + 3, z + 2);
        world.getBlock(x, y, z).addCollisionBoxes(world, x, y, z, area, out);
        return out;
    }

    /** Feet at the middle of the top of a block's collision boxes, or null if the block has none. */
    public static double[] standOn(SparseBlockWorld world, int x, int y, int z) {
        List<Aabb> boxes = boxes(world, x, y, z);
        if (boxes.isEmpty()) return null;
        double top = Double.NEGATIVE_INFINITY;
        for (Aabb b : boxes) top = Math.max(top, b.maxY);
        return new double[] {x + 0.5D, top, z + 0.5D};
    }

    /**
     * The problem set's landing goal on a block (docs/problem-format.md 4): {@code y} exactly the top of its
     * collision boxes, and horizontally their extent grown by 0.3. Null if the block has no collision box.
     */
    public static Region landingOn(SparseBlockWorld world, int x, int y, int z) {
        List<Aabb> boxes = boxes(world, x, y, z);
        if (boxes.isEmpty()) return null;
        double top = Double.NEGATIVE_INFINITY;
        double x1 = Double.POSITIVE_INFINITY, z1 = Double.POSITIVE_INFINITY;
        double x2 = Double.NEGATIVE_INFINITY, z2 = Double.NEGATIVE_INFINITY;
        for (Aabb b : boxes) {
            top = Math.max(top, b.maxY);
            x1 = Math.min(x1, b.minX);
            z1 = Math.min(z1, b.minZ);
            x2 = Math.max(x2, b.maxX);
            z2 = Math.max(z2, b.maxZ);
        }
        return new Region(x1 - 0.3D, top, z1 - 0.3D, x2 + 0.3D, top, z2 + 0.3D);
    }

    /**
     * Problems with the spawn, worst first: an error string starting with {@code "error: "} if the player's
     * box overlaps a block there (D-007), a warning if a player left standing there does not stay put
     * for a tick (the judge assumes it does).
     */
    public static List<String> checkSpawn(SparseBlockWorld world, Spawn spawn) {
        List<String> out = new ArrayList<String>();
        double x = spawn.pos.x, y = spawn.pos.y, z = spawn.pos.z;
        Aabb player = new Aabb(x - 0.3D, y, z - 0.3D, x + 0.3D, y + 1.8D, z + 0.3D);
        for (int bx = (int) Math.floor(x - 1.3D); bx <= (int) Math.floor(x + 1.3D); bx++) {
            for (int by = (int) Math.floor(y) - 1; by <= (int) Math.floor(y + 1.8D); by++) {
                for (int bz = (int) Math.floor(z - 1.3D); bz <= (int) Math.floor(z + 1.3D); bz++) {
                    for (Aabb b : boxes(world, bx, by, bz)) {
                        if (b.intersects(player)) {
                            out.add("error: the player at the spawn overlaps " + world.getBlock(bx, by, bz).name()
                                + " at " + bx + "," + by + "," + bz + " (a run may not start inside a block, D-007)");
                            return out;
                        }
                    }
                }
            }
        }
        PlayerState s = PlayerState.standing(x, y, z, spawn.yaw);
        s.rotationPitch = spawn.pitch;
        PlayerState next = Physics.tick(world, s, new PlayerInput(0, spawn.yaw, spawn.pitch));
        if (next.posX != x || next.posY != y || next.posZ != z || !next.onGround) {
            out.add("warning: a player standing still at the spawn does not stay there (after one tick: "
                + next.posX + ", " + next.posY + ", " + next.posZ + ", onGround=" + next.onGround
                + "); the spawn should be on top of a block");
        }
        return out;
    }

    /** Counts of the blocks in a world that the engine keeps but the real game would not (L-005, D-005). */
    public static List<String> blockWarnings(SparseBlockWorld world) {
        final Map<String, Integer> unstable = new HashMap<String, Integer>();
        final Map<String, Integer> damage = new HashMap<String, Integer>();
        world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                String id = type.name().indexOf('[') < 0 ? type.name() : type.name().substring(0, type.name().indexOf('['));
                if (type.isExcept(BlockType.UNSTABLE)) {
                    unstable.put(id, unstable.containsKey(id) ? unstable.get(id) + 1 : 1);
                }
                if (id.equals("minecraft:cactus") || id.equals("minecraft:magma") || id.equals("minecraft:fire")
                        || id.equals("minecraft:lava") || id.equals("minecraft:flowing_lava")) {
                    damage.put(id, damage.containsKey(id) ? damage.get(id) + 1 : 1);
                }
            }
        });
        List<String> out = new ArrayList<String>();
        if (!unstable.isEmpty()) {
            out.add("warning: blocks that change by themselves in the real game (falling, growing, rails that reshape;"
                + " the engine keeps them as written): " + new java.util.TreeMap<String, Integer>(unstable));
        }
        if (!damage.isEmpty()) {
            out.add("warning: blocks that hurt the player (the engine does not model damage, D-005): "
                + new java.util.TreeMap<String, Integer>(damage));
        }
        return out;
    }
}
