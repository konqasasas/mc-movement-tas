package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.problem.io.ProblemIO;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The foothold graph's special moves: holes, swimming between separate water blocks, slime bounces, flat goals. */
public class CourseTest {
    private static Course course(String name) throws Exception {
        return new Course(ProblemIO.read(Paths.get("src/test/resources/course/" + name + ".problem.json")));
    }

    @Test
    public void findsTheHoleInTheMiddleOfTwoByTwoIronBars() throws Exception {
        Course c = course("hole");
        Course.Surface deck = c.support(-1.5, 69.0, 0.5);
        assertNotNull(deck);
        List<Course.Hole> holes = c.holes.get(deck.platform);
        assertNotNull(holes);
        assertEquals(1, holes.size());
        Course.Hole h = holes.get(0);
        assertEquals(1.0, h.x, 1e-9);
        assertEquals(3.0, h.z, 1e-9);
        assertEquals(65.0, h.below.top, 1e-9);
        // The deck reaches the goal only through it.
        assertTrue(deck.toGo < Double.POSITIVE_INFINITY);
    }

    @Test
    public void swimsOnBetweenWaterBlocksThatTheBodyTouchesTogether() throws Exception {
        Course c = course("water-stairs");
        int body = -1;
        for (int b = 0; b < c.bodies.size(); b++) for (int[] cell : c.bodies.get(b)) if (cell[1] == 65) body = b;
        assertEquals(3, c.bodies.get(body).size());
        List<int[]> path = c.pathThrough(body, new int[] {0, 65, 1}, new int[] {1, 69, 2});
        assertEquals(3, path.size());
        assertTrue(c.support(0.5, 65.0, -5.5).toGo < Double.POSITIVE_INFINITY);
    }

    @Test
    public void waterNeighboursGoBothWays() {
        // A body is grown from whichever of its cells comes first: a step only one way round splits it
        // when it is grown from the far end (waza3h3's water steps up from the upper one, F-527).
        java.util.Set<String> all = new java.util.HashSet<String>();
        for (int[] d : Course.WATER_NEIGHBOURS) all.add(d[0] + "," + d[1] + "," + d[2]);
        for (int[] d : Course.WATER_NEIGHBOURS) assertTrue(all.contains(-d[0] + "," + -d[1] + "," + -d[2]));
    }

    @Test
    public void swimsUpWaterStepsTwoApartBetweenWalls() throws Exception {
        Course c = new Course(ProblemIO.read(Paths.get("courses/elem-dev/e09-water-steps.problem.json")));
        int body = -1;
        for (int b = 0; b < c.bodies.size(); b++) for (int[] cell : c.bodies.get(b)) if (cell[1] == 68) body = b;
        assertEquals(4, c.bodies.get(body).size());
        assertTrue(c.support(0.5, 65.0, -5.5).toGo < Double.POSITIVE_INFINITY);
    }

    @Test
    public void bouncesOffSlimeOnlyFromAFallHighEnough() throws Exception {
        Course c = course("slime");
        Course.Surface high = c.support(0.5, 74.0, -5.5), far = c.support(0.5, 71.0, 11.5), floor = c.support(0.5, 65.0, 2.5);
        boolean bounce = false;
        for (Course.Surface a : c.platforms.get(high.platform)) {
            for (java.util.Map.Entry<Course.Surface, Course.Surface> e : a.bounceVia.entrySet()) {
                bounce |= e.getKey().platform == far.platform && c.slime(e.getValue()) && a.next.contains(e.getKey());
            }
        }
        assertTrue(bounce);
        // Walking onto the slime does not bounce high: no way up from the floor beside it.
        for (Course.Surface a : c.platforms.get(floor.platform)) for (Course.Surface b : a.next) assertTrue(b.platform != far.platform);
        assertTrue(c.flatGoal());
    }

    @Test
    public void bounceComesBackLowerThanTheFall() {
        // Measured in the engine: a fall of 4 onto slime comes back 2.55; 11 onto slime under water, 4.3.
        assertEquals(2.55, Course.bounceRise(4, false), 0.02);
        assertEquals(4.3, Course.bounceRise(11, true), 0.15);
    }

    @Test
    public void bouncesUpIntoWaterOverTheSlime() throws Exception {
        // The far top is out of a jump's reach and of the bounce's height (73 above 66), but the bounce
        // gets into the water at 72, which can be left onto it.
        Course c = course("slime-water");
        Course.Surface high = c.support(0.5, 74.0, -5.5), far = c.support(0.5, 73.0, 10.5);
        boolean edge = false;
        for (Course.Surface a : c.platforms.get(high.platform)) {
            for (int i = 0; i < a.viaBounceBody.size(); i++) {
                edge |= a.viaBounceBody.get(i).platform == far.platform && a.viaBounceEntry.get(i).cell[1] == 72 && a.notByStep.contains(a.viaBounceBody.get(i));
            }
            for (Course.Surface b : a.bounceVia.keySet()) assertTrue(b.platform != far.platform);
        }
        assertTrue(edge);
    }
}
