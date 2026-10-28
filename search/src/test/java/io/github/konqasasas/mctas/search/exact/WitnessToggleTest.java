package io.github.konqasasas.mctas.search.exact;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;
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
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Every v0 witness keeps sprint and sneak changes {@link Beam#TOGGLE_GAP} ticks apart until the goal. */
public class WitnessToggleTest {

    @Test
    public void v0WitnessesKeepTheToggleGap() throws IOException {
        int checked = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("..", "problems", "v0"), "*.problem.json")) {
            for (Path f : ds) {
                Problem p = ProblemIO.read(f);
                String text = new String(Files.readAllBytes(f.resolveSibling(p.target.witness)), StandardCharsets.UTF_8);
                List<TickInput> in = InputText.parse(text, p.spawn);
                RunResult r = Evaluator.run(p, in);
                assertEquals(p.id, Collections.<Integer>emptyList(), Beam.toggleViolations(p, in, r.goalTick));
                checked++;
            }
        }
        assertEquals(10, checked);
    }
}
