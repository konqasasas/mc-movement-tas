package io.github.konqasasas.mctas.problem.cli.importer;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.Spawn;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.io.ProblemFormatException;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.world.Blocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code mctas-problem import}: cuts a box out of a 1.12.2 save (or a {@code .schematic}) and writes it as a
 * problem file with inline fills. Everything outside the box is air in the problem.
 */
public final class WorldImport {

    public static final String USAGE = String.join("\n",
        "  mctas-problem import <save-folder|file.schematic> -o <out.problem.json> --id <id>",
        "        --from x,y,z --to x,y,z            the box to cut, corners included (a schematic defaults to all of it)",
        "        --spawn x,y,z[,yaw[,pitch]] | --spawn-on x,y,z [--yaw a] [--pitch b]",
        "        --start x1,y1,z1,x2,y2,z2",
        "        --goal x1,y1,z1,x2,y2,z2 | --goal-on x,y,z",
        "        [--title t] [--dimension 0|-1|1] [--origin x,y,z (schematic)] [--max-ticks n] [--void-y y]",
        "        [--unsupported-as-air]");

    /** At most this many blocks are cut, so a typo in the box does not read a whole world. */
    static final long MAX_VOLUME = 16L * 1024 * 1024;

    private WorldImport() {
    }

    /** What a cut produced: the world, and the messages about it. */
    static final class Cut {
        final SparseBlockWorld world = new SparseBlockWorld();
        final List<String> errors = new ArrayList<String>();
        final List<String> warnings = new ArrayList<String>();
    }

    /**
     * Reads every block of the box from the source. Blocks the problem format cannot hold are errors, or
     * air with a warning when {@code unsupportedAsAir}.
     */
    static Cut cut(BlockSource src, int[] min, int[] max, boolean unsupportedAsAir) throws IOException {
        long volume = (long) (max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1);
        if (volume > MAX_VOLUME) {
            throw new IllegalArgumentException("the box holds " + volume + " blocks, more than " + MAX_VOLUME);
        }
        Cut cut = new Cut();
        Map<String, int[]> unsupported = new LinkedHashMap<String, int[]>();
        long noData = 0;
        int[] firstNoData = null;
        long asAir = 0;
        int[] firstAsAir = null;
        for (int y = min[1]; y <= max[1]; y++) {
            for (int z = min[2]; z <= max[2]; z++) {
                for (int x = min[0]; x <= max[0]; x++) {
                    int v = src.get(x, y, z);
                    if (v < 0) {
                        if (noData++ == 0) firstNoData = new int[] {x, y, z};
                        continue;
                    }
                    int id = v >> 4;
                    if (id == 0) continue;
                    if (LegacyBlocks.name(id) != null && !LegacyBlocks.stored(id, v & 15)) {
                        if (asAir++ == 0) firstAsAir = new int[] {x, y, z, id, v & 15};
                        continue;
                    }
                    BlockType t;
                    try {
                        t = Blocks.byId(name(src, x, y, z, id, v & 15));
                    } catch (IllegalArgumentException e) {
                        String key = e.getMessage();
                        int[] seen = unsupported.get(key);
                        if (seen == null) unsupported.put(key, new int[] {1, x, y, z});
                        else seen[0]++;
                        continue;
                    }
                    if (!t.isAir()) {
                        cut.world.setBlock(x, y, z, t);
                    }
                }
            }
        }
        for (Map.Entry<String, int[]> e : unsupported.entrySet()) {
            int[] s = e.getValue();
            String msg = e.getKey() + " (" + s[0] + " block" + (s[0] == 1 ? "" : "s") + ", first at " + s[1] + "," + s[2] + "," + s[3] + ")";
            if (unsupportedAsAir) cut.warnings.add("warning: read as air: " + msg);
            else cut.errors.add("error: " + msg);
        }
        if (asAir > 0) {
            cut.warnings.add("warning: " + asAir + " blocks have metadata the game loads as air, first "
                + LegacyBlocks.name(firstAsAir[3]) + " with metadata " + firstAsAir[4] + " at " + firstAsAir[0] + ","
                + firstAsAir[1] + "," + firstAsAir[2] + "; read as air");
        }
        if (noData > 0) {
            cut.warnings.add("warning: " + noData + " positions in the box have no data (chunks never generated, or outside"
                + " the schematic), first at " + firstNoData[0] + "," + firstNoData[1] + "," + firstNoData[2] + "; read as air");
        }
        String beyond = beyond(src, min, max);
        if (beyond != null) {
            cut.warnings.add("warning: blocks continue just outside the box on the " + beyond
                + " side(s); the problem has air there");
        }
        return cut;
    }

    /** The state name of a stored block; a door half is completed from the other half. */
    static String name(BlockSource src, int x, int y, int z, int id, int meta) throws IOException {
        if (!LegacyBlocks.isDoor(id)) {
            return LegacyBlocks.state(id, meta);
        }
        int other = src.get(x, (meta & 8) == 0 ? y + 1 : y - 1, z);
        if (other < 0 || !LegacyBlocks.stored(other >> 4, other & 15)) {
            return LegacyBlocks.state(id, meta);
        }
        return LegacyBlocks.door(id, meta, other >> 4, other & 15);
    }

    /** The faces of the box with non-air blocks right outside, such as {@code "-x, +y"}, or null. */
    private static String beyond(BlockSource src, int[] min, int[] max) throws IOException {
        StringBuilder sb = new StringBuilder();
        String[] names = {"x", "y", "z"};
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                int fixed = side == 0 ? min[axis] - 1 : max[axis] + 1;
                if (axis == 1 && (fixed < 0 || fixed > 255)) continue;
                int a = (axis + 1) % 3;
                int b = (axis + 2) % 3;
                boolean found = false;
                for (int i = min[a]; i <= max[a] && !found; i++) {
                    for (int j = min[b]; j <= max[b] && !found; j++) {
                        int[] p = new int[3];
                        p[axis] = fixed;
                        p[a] = i;
                        p[b] = j;
                        found = src.get(p[0], p[1], p[2]) > 15;
                    }
                }
                if (found) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(side == 0 ? '-' : '+').append(names[axis]);
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Runs the command; returns the exit code (0 written, 1 refused, 2 bad arguments). */
    public static int run(String[] args) throws IOException {
        Map<String, String> opt = new LinkedHashMap<String, String>();
        String input = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--unsupported-as-air")) {
                opt.put(a, "");
            } else if (a.startsWith("--") || a.equals("-o")) {
                if (i + 1 >= args.length) return usage("missing value for " + a);
                opt.put(a.equals("-o") ? "--out" : a, args[++i]);
            } else if (input == null) {
                input = a;
            } else {
                return usage("unexpected argument " + a);
            }
        }
        if (input == null || !opt.containsKey("--out") || !opt.containsKey("--id")) {
            return usage("the input, -o and --id are required");
        }
        try {
            return run(Paths.get(input), opt);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int run(Path input, Map<String, String> opt) throws IOException {
        for (String k : opt.keySet()) {
            if (!java.util.Arrays.asList("--out", "--id", "--from", "--to", "--spawn", "--spawn-on", "--yaw", "--pitch",
                    "--start", "--goal", "--goal-on", "--title", "--dimension", "--origin", "--max-ticks", "--void-y",
                    "--unsupported-as-air").contains(k)) {
                throw new IllegalArgumentException("unknown option " + k);
            }
        }
        BlockSource src;
        int[] min;
        int[] max;
        String imported;
        boolean schematic = input.getFileName() != null && input.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".schematic");
        if (schematic) {
            int[] origin = opt.containsKey("--origin") ? ints(opt.get("--origin"), 3, "--origin") : new int[3];
            Schematic s = new Schematic(input, origin);
            src = s;
            min = opt.containsKey("--from") ? ints(opt.get("--from"), 3, "--from") : s.min();
            max = opt.containsKey("--to") ? ints(opt.get("--to"), 3, "--to") : s.max();
            imported = "{\"schematic\": " + ProblemWriter.quote(input.getFileName().toString())
                + ", \"origin\": " + json(origin);
        } else {
            if (!opt.containsKey("--from") || !opt.containsKey("--to")) {
                throw new IllegalArgumentException("--from and --to are required for a save");
            }
            int dim = opt.containsKey("--dimension") ? Integer.parseInt(opt.get("--dimension")) : 0;
            src = new AnvilWorld(input, dim);
            min = ints(opt.get("--from"), 3, "--from");
            max = ints(opt.get("--to"), 3, "--to");
            Path name = input.toAbsolutePath().normalize().getFileName();
            imported = "{\"save\": " + ProblemWriter.quote(name == null ? input.toString() : name.toString())
                + ", \"dimension\": " + dim;
        }
        for (int i = 0; i < 3; i++) {
            int lo = Math.min(min[i], max[i]);
            int hi = Math.max(min[i], max[i]);
            min[i] = lo;
            max[i] = hi;
        }
        min[1] = Math.max(min[1], 0);
        max[1] = Math.min(max[1], 255);
        if (min[1] > max[1]) {
            throw new IllegalArgumentException("the box is outside the build height 0 to 255");
        }
        imported += ", \"from\": " + json(min) + ", \"to\": " + json(max) + "}";

        Cut cut = cut(src, min, max, opt.containsKey("--unsupported-as-air"));
        List<String> messages = new ArrayList<String>(cut.errors);
        messages.addAll(cut.warnings);
        messages.addAll(ProblemWriter.blockWarnings(cut.world));

        float yaw = opt.containsKey("--yaw") ? Float.parseFloat(opt.get("--yaw")) : 0.0F;
        float pitch = opt.containsKey("--pitch") ? Float.parseFloat(opt.get("--pitch")) : 0.0F;
        Spawn spawn;
        if (opt.containsKey("--spawn")) {
            double[] s = doubles(opt.get("--spawn"), 3, 5, "--spawn");
            spawn = new Spawn(new Vec3(s[0], s[1], s[2]), s.length > 3 ? (float) s[3] : yaw, s.length > 4 ? (float) s[4] : pitch);
        } else if (opt.containsKey("--spawn-on")) {
            int[] b = ints(opt.get("--spawn-on"), 3, "--spawn-on");
            double[] p = ProblemWriter.standOn(cut.world, b[0], b[1], b[2]);
            if (p == null) throw new IllegalArgumentException("--spawn-on: no block with a collision box at " + json(b) + " in the box");
            spawn = new Spawn(new Vec3(p[0], p[1], p[2]), yaw, pitch);
        } else {
            throw new IllegalArgumentException("--spawn or --spawn-on is required");
        }
        if (!opt.containsKey("--start")) throw new IllegalArgumentException("--start is required");
        Region start = region(opt.get("--start"), "--start");
        Region goal;
        if (opt.containsKey("--goal")) {
            goal = region(opt.get("--goal"), "--goal");
        } else if (opt.containsKey("--goal-on")) {
            int[] b = ints(opt.get("--goal-on"), 3, "--goal-on");
            goal = ProblemWriter.landingOn(cut.world, b[0], b[1], b[2]);
            if (goal == null) throw new IllegalArgumentException("--goal-on: no block with a collision box at " + json(b) + " in the box");
        } else {
            throw new IllegalArgumentException("--goal or --goal-on is required");
        }
        messages.addAll(ProblemWriter.checkSpawn(cut.world, spawn));
        int maxTicks = opt.containsKey("--max-ticks") ? Integer.parseInt(opt.get("--max-ticks")) : ProblemIO.DEFAULT_MAX_TICKS;
        // Below the bottom of the box there is nothing to land on.
        double voidY = opt.containsKey("--void-y") ? Double.parseDouble(opt.get("--void-y")) : min[1];

        String json = ProblemWriter.problemJson(opt.get("--id"), opt.get("--title"), cut.world, imported,
            spawn, start, goal, maxTicks, voidY);
        boolean failed = false;
        for (String m : messages) {
            System.err.println(m);
            failed |= m.startsWith("error:");
        }
        if (failed) {
            System.err.println("nothing written (see the errors above)");
            return 1;
        }
        Problem back;
        try {
            back = ProblemIO.parse(json);
        } catch (ProblemFormatException e) {
            System.err.println("error: the problem file would not load: " + e.getMessage());
            return 1;
        }
        String diff = WorldDiff.firstDifference(cut.world, back.world);
        if (diff != null) {
            throw new IllegalStateException("the written fills do not give back the cut world: " + diff);
        }
        Path out = Paths.get(opt.get("--out"));
        if (out.toAbsolutePath().getParent() != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
        }
        Files.write(out, json.getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out + ": " + cut.world.size() + " blocks in " + ProblemWriter.fills(cut.world).size()
            + " fills from " + src.describe() + " " + json(min) + ".." + json(max));
        return 0;
    }

    private static int usage(String why) {
        System.err.println("import: " + why);
        System.err.println("usage:");
        System.err.println(USAGE);
        return 2;
    }

    private static Region region(String s, String what) {
        double[] v = doubles(s, 6, 6, what);
        return new Region(Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]),
            Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5]));
    }

    static int[] ints(String s, int n, String what) {
        String[] p = s.split(",");
        if (p.length != n) throw new IllegalArgumentException(what + " needs " + n + " integers separated by commas: " + s);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            try {
                out[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(what + ": not an integer: " + p[i]);
            }
        }
        return out;
    }

    private static double[] doubles(String s, int lo, int hi, String what) {
        String[] p = s.split(",");
        if (p.length < lo || p.length > hi) {
            throw new IllegalArgumentException(what + " needs " + (lo == hi ? "" + lo : lo + " to " + hi) + " numbers separated by commas: " + s);
        }
        double[] out = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                out[i] = Double.parseDouble(p[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(what + ": not a number: " + p[i]);
            }
        }
        return out;
    }

    private static String json(int[] a) {
        return "[" + a[0] + ", " + a[1] + ", " + a[2] + "]";
    }
}
