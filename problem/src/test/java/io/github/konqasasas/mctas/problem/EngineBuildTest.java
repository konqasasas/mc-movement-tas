package io.github.konqasasas.mctas.problem;

import io.github.konqasasas.mctas.physics.EngineBuild;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

/** The committed engine build id matches the sources, so results name the build that made them. */
public class EngineBuildTest {

    @Test
    public void idIsUpToDate() throws Exception {
        String root = System.getProperty("mctas.repoRoot", "..");
        assertEquals("the engine sources changed: run ./gradlew :problem:writeEngineBuild and commit the new id",
                EngineBuild.compute(Paths.get(root).toAbsolutePath().normalize()), EngineBuild.ID);
    }
}
