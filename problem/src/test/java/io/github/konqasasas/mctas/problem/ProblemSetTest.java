package io.github.konqasasas.mctas.problem;

import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ProblemSetTest {
    private static final Path V0 = Paths.get("..", "problems", "v0");

    @Test
    public void everyV0WitnessReachesItsTargetExactly() throws IOException {
        assertEquals(10, checkWitnesses(V0));
    }

    @Test
    public void everyPlacingWitnessReachesItsTargetExactly() throws IOException {
        assertEquals(1, checkWitnesses(Paths.get("..", "problems", "place")));
    }

    @Test
    public void everyFallWitnessReachesItsTargetExactly() throws IOException {
        assertEquals(1, checkWitnesses(Paths.get("..", "problems", "fall")));
    }

    @Test
    public void everyV1WitnessReachesItsTargetExactly() throws IOException {
        assertEquals(8, checkWitnesses(Paths.get("..", "problems", "v1-dev")));
        // Some held-out courses have no known solution yet.
        assertEquals(9, checkWitnesses(Paths.get("..", "problems", "v1-holdout"), false));
        assertEquals(7, checkWitnesses(Paths.get("..", "problems", "v1-full"), false));
    }

    private static int checkWitnesses(Path dir) throws IOException {
        return checkWitnesses(dir, true);
    }

    private static int checkWitnesses(Path dir, boolean everyHasTarget) throws IOException {
        int checked = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.problem.json")) {
            for (Path f : ds) {
                Problem p = ProblemIO.read(f);
                if (p.target == null && !everyHasTarget) continue;
                assertNotNull(p.id + " has no target", p.target);
                String text = new String(Files.readAllBytes(f.resolveSibling(p.target.witness)), StandardCharsets.UTF_8);
                RunResult r = Evaluator.run(p, InputText.parse(text, p.spawn));
                assertTrue(p.id + " witness does not finish: " + r.status, r.finished());
                assertEquals(p.id, p.target.ticks, r.ticks());
                checked++;
            }
        }
        return checked;
    }
}
