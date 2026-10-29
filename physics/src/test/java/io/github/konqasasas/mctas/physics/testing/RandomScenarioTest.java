package io.github.konqasasas.mctas.physics.testing;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RandomScenarioTest {

    @Test
    public void inputsFromTheScenariosOwnStartAreItsInputs() {
        for (String course : RandomScenario.COURSES) {
            for (long seed = 1; seed <= 20; seed++) {
                RandomScenario sc = RandomScenario.generate(course, seed, 200);
                assertEquals(course + " " + seed, sc.inputs,
                        RandomScenario.inputsFrom(course, seed, 200, sc.world, sc.start));
            }
        }
    }
}
