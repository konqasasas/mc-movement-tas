package io.github.konqasasas.mctas.problem;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.provisional.PkcCheckpoint;
import io.github.konqasasas.mctas.problem.provisional.PlayerState;
import io.github.konqasasas.mctas.problem.provisional.ReferenceStepper;
import io.github.konqasasas.mctas.problem.world.BlockType;
import io.github.konqasasas.mctas.problem.world.BlockWorld;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class PkcCaptureParityTest {
    private static final int LAST_TICK_ON_FLOOR = 36;
    private static final int PKC_CHECKPOINT_TICK = 25;

    @Test
    public void matchesCaptureBitForBitUntilPkcCheckpoint() throws Exception {
        replay(PKC_CHECKPOINT_TICK, -1);
    }

    @Test
    public void matchesWholeFloorSegmentWhenBoxIsRebuiltAtPkcCheckpoint() throws Exception {
        replay(LAST_TICK_ON_FLOOR, PKC_CHECKPOINT_TICK);
    }

    private static void replay(int lastTick, int checkpointTick) throws Exception {
        JsonObject root = new JsonParser().parse(new InputStreamReader(
            PkcCaptureParityTest.class.getResourceAsStream("/pkc/trp-optimize-feasible-swap.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("1.12.2", root.get("mcVersion").getAsString());
        JsonArray rows = root.getAsJsonArray("rows");
        JsonArray debug = root.getAsJsonArray("debug");
        JsonObject start = root.getAsJsonObject("start");
        JsonArray p0 = start.getAsJsonArray("pos");

        BlockWorld world = new BlockWorld();
        world.fill(-10, 25, -10, 20, 25, 20, BlockType.byId("stone"));
        ReferenceStepper stepper = new ReferenceStepper(world);
        Spawn spawn = new Spawn(new Vec3(p0.get(0).getAsDouble(), p0.get(1).getAsDouble(), p0.get(2).getAsDouble()),
            start.get("yaw").getAsFloat(), start.get("pitch").getAsFloat());
        PlayerState s = PlayerState.standingAt(spawn);

        for (int t = 0; t <= lastTick; t++) {
            if (t == checkpointTick) PkcCheckpoint.restoreLikePkc(s);
            JsonObject row = rows.get(t).getAsJsonObject();
            Set<Key> keys = EnumSet.noneOf(Key.class);
            for (JsonElement k : row.getAsJsonArray("keys")) keys.add(Key.valueOf(k.getAsString()));
            JsonObject d = debug.get(t + 1).getAsJsonObject();
            s = stepper.tick(s, new TickInput(keys, d.get("yaw").getAsFloat(), 0.0F));
            JsonArray pos = d.getAsJsonArray("pos");
            JsonArray vel = d.getAsJsonArray("vel");
            String at = "tick " + t;
            assertEquals(at + " x", pos.get(0).getAsDouble(), s.x(), 0.0);
            assertEquals(at + " y", pos.get(1).getAsDouble(), s.y(), 0.0);
            assertEquals(at + " z", pos.get(2).getAsDouble(), s.z(), 0.0);
            assertEquals(at + " vx", vel.get(0).getAsDouble(), s.motionX(), 0.0);
            assertEquals(at + " vy", vel.get(1).getAsDouble(), s.motionY(), 0.0);
            assertEquals(at + " vz", vel.get(2).getAsDouble(), s.motionZ(), 0.0);
            assertEquals(at + " ground", d.get("onGround").getAsBoolean(), s.onGround());
            assertEquals(at + " sprint", d.get("sprinting").getAsBoolean(), s.sprinting());
        }
    }
}
