package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.testing.RandomScenario;

import java.util.List;

/**
 * Runs {@link RandomScenario}s through both {@link Physics} and {@link VanillaOracle} and compares
 * every field of every tick bitwise.
 *
 * <p>Usage: {@code DifferentialFuzz [runs] [ticks] [firstSeed] [shapes]}; {@code shapes} uses
 * {@link RandomScenario#generateShapes} instead of the full-block courses.
 */
public final class DifferentialFuzz {

    private DifferentialFuzz() {
    }

    /** Null when all ticks match, otherwise a description of the first mismatch. */
    public static String check(RandomScenario sc) {
        VanillaOracle oracle = new VanillaOracle(sc.world);
        List<PlayerState> expected = oracle.run(sc.start, sc.inputs);
        PlayerState s = sc.start.copy();
        for (int t = 0; t < sc.inputs.size(); t++) {
            Physics.step(sc.world, s, sc.inputs.get(t));
            String field = s.firstDifference(expected.get(t));
            if (field != null) {
                return "seed " + sc.seed + " tick " + t + " field " + field + " input " + sc.inputs.get(t)
                        + "\n  physics: " + s + "\n  vanilla: " + expected.get(t)
                        + (t > 0 ? "\n  before:  " + expected.get(t - 1) : "\n  start:   " + sc.start);
            }
        }
        return null;
    }

    public static void main(String[] args) {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        long first = args.length > 2 ? Long.parseLong(args[2]) : 1L;
        boolean shapes = args.length > 3 && args[3].equals("shapes");
        int failures = 0;
        long totalTicks = 0;
        long t0 = System.nanoTime();
        for (long seed = first; seed < first + runs; seed++) {
            RandomScenario sc = shapes ? RandomScenario.generateShapes(seed, ticks) : RandomScenario.generate(seed, ticks);
            String msg = check(sc);
            totalTicks += ticks;
            if (msg != null) {
                failures++;
                if (failures <= 5) {
                    System.out.println(msg);
                }
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%d runs x %d ticks: %d mismatching runs (%.1f s, %d ticks compared)%n",
                runs, ticks, failures, secs, totalTicks);
        if (failures > 0) {
            System.exit(1);
        }
    }
}
