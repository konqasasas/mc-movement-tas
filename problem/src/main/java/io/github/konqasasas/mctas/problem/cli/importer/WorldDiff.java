package io.github.konqasasas.mctas.problem.cli.importer;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.ProblemIO;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code mctas-problem diffworld}: the blocks where two problems' worlds differ, for checking a save import
 * against {@code /mctas export} of the same place.
 */
public final class WorldDiff {

    private WorldDiff() {
    }

    /** Every position whose block differs, as {@code "x,y,z: a vs b"}, sorted. */
    static List<String> differences(final SparseBlockWorld a, final SparseBlockWorld b) {
        final List<String> out = new ArrayList<String>();
        a.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                BlockType other = b.getBlock(x, y, z);
                if (other != type) {
                    out.add(x + "," + y + "," + z + ": " + type.name() + " vs " + other.name());
                }
            }
        });
        b.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                if (a.getBlock(x, y, z).isAir()) {
                    out.add(x + "," + y + "," + z + ": " + BlockType.AIR.name() + " vs " + type.name());
                }
            }
        });
        Collections.sort(out);
        return out;
    }

    static String firstDifference(SparseBlockWorld a, SparseBlockWorld b) {
        List<String> d = differences(a, b);
        return d.isEmpty() ? null : d.get(0);
    }

    /** Runs the command; returns 0 if the worlds are the same, 1 if not. */
    public static int run(String[] args) throws IOException {
        Problem a = ProblemIO.read(Paths.get(args[1]));
        Problem b = ProblemIO.read(Paths.get(args[2]));
        List<String> d = differences(a.world, b.world);
        int shown = Math.min(d.size(), 50);
        for (int i = 0; i < shown; i++) {
            System.out.println(d.get(i));
        }
        if (d.size() > shown) {
            System.out.println("... and " + (d.size() - shown) + " more");
        }
        System.out.println(d.isEmpty() ? "same world (" + a.world.size() + " blocks)"
            : d.size() + " positions differ (" + a.world.size() + " vs " + b.world.size() + " blocks)");
        return d.isEmpty() ? 0 : 1;
    }
}
