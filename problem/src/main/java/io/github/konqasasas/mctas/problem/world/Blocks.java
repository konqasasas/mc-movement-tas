package io.github.konqasasas.mctas.problem.world;

import io.github.konqasasas.mctas.physics.BlockType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Blocks {
    private static final Map<String, BlockType> SUPPORTED;

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
        m.put("minecraft:ice", BlockType.ICE);
        m.put("minecraft:packed_ice", BlockType.PACKED_ICE);
        m.put("minecraft:air", BlockType.AIR);
        SUPPORTED = Collections.unmodifiableMap(m);
    }

    private Blocks() {
    }

    public static BlockType byId(String id) {
        String key = id.indexOf(':') < 0 ? "minecraft:" + id : id;
        BlockType t = SUPPORTED.get(key);
        if (t == null) {
            throw new IllegalArgumentException("unsupported block in problem format v1: " + id
                + " (supported: " + SUPPORTED.keySet() + ")");
        }
        return t;
    }
}
