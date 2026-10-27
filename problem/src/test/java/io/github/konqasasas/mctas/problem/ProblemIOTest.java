package io.github.konqasasas.mctas.problem;

import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemFormatException;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProblemIOTest {
    private static final String BASE = "{\"format\":\"mctas-problem\",\"version\":1,\"id\":\"x\",\"mcVersion\":\"1.12.2\","
        + "\"world\":{\"source\":\"inline\",\"fills\":[{\"from\":[0,64,0],\"to\":[0,64,9],\"block\":\"stone\"}]},"
        + "\"spawn\":{\"pos\":[0.5,65,0.5]},"
        + "\"start\":{\"min\":[0,60,1],\"max\":[1,70,2]},";

    @Test
    public void readsInlineWorldAndRegions() {
        Problem p = ProblemIO.parse(BASE + "\"goal\":{\"min\":[0,65,8],\"max\":[1,65,9]}}");
        assertTrue(!p.world.getBlock(0, 64, 9).isAir());
        assertTrue(p.world.getBlock(0, 64, 10).isAir());
        assertEquals(1200, p.maxTicks);
        assertEquals(8.0, p.goal.minZ, 0.0);
    }

    @Test(expected = ProblemFormatException.class)
    public void rejectsOverlappingStartAndGoal() {
        ProblemIO.parse(BASE + "\"goal\":{\"min\":[0,65,1.5],\"max\":[1,65,9]}}");
    }

    @Test(expected = ProblemFormatException.class)
    public void rejectsNonEmptyReservedPlayerSection() {
        ProblemIO.parse(BASE + "\"goal\":{\"min\":[0,65,8],\"max\":[1,65,9]},\"player\":{\"inventory\":[]}}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedBlocks() {
        ProblemIO.parse(BASE.replace("stone", "stone_slab") + "\"goal\":{\"min\":[0,65,8],\"max\":[1,65,9]}}");
    }

    @Test
    public void textInputsRoundTrip() {
        Spawn spawn = new Spawn(new Vec3(0, 0, 0), 10.0F, 0.0F);
        List<TickInput> in = InputText.parse("3 W SPRINT\n1 W R J yaw=45.5\n2 - pitch=12\n", spawn);
        assertEquals(6, in.size());
        assertEquals(10.0F, in.get(0).yaw, 0.0F);
        assertTrue(in.get(3).has(Key.JUMP) && in.get(3).has(Key.SPRINT));
        assertEquals(45.5F, in.get(5).yaw, 0.0F);
        assertEquals(12.0F, in.get(5).pitch, 0.0F);
        List<TickInput> again = InputText.parse(InputText.format(in, spawn), spawn);
        assertEquals(in.toString(), again.toString());
    }
}
