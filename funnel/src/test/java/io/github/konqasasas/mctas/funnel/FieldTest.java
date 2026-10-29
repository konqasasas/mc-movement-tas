package io.github.konqasasas.mctas.funnel;

import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FieldTest {
    private static Problem read(String id) throws Exception {
        return ProblemIO.read(Paths.get("../problems/" + id + ".problem.json"));
    }

    @Test
    public void theGoalFieldReachesTheSpawnAcrossAThreeBlockGap() throws Exception {
        Problem p = read("v0/04-gap-3");
        Grid g = new Grid(p);
        Field fwd = new Field(g, true);
        fwd.seed(g.cellsIn(p.start), 0);
        Field back = new Field(g, false, fwd);
        back.seed(g.cellsIn(p.goal), 0);
        double x = p.spawn.pos.x, y = p.spawn.pos.y, z = p.spawn.pos.z;
        float v = back.at(x, y, z);
        assertTrue("no value at the spawn", v < Field.INF);
        // The moves are optimistic: no slower than a sprint over the straight distance less a little.
        assertTrue(v > 0);
        assertFalse(Float.isNaN(back.heading(x, y, z)));
    }

    @Test
    public void anchorsSitOnTheBlockTopsTheFeetStandOn() throws Exception {
        Problem p = read("v0/04-gap-3");
        Grid g = new Grid(p);
        Anchors anchors = new Anchors(g);
        assertFalse(anchors.list.isEmpty());
        for (Anchors.Anchor a : anchors.list) {
            assertFalse(a.cells.isEmpty());
            PlayerState s = a.settled(0);
            s.onGround = true;
            assertNotNull(a + " not found under its own centre", anchors.under(s, false));
        }
    }

    @Test
    public void aLandingIsValuedFromWhereTheFlightComesDown() throws Exception {
        Problem p = read("v0/04-gap-3");
        Grid g = new Grid(p);
        Field fwd = new Field(g, true);
        fwd.seed(g.cellsIn(p.start), 0);
        Field back = new Field(g, false, fwd);
        back.seed(g.cellsIn(p.goal), 0);
        Landing landing = new Landing(g, back);
        // Straight up from the spawn: comes down where it left, so no better than standing there.
        PlayerState s = PlayerState.standing(p.spawn.pos.x, p.spawn.pos.y + 0.5, p.spawn.pos.z, 0);
        s.onGround = false;
        float up = landing.value(s);
        assertTrue(up < Field.INF);
        assertTrue(up + 1e-3 >= back.at(p.spawn.pos.x, p.spawn.pos.y, p.spawn.pos.z) - Field.SPEED * 4);
    }

    @Test
    public void aBeamFromTheSpawnReachesTheGoal() throws Exception {
        Problem p = read("v0/02-gap-1");
        Grid g = new Grid(p);
        Field fwd = new Field(g, true);
        fwd.seed(g.cellsIn(p.start), 0);
        Field back = new Field(g, false, fwd);
        back.seed(g.cellsIn(p.goal), 0);
        Landing landing = new Landing(g, back);
        Anchors anchors = new Anchors(g);
        Beam beam = new Beam(g, back, landing, anchors);
        Beam.Options o = new Beam.Options();
        // The spawn is 30 blocks behind the start: the run-up is free, but takes ticks of the beam.
        o.width = 100;
        o.maxTicks = 160;
        o.judgeStart = true;
        PlayerState s = PlayerState.standing(p.spawn.pos.x, p.spawn.pos.y, p.spawn.pos.z, p.spawn.yaw);
        boolean in = p.start.contains(s.posX, s.posY, s.posZ);
        List<Beam.Hit> hits = beam.search(new Beam.Node(s, 0, in ? 0 : -1, in, -100), o);
        Beam.Hit goal = null;
        for (Beam.Hit h : hits) if (h.goal()) goal = h;
        assertNotNull("no hit in the goal", goal);
        assertTrue("slow: " + goal.value, goal.value <= 6);
    }
}
