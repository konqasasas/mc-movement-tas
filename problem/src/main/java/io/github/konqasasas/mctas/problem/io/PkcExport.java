package io.github.konqasasas.mctas.problem.io;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;

import java.util.List;

public final class PkcExport {
    private static final double STANDING_MOTION_Y = -0.0784000015258789D;

    private PkcExport() {
    }

    public static String toPkcSave(Problem problem, List<TickInput> inputs, String createdAt) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("createdAt", createdAt);
        root.addProperty("mcVersion", Problem.MC_VERSION);
        JsonObject world = new JsonObject();
        world.addProperty("dimension", "Overworld");
        world.addProperty("worldName", "mctas:" + problem.id);
        root.add("world", world);
        JsonObject start = new JsonObject();
        start.add("pos", arr(problem.spawn.pos.x, problem.spawn.pos.y, problem.spawn.pos.z));
        start.add("vel", arr(0.0D, STANDING_MOTION_Y, 0.0D));
        start.addProperty("yaw", problem.spawn.yaw);
        start.addProperty("pitch", problem.spawn.pitch);
        root.add("start", start);
        JsonArray rows = new JsonArray();
        for (TickInput in : inputs) {
            JsonObject r = new JsonObject();
            if (in.has(Key.USE) || in.has(Key.CLOSE) || in.slot >= 0) {
                throw new ProblemFormatException("PKC has no right-click, close or hotbar keys; inputs that place blocks"
                        + " or open boxes cannot be exported");
            }
            JsonArray keys = new JsonArray();
            for (Key k : in.keys()) keys.add(new JsonPrimitive(k.name()));
            r.add("keys", keys);
            r.addProperty("yaw", in.yaw);
            r.addProperty("yawLocked", true);
            r.addProperty("pitch", in.pitch);
            r.addProperty("pitchLocked", true);
            r.addProperty("speedAmplifier", 0);
            r.addProperty("jumpBoostAmplifier", 0);
            rows.add(r);
        }
        root.add("rows", rows);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    private static JsonArray arr(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(new JsonPrimitive(x));
        a.add(new JsonPrimitive(y));
        a.add(new JsonPrimitive(z));
        return a;
    }
}
