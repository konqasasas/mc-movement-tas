package io.github.konqasasas.mctas.problem.world;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.VanillaBlocks;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Block names in problem files.
 *
 * <p>A name is a vanilla block state string such as {@code minecraft:oak_stairs[facing=north,half=bottom]},
 * the same form {@link VanillaBlocks} uses. The {@code minecraft:} prefix may be left out, the properties
 * may come in any order, and properties that do not change collision (colour, wood variant, stair shape,
 * fence connections) are accepted and ignored, so a state copied from the F3 screen works as it is.
 * Every 1.12.2 block is known except {@code portal}, {@code end_portal}, {@code end_gateway} and
 * {@code piston_extension}.
 */
public final class Blocks {
    private Blocks() {
    }

    public static BlockType byId(String name) {
        String full = name.trim();
        if (VanillaBlocks.blockId(full).indexOf(':') < 0) {
            full = "minecraft:" + full;
        }
        String id = VanillaBlocks.blockId(full);
        if (full.indexOf('[') >= 0 && !full.endsWith("]")) {
            throw new IllegalArgumentException("malformed block state: " + name);
        }
        Map<String, String> given;
        try {
            given = VanillaBlocks.parseProperties(full);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed block state: " + name);
        }
        List<String> keys = VanillaBlocks.properties(id);
        Map<String, String> kept = new TreeMap<String, String>();
        if (keys != null) {
            for (String k : keys) {
                if (!given.containsKey(k)) {
                    throw new IllegalArgumentException("block " + id + " needs the properties " + keys + ": " + name);
                }
                kept.put(k, given.get(k));
            }
        }
        BlockType t = VanillaBlocks.get(VanillaBlocks.name(id, kept));
        if (t == null) {
            throw new IllegalArgumentException("unsupported block: " + name
                + " (every 1.12.2 block is supported except portals, end gateways and moving pistons)");
        }
        return t;
    }
}
