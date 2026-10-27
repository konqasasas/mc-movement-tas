package io.github.konqasasas.mctas.problem.world;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.VanillaBlocks;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 */
public final class Blocks {
    /** Plain full cubes that are not in {@link VanillaBlocks} under their own id. */
    private static final Map<String, BlockType> FULL_CUBES;

    static {
        Map<String, BlockType> m = new LinkedHashMap<String, BlockType>();
        String[] fullCubes = {
            "minecraft:stone", "minecraft:grass", "minecraft:dirt", "minecraft:cobblestone", "minecraft:planks",
            "minecraft:bedrock", "minecraft:sand", "minecraft:gravel", "minecraft:log", "minecraft:wool",
            "minecraft:gold_block", "minecraft:iron_block", "minecraft:diamond_block", "minecraft:emerald_block",
            "minecraft:quartz_block", "minecraft:stonebrick", "minecraft:sandstone", "minecraft:obsidian",
            "minecraft:stained_hardened_clay", "minecraft:hardened_clay", "minecraft:concrete",
            "minecraft:brick_block", "minecraft:netherrack", "minecraft:end_stone"
        };
        for (String id : fullCubes) m.put(id, BlockType.STONE);
        FULL_CUBES = Collections.unmodifiableMap(m);
    }

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
        BlockType cube = FULL_CUBES.get(id);
        if (cube != null) {
            return cube;
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
                + " (full cubes: " + FULL_CUBES.keySet() + "; other blocks: see VanillaBlocks)");
        }
        return t;
    }
}
