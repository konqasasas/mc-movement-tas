package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.testing.RandomScenario;
import org.junit.Test;

import static org.junit.Assert.assertNull;

/** M1: 1,000 random runs of 200 ticks agree bitwise with the vanilla player on every tick. */
public class DifferentialFuzzTest {

    @Test
    public void thousandRandomRunsMatchVanilla() {
        for (long seed = 1; seed <= 1000; seed++) {
            assertNull(DifferentialFuzz.check(RandomScenario.generate(seed, 200)));
        }
    }

    @Test
    public void thousandRandomRunsOnShapeCoursesMatchVanilla() {
        for (long seed = 1; seed <= 1000; seed++) {
            assertNull(DifferentialFuzz.check(RandomScenario.generateShapes(seed, 200)));
        }
    }

    @Test
    public void thousandRandomRunsOnFluidCoursesMatchVanilla() {
        for (long seed = 1; seed <= 1000; seed++) {
            assertNull(DifferentialFuzz.check(RandomScenario.generateFluids(seed, 200)));
        }
    }

    @Test
    public void thousandRandomRunsWithPlacingMatchVanilla() {
        for (long seed = 1; seed <= 1000; seed++) {
            assertNull(DifferentialFuzz.check(RandomScenario.generatePlacing(seed, 200)));
        }
    }

    @Test
    public void thousandRandomRunsWithShulkerBoxesMatchVanilla() {
        for (long seed = 1; seed <= 1000; seed++) {
            assertNull(DifferentialFuzz.check(RandomScenario.generateShulker(seed, 200)));
        }
    }

    @Test
    public void thousandRandomRunsWithPressurePlatesMatchVanilla() {
        for (long seed = 1; seed <= 1000; seed++) {
            assertNull(DifferentialFuzz.check(RandomScenario.generatePlates(seed, 200)));
        }
    }

    @Test
    public void viewRayMatchesVanillaOnRandomClusters() {
        for (long seed = 1; seed <= 500; seed++) {
            assertNull(RayTraceFuzz.check(seed, 200));
        }
    }
}
