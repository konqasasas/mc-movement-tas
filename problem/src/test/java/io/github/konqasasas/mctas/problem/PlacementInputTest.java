package io.github.konqasasas.mctas.problem;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemFormatException;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.io.SolutionIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Version 2 problems give the player blocks, and the inputs right-click and pick hotbar slots. */
public class PlacementInputTest {
    private static final String BASE = "{\"format\":\"mctas-problem\",\"version\":2,\"id\":\"x\",\"mcVersion\":\"1.12.2\","
        + "\"world\":{\"source\":\"inline\",\"fills\":[{\"from\":[0,64,0],\"to\":[0,64,9],\"block\":\"stone\"}]},"
        + "\"spawn\":{\"pos\":[0.5,65,0.5]},"
        + "\"start\":{\"min\":[0,60,1],\"max\":[1,70,2]},"
        + "\"goal\":{\"min\":[0,65,8],\"max\":[1,65,9]}";

    @Test
    public void readsTheHotbar() {
        Problem p = ProblemIO.parse(BASE + ",\"player\":{\"inventory\":[{\"slot\":2,\"block\":\"minecraft:dirt\",\"count\":5},"
            + "{\"slot\":0,\"block\":\"stone\"}],\"selectedSlot\":2}}");
        assertSame(BlockType.STONE, p.inventory.item(0));
        assertEquals(64, p.inventory.count(0));
        assertEquals(5, p.inventory.count(2));
        assertEquals(2, p.selectedSlot);
    }

    @Test(expected = ProblemFormatException.class)
    public void version1StillRejectsThePlayerSection() {
        ProblemIO.parse(BASE.replace("\"version\":2", "\"version\":1")
            + ",\"player\":{\"inventory\":[{\"slot\":0,\"block\":\"stone\"}]}}");
    }

    @Test(expected = ProblemFormatException.class)
    public void rejectsBlocksTheEngineCannotPlace() {
        ProblemIO.parse(BASE + ",\"player\":{\"inventory\":[{\"slot\":0,\"block\":\"stone_slab[half=bottom]\"}]}}");
    }

    @Test
    public void textAndSolutionKeepUseAndSlot() {
        Problem p = ProblemIO.parse(BASE + "}");
        List<TickInput> in = InputText.parse("1 J U slot=3 pitch=90\n2 U\n1 -\n", p.spawn);
        assertEquals(4, in.size());
        assertTrue(in.get(0).has(Key.USE));
        assertEquals(3, in.get(0).slot);
        assertEquals(-1, in.get(1).slot);
        assertEquals("1 JUMP USE pitch=90.0 slot=3\n2 USE\n1 -\n", InputText.format(in, p.spawn));
        List<TickInput> back = SolutionIO.readInputs(SolutionIO.write(p, in, Evaluator.run(p, in), "physics-1.12.2"));
        assertEquals(in.toString(), back.toString());
    }

    @Test
    public void pillarsUpOnAPlacedBlock() {
        Problem p = ProblemIO.parse(BASE + ",\"player\":{\"inventory\":[{\"slot\":4,\"block\":\"stone\",\"count\":3}]}}");
        List<TickInput> in = InputText.parse("1 J U slot=4 pitch=90\n15 U\n", p.spawn);
        PhysicsModel m = new PhysicsModel(p);
        PlayerState s = m.spawn(p);
        for (TickInput t : in) {
            s = m.tick(s, t);
        }
        assertEquals(1, s.placed.size());
        assertSame(BlockType.STONE, s.world(p.world).getBlock(0, 65, 0));
        assertEquals(2, s.hotbar.count(4));
        assertEquals(66.0, s.posY, 0.0);
        assertTrue(s.onGround);
    }
}
