package io.github.konqasasas.mctas.problem.io;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.konqasasas.mctas.physics.EngineBuild;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.judge.RunResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class SolutionIO {
    public static final String FORMAT = "mctas-solution";
    public static final int VERSION = 1;

    private SolutionIO() {
    }

    public static String write(Problem problem, List<TickInput> inputs, RunResult result, String model) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        root.addProperty("problem", problem.id);
        root.addProperty("mcVersion", Problem.MC_VERSION);
        JsonObject res = new JsonObject();
        res.addProperty("status", result.status.name().toLowerCase());
        if (result.finished()) {
            res.addProperty("ticks", result.ticks());
            res.addProperty("startTick", result.startTick);
            res.addProperty("goalTick", result.goalTick);
        }
        res.addProperty("model", model);
        res.addProperty("engineBuild", EngineBuild.ID);
        root.add("result", res);
        JsonArray rows = new JsonArray();
        int n = result.finished() ? result.goalTick : inputs.size();
        for (int i = 0; i < n; i++) {
            TickInput in = inputs.get(i);
            JsonObject r = new JsonObject();
            JsonArray keys = new JsonArray();
            for (Key k : in.keys()) keys.add(new com.google.gson.JsonPrimitive(k.name()));
            r.add("keys", keys);
            r.addProperty("yaw", in.yaw);
            r.addProperty("pitch", in.pitch);
            if (in.slot >= 0) r.addProperty("slot", in.slot);
            rows.add(r);
        }
        root.add("inputs", rows);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /** The {@code result.engineBuild} of a solution file, or null when it has none (files from before it existed). */
    public static String readEngineBuild(String json) {
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        JsonObject res = root.has("result") ? root.getAsJsonObject("result") : null;
        return res != null && res.has("engineBuild") ? res.get("engineBuild").getAsString() : null;
    }

    /**
     * Null when a solution recorded by build {@code recorded} is safe to replay or judge with this build,
     * otherwise a warning that names both builds.
     */
    public static String buildMismatch(String recorded) {
        if (recorded == null) {
            return "the solution does not say which engine build made it (this is " + EngineBuild.ID + ")";
        }
        if (!recorded.equals(EngineBuild.ID)) {
            return "the solution was made by engine build " + recorded + " but this is " + EngineBuild.ID
                    + "; the inputs may not do what they did when it was found";
        }
        return null;
    }

    public static List<TickInput> readInputs(String json) {
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        if (!root.has("format") || !FORMAT.equals(root.get("format").getAsString())) {
            throw new ProblemFormatException("format must be \"" + FORMAT + "\"");
        }
        List<TickInput> out = new ArrayList<TickInput>();
        for (JsonElement e : root.getAsJsonArray("inputs")) {
            JsonObject r = e.getAsJsonObject();
            Set<Key> keys = EnumSet.noneOf(Key.class);
            for (JsonElement k : r.getAsJsonArray("keys")) keys.add(Key.parse(k.getAsString()));
            out.add(new TickInput(keys, r.get("yaw").getAsFloat(), r.get("pitch").getAsFloat(),
                r.has("slot") ? r.get("slot").getAsInt() : -1));
        }
        return out;
    }
}
