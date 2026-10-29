package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.problem.io.ProblemIO;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The learned guide (WS13): its model fits and reads back, and its estimate never undercuts the bound. */
public class GuideTest {
    private static Course course(String name) throws Exception {
        return new Course(ProblemIO.read(Paths.get("src/test/resources/course/" + name + ".problem.json")));
    }

    /** Moves with little margin are not made, the others are. */
    private static GuideModel toyModel() {
        List<double[]> fs = new ArrayList<double[]>();
        List<Integer> made = new ArrayList<Integer>();
        for (int i = 0; i < 200; i++) {
            double margin = -1 + 4 * i / 200.0;
            double[] f = new double[GuideFeatures.N];
            f[0] = 3 - margin;
            f[2] = margin;
            f[3] = i % 5;
            fs.add(f);
            made.add(margin > 1 ? 1 : 0);
        }
        return GuideModel.fit(fs, made, 500, 1e-4);
    }

    @Test
    public void learnsThatMovesWithLittleMarginAreNotMadeAndReadsBack() {
        GuideModel m = toyModel();
        double[] easy = new double[GuideFeatures.N], hard = new double[GuideFeatures.N];
        easy[2] = 2.5;
        hard[2] = 0.2;
        hard[0] = 2.8;
        assertTrue(m.made(easy) > 0.9);
        assertTrue(m.made(hard) < 0.1);
        GuideModel back = GuideModel.read(m.write());
        assertEquals(m.made(easy), back.made(easy), 1e-4);
        assertEquals(m.made(hard), back.made(hard), 1e-4);
    }

    @Test
    public void estimateIsTheBoundWithoutPenaltyAndNeverBelowIt() throws Exception {
        Course c = course("slalom");
        GuideModel m = toyModel();
        GuideField none = new GuideField(c, m, 0), some = new GuideField(c, m, 20);
        for (Course.Surface s : c.surfaces) {
            if (s.toGo == Double.POSITIVE_INFINITY) continue;
            assertEquals(s.toGo, none.at(s), 1e-9);
            assertTrue(some.at(s) >= s.toGo - 1e-9);
            double x = s.cx(), z = s.cz();
            assertEquals(c.toGo(x, s.top, z), none.toGo(x, s.top, z), 1e-9);
        }
    }

    @Test
    public void refusesReportOnlyProblems() {
        assertTrue(GuideTool.reportOnly("human6/prism"));
        assertTrue(GuideTool.reportOnly("waza/waza3h2"));
        assertTrue(GuideTool.reportOnly("gen-dev2-holdout/g0301"));
        assertTrue(!GuideTool.reportOnly("human6/verdure"));
        assertTrue(!GuideTool.reportOnly("gen-dev2/g0001"));
    }
}
