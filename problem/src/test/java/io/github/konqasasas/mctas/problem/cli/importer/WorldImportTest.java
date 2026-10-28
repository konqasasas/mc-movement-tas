package io.github.konqasasas.mctas.problem.cli.importer;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import io.github.konqasasas.mctas.problem.world.Blocks;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WorldImportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void everyStoredStateIsAProblemBlockExceptThePortalsAndMovingPistons() {
        Set<Integer> unsupported = new HashSet<Integer>(Arrays.asList(36, 90, 119, 209));
        int states = 0;
        for (int id = 0; id < 256; id++) {
            for (int meta = 0; meta < 16; meta++) {
                if (!LegacyBlocks.stored(id, meta)) continue;
                String name;
                try {
                    name = LegacyBlocks.state(id, meta);
                } catch (IllegalArgumentException e) {
                    throw new AssertionError(id + ":" + meta + " is stored but does not decode", e);
                }
                try {
                    Blocks.byId(name);
                    assertFalse(name, unsupported.contains(id));
                    states++;
                } catch (IllegalArgumentException e) {
                    assertTrue(name + ": " + e.getMessage(), unsupported.contains(id));
                }
            }
        }
        // All 1,682 stored id:meta pairs, but the 16 of the portals, end gateway and moving piston.
        assertEquals(1666, states);
    }

    @Test
    public void metadataDecodesAsTheGameDoes() {
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", LegacyBlocks.state(53, 0));
        assertEquals("minecraft:stone_stairs[facing=north,half=top]", LegacyBlocks.state(67, 7));
        assertEquals("minecraft:stone_slab[half=top]", LegacyBlocks.state(44, 9));
        assertEquals("minecraft:snow_layer[layers=3]", LegacyBlocks.state(78, 2));
        assertEquals("minecraft:trapdoor[facing=west,half=top,open=true]", LegacyBlocks.state(96, 14));
        assertEquals("minecraft:fence_gate[facing=north,open=true]", LegacyBlocks.state(107, 6));
        assertEquals("minecraft:ladder[facing=north]", LegacyBlocks.state(65, 0));
        assertEquals("minecraft:ladder[facing=east]", LegacyBlocks.state(65, 5));
        assertEquals("minecraft:cocoa[age=1,facing=south]", LegacyBlocks.state(127, 4));
        assertEquals("minecraft:piston[extended=true,facing=up]", LegacyBlocks.state(33, 9));
        assertEquals("minecraft:skull[facing=north]", LegacyBlocks.state(144, 10));
        assertEquals("minecraft:water[level=0]", LegacyBlocks.state(9, 0));
        assertEquals("minecraft:wool", LegacyBlocks.state(35, 14));
        assertEquals("minecraft:wooden_door[facing=east,half=lower,hinge=left,open=false]", LegacyBlocks.state(64, 0));
    }

    @Test
    public void metadataNoStateHasLoadsAsAir() throws IOException {
        assertTrue(LegacyBlocks.stored(53, 7));
        assertFalse(LegacyBlocks.stored(53, 8));
        assertFalse(LegacyBlocks.stored(65, 0));
        assertFalse(LegacyBlocks.stored(1, 7));
        TestSaves save = new TestSaves().set(0, 64, 0, 1, 0).set(1, 64, 0, 53, 12);
        Path dir = tmp.newFolder("save").toPath();
        save.writeSave(dir);
        WorldImport.Cut cut = WorldImport.cut(new AnvilWorld(dir, 0), new int[] {0, 64, 0}, new int[] {1, 64, 0}, false);
        assertEquals(1, cut.world.size());
        assertTrue(cut.warnings.get(0), cut.warnings.get(0).contains("minecraft:oak_stairs with metadata 12 at 1,64,0"));
    }

    @Test
    public void doorHalvesCompleteEachOther() {
        // Lower half: facing west rotated counter-clockwise = south, open. Upper half: hinge right.
        assertEquals("minecraft:spruce_door[facing=south,half=lower,hinge=right,open=true]", LegacyBlocks.door(193, 5, 193, 9));
        assertEquals("minecraft:spruce_door[facing=south,half=upper,hinge=right,open=true]", LegacyBlocks.door(193, 9, 193, 5));
        // A different block on the other side leaves the half as stored.
        assertEquals("minecraft:spruce_door[facing=north,half=upper,hinge=right,open=false]", LegacyBlocks.door(193, 9, 64, 5));
    }

    @Test
    public void readsASaveAcrossChunkAndRegionBorders() throws IOException {
        TestSaves save = new TestSaves()
            .fill(-2, 64, -514, 1, 64, -510, 1, 0)      // stone across x=0 and the region border at z=-512
            .set(-1, 65, -511, 53, 2)                     // oak stairs facing south
            .set(0, 65, -511, 44, 8)                      // top slab
            .set(1, 65, -511, 71, 1).set(1, 66, -511, 71, 8) // iron door, closed, facing south, hinge left
            .set(0, 200, -511, 35, 5)                     // wool high up, its own section
            .set(5, 64, 5, 2, 0);                         // chunk 0,0: zlib
        Path dir = tmp.newFolder("save").toPath();
        save.writeSave(dir);
        AnvilWorld w = new AnvilWorld(dir, 0);
        WorldImport.Cut cut = WorldImport.cut(w, new int[] {-3, 60, -515}, new int[] {6, 210, 6}, false);
        assertTrue(cut.errors.toString(), cut.errors.isEmpty());
        assertEquals("minecraft:oak_stairs[facing=south,half=bottom]", cut.world.getBlock(-1, 65, -511).name());
        assertEquals("minecraft:stone_slab[half=top]", cut.world.getBlock(0, 65, -511).name());
        assertEquals("minecraft:iron_door[facing=south,half=lower,hinge=left,open=false]", cut.world.getBlock(1, 65, -511).name());
        assertEquals("minecraft:iron_door[facing=south,half=upper,hinge=left,open=false]", cut.world.getBlock(1, 66, -511).name());
        assertEquals("minecraft:wool", cut.world.getBlock(0, 200, -511).name());
        assertEquals("minecraft:grass", cut.world.getBlock(5, 64, 5).name());
        assertEquals("minecraft:stone", cut.world.getBlock(-2, 64, -514).name());
        assertEquals(20 + 5 + 1, cut.world.size());
        // Most of the box is in chunks that were never written.
        assertTrue(cut.warnings.toString(), cut.warnings.get(0).contains("no data"));
    }

    @Test
    public void readsASchematicAtItsOrigin() throws IOException {
        TestSaves blocks = new TestSaves().fill(0, 0, 0, 2, 0, 3, 1, 0).set(1, 1, 2, 126, 8).set(2, 2, 3, 107, 5);
        Path file = tmp.newFile("course.schematic").toPath();
        blocks.writeSchematic(file, new int[] {0, 0, 0}, new int[] {2, 2, 3});
        Schematic s = new Schematic(file, new int[] {100, 64, -7});
        assertEquals(3, s.width);
        assertEquals(4, s.length);
        WorldImport.Cut cut = WorldImport.cut(s, s.min(), s.max(), false);
        assertEquals("minecraft:wooden_slab[half=top]", cut.world.getBlock(101, 65, -5).name());
        assertEquals("minecraft:fence_gate[facing=west,open=true]", cut.world.getBlock(102, 66, -4).name());
        assertEquals(14, cut.world.size());
    }

    @Test
    public void portalsAreErrorsOrAirOnRequest() throws IOException {
        TestSaves save = new TestSaves().fill(0, 64, 0, 3, 64, 0, 1, 0).set(1, 65, 0, 90, 1).set(2, 65, 0, 90, 1);
        Path dir = tmp.newFolder("save").toPath();
        save.writeSave(dir);
        AnvilWorld w = new AnvilWorld(dir, 0);
        WorldImport.Cut strict = WorldImport.cut(w, new int[] {0, 64, 0}, new int[] {3, 65, 0}, false);
        assertEquals(1, strict.errors.size());
        assertTrue(strict.errors.get(0), strict.errors.get(0).contains("portal") && strict.errors.get(0).contains("2 blocks, first at 1,65,0"));
        WorldImport.Cut lenient = WorldImport.cut(w, new int[] {0, 64, 0}, new int[] {3, 65, 0}, true);
        assertTrue(lenient.errors.isEmpty());
        assertTrue(lenient.warnings.get(0).startsWith("warning: read as air"));
        assertEquals(4, lenient.world.size());
    }

    @Test
    public void refusesASpawnInsideABlock() throws IOException {
        TestSaves save = new TestSaves().fill(0, 64, 0, 3, 65, 3, 1, 0);
        Path dir = tmp.newFolder("save").toPath();
        save.writeSave(dir);
        Path out = dir.resolve("out.problem.json");
        int code = WorldImport.run(new String[] {"import", dir.toString(), "-o", out.toString(), "--id", "t",
            "--from", "0,64,0", "--to", "3,65,3", "--spawn", "1.5,65.5,1.5", "--start", "0,66,0,1,67,1",
            "--goal-on", "3,65,3"});
        assertEquals(1, code);
        assertFalse(Files.exists(out));
    }

    /**
     * Every v0 problem written into a save and imported back gives the same world, and its witness the
     * same time: what {@code mctas-problem check} checks.
     */
    @Test
    public void v0ProblemsSurviveASaveRoundTrip() throws IOException {
        Map<String, Integer> numeric = new HashMap<String, Integer>();
        for (int id = 0; id < 256; id++) {
            for (int meta = 15; meta >= 0; meta--) {
                try {
                    numeric.put(Blocks.byId(LegacyBlocks.state(id, meta)).name(), id << 4 | meta);
                } catch (IllegalArgumentException e) {
                    // not a state
                }
            }
        }
        int checked = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("..", "problems", "v0"), "*.problem.json")) {
            for (Path f : ds) {
                final Problem p = ProblemIO.read(f);
                final TestSaves save = new TestSaves();
                final Map<String, Integer> num = numeric;
                final int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
                final int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
                p.world.forEach(new SparseBlockWorld.Visitor() {
                    @Override
                    public void visit(int x, int y, int z, BlockType type) {
                        int v = num.get(type.name());
                        save.set(x, y, z, v >> 4, v & 15);
                        int[] q = {x, y, z};
                        for (int i = 0; i < 3; i++) {
                            min[i] = Math.min(min[i], q[i]);
                            max[i] = Math.max(max[i], q[i]);
                        }
                    }
                });
                Path dir = tmp.newFolder().toPath();
                save.writeSave(dir.resolve("save"));
                Path out = dir.resolve("imported.problem.json");
                int code = WorldImport.run(new String[] {"import", dir.resolve("save").toString(), "-o", out.toString(),
                    "--id", p.id, "--from", (min[0] - 2) + "," + (min[1] - 1) + "," + (min[2] - 2),
                    "--to", (max[0] + 2) + "," + (max[1] + 3) + "," + (max[2] + 2),
                    "--spawn", p.spawn.pos.x + "," + p.spawn.pos.y + "," + p.spawn.pos.z + "," + p.spawn.yaw + "," + p.spawn.pitch,
                    "--start", p.start.minX + "," + p.start.minY + "," + p.start.minZ + "," + p.start.maxX + "," + p.start.maxY + "," + p.start.maxZ,
                    "--goal", p.goal.minX + "," + p.goal.minY + "," + p.goal.minZ + "," + p.goal.maxX + "," + p.goal.maxY + "," + p.goal.maxZ,
                    "--max-ticks", Integer.toString(p.maxTicks), "--void-y", Double.toString(p.voidY)});
                assertEquals(p.id, 0, code);
                Problem back = ProblemIO.read(out);
                assertNull(p.id, WorldDiff.firstDifference(p.world, back.world));
                String text = new String(Files.readAllBytes(f.resolveSibling(p.target.witness)), StandardCharsets.UTF_8);
                RunResult r = Evaluator.run(back, InputText.parse(text, back.spawn));
                assertTrue(p.id, r.finished());
                assertEquals(p.id, p.target.ticks, r.ticks());
                checked++;
            }
        }
        assertEquals(10, checked);
    }

    @Test
    public void landingGoalFollowsTheBlockTop() throws IOException {
        SparseBlockWorld w = new SparseBlockWorld();
        w.setBlock(0, 64, 0, Blocks.byId("minecraft:stone_slab[half=bottom]"));
        w.setBlock(3, 64, 0, Blocks.byId("minecraft:fence"));
        io.github.konqasasas.mctas.problem.Region slab = ProblemWriter.landingOn(w, 0, 64, 0);
        assertEquals(64.5D, slab.minY, 0.0D);
        assertEquals(64.5D, slab.maxY, 0.0D);
        assertEquals(-0.3D, slab.minX, 0.0D);
        assertEquals(1.3D, slab.maxX, 0.0D);
        io.github.konqasasas.mctas.problem.Region post = ProblemWriter.landingOn(w, 3, 64, 0);
        assertEquals(65.5D, post.minY, 0.0D);
        assertEquals(3.375D - 0.3D, post.minX, 1e-12);
        assertEquals(null, ProblemWriter.landingOn(w, 5, 64, 0));
        double[] stand = ProblemWriter.standOn(w, 0, 64, 0);
        assertEquals(64.5D, stand[1], 0.0D);
        assertTrue(ProblemWriter.checkSpawn(w, new io.github.konqasasas.mctas.problem.Spawn(
            new io.github.konqasasas.mctas.problem.Vec3(stand[0], stand[1], stand[2]), 0.0F, 0.0F)).isEmpty());
        if (ProblemWriter.checkSpawn(w, new io.github.konqasasas.mctas.problem.Spawn(
                new io.github.konqasasas.mctas.problem.Vec3(0.5D, 66.0D, 0.5D), 0.0F, 0.0F)).isEmpty()) {
            fail("a spawn in the air should warn");
        }
    }
}
