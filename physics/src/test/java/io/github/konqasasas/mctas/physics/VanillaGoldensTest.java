package io.github.konqasasas.mctas.physics;

import io.github.konqasasas.mctas.physics.testing.RandomScenario;
import io.github.konqasasas.mctas.physics.testing.StateHash;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks {@link Physics} against trajectory hashes produced by the game's own code
 * ({@code :oracle:writeGoldens}): 1,000 random courses and input sequences of 200 ticks, every
 * field of every tick bitwise.
 */
public class VanillaGoldensTest {

    @Test
    public void matchesVanillaOnAllGoldenScenarios() throws IOException {
        List<String> failures = new ArrayList<String>();
        int checked = 0;
        InputStream in = getClass().getResourceAsStream("/vanilla-goldens.txt");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                long seed = Long.parseLong(p[0]);
                int ticks = Integer.parseInt(p[1]);
                long expected = Long.parseUnsignedLong(p[2], 16);
                RandomScenario sc = RandomScenario.generate(seed, ticks);
                if (hash(sc, sc.world) != expected) {
                    failures.add("seed " + seed);
                }
                if (hash(sc, DenseBlockWorld.of(sc.world, 3)) != expected) {
                    failures.add("seed " + seed + " (dense world)");
                }
                checked++;
            }
        } finally {
            r.close();
        }
        assertEquals(1000, checked);
        assertTrue("trajectories differ from vanilla (run :oracle:fuzz for the first differing tick): " + failures,
                failures.isEmpty());
    }

    private static long hash(RandomScenario sc, BlockWorld world) {
        StateHash h = new StateHash();
        PlayerState s = sc.start.copy();
        for (PlayerInput input : sc.inputs) {
            Physics.step(world, s, input);
            h.add(s);
        }
        return h.value();
    }
}
