package io.github.konqasasas.mctas.problem.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.Spawn;
import io.github.konqasasas.mctas.problem.Target;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.world.Blocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class ProblemIO {
    public static final int DEFAULT_MAX_TICKS = 1200;

    private ProblemIO() {
    }

    public static Problem read(Path file) throws IOException {
        return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    public static Problem parse(String json) {
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        expect(Problem.FORMAT.equals(str(root, "format")), "format must be \"" + Problem.FORMAT + "\"");
        expect(intOf(root, "version") == Problem.VERSION, "unsupported version, expected " + Problem.VERSION);
        expect(Problem.MC_VERSION.equals(str(root, "mcVersion")), "mcVersion must be " + Problem.MC_VERSION);
        String id = str(root, "id");
        String title = root.has("title") ? str(root, "title") : id;

        rejectReserved(root, "player");
        if (root.has("rules")) {
            JsonObject rules = root.getAsJsonObject("rules");
            expect(!rules.has("autoJump") || !rules.get("autoJump").getAsBoolean(), "rules.autoJump=true is not supported");
        }

        SparseBlockWorld world = readWorld(obj(root, "world"));
        JsonObject sp = obj(root, "spawn");
        Spawn spawn = new Spawn(vec(sp, "pos"), sp.has("yaw") ? sp.get("yaw").getAsFloat() : 0.0F,
            sp.has("pitch") ? sp.get("pitch").getAsFloat() : 0.0F);
        Region start = region(obj(root, "start"));
        Region goal = region(obj(root, "goal"));
        expect(!start.intersects(goal), "start and goal regions must not overlap");

        int maxTicks = DEFAULT_MAX_TICKS;
        double voidY = -64.0D;
        if (root.has("limits")) {
            JsonObject lim = root.getAsJsonObject("limits");
            if (lim.has("maxTicks")) maxTicks = lim.get("maxTicks").getAsInt();
            if (lim.has("voidY")) voidY = lim.get("voidY").getAsDouble();
        }
        expect(maxTicks > 0, "limits.maxTicks must be positive");

        Target target = null;
        if (root.has("target") && !root.get("target").isJsonNull()) {
            JsonObject t = root.getAsJsonObject("target");
            Target.Kind kind = Target.Kind.valueOf(str(t, "kind").toUpperCase(Locale.ROOT));
            target = new Target(intOf(t, "ticks"), kind, t.has("witness") ? str(t, "witness") : null,
                t.has("note") ? str(t, "note") : null);
        }
        return new Problem(id, title, world, spawn, start, goal, maxTicks, voidY, target);
    }

    private static SparseBlockWorld readWorld(JsonObject w) {
        String source = str(w, "source");
        if (!"inline".equals(source)) {
            throw new ProblemFormatException("world.source \"" + source + "\" is not supported yet (only \"inline\")");
        }
        SparseBlockWorld world = new SparseBlockWorld();
        JsonArray fills = w.getAsJsonArray("fills");
        expect(fills != null, "world.fills is required");
        for (JsonElement e : fills) {
            JsonObject f = e.getAsJsonObject();
            int[] a = ivec(f, "from");
            int[] b = f.has("to") ? ivec(f, "to") : a;
            world.fill(a[0], a[1], a[2], b[0], b[1], b[2], Blocks.byId(str(f, "block")));
        }
        return world;
    }

    private static void rejectReserved(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) return;
        JsonElement e = root.get(key);
        boolean empty = e.isJsonObject() && e.getAsJsonObject().entrySet().isEmpty();
        expect(empty, "\"" + key + "\" is reserved for a later format version and must be empty");
    }

    private static Region region(JsonObject o) {
        Vec3 a = vec(o, "min");
        Vec3 b = vec(o, "max");
        return new Region(a.x, a.y, a.z, b.x, b.y, b.z);
    }

    private static Vec3 vec(JsonObject o, String key) {
        JsonArray a = o.getAsJsonArray(key);
        expect(a != null && a.size() == 3, key + " must be [x, y, z]");
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    private static int[] ivec(JsonObject o, String key) {
        JsonArray a = o.getAsJsonArray(key);
        expect(a != null && a.size() == 3, key + " must be [x, y, z]");
        return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    private static JsonObject obj(JsonObject o, String key) {
        expect(o.has(key) && o.get(key).isJsonObject(), "\"" + key + "\" object is required");
        return o.getAsJsonObject(key);
    }

    private static String str(JsonObject o, String key) {
        expect(o.has(key), "\"" + key + "\" is required");
        return o.get(key).getAsString();
    }

    private static int intOf(JsonObject o, String key) {
        expect(o.has(key), "\"" + key + "\" is required");
        return o.get(key).getAsInt();
    }

    private static void expect(boolean cond, String message) {
        if (!cond) throw new ProblemFormatException(message);
    }
}
