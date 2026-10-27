package io.github.konqasasas.mctas.problem.world;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BlockType {
    public static final BlockType AIR = new BlockType("minecraft:air", false, 0.6F);

    private static final Map<String, BlockType> SUPPORTED;

    static {
        Map<String, BlockType> m = new LinkedHashMap<String, BlockType>();
        String[] fullCubes = {
            "minecraft:stone", "minecraft:grass", "minecraft:dirt", "minecraft:cobblestone", "minecraft:planks",
            "minecraft:bedrock", "minecraft:sand", "minecraft:gravel", "minecraft:log", "minecraft:wool",
            "minecraft:gold_block", "minecraft:iron_block", "minecraft:diamond_block", "minecraft:emerald_block",
            "minecraft:quartz_block", "minecraft:stonebrick", "minecraft:sandstone", "minecraft:obsidian",
            "minecraft:stained_hardened_clay", "minecraft:hardened_clay", "minecraft:concrete", "minecraft:glass",
            "minecraft:stained_glass", "minecraft:brick_block", "minecraft:netherrack", "minecraft:end_stone"
        };
        for (String id : fullCubes) m.put(id, new BlockType(id, true, 0.6F));
        m.put("minecraft:ice", new BlockType("minecraft:ice", true, 0.98F));
        m.put("minecraft:packed_ice", new BlockType("minecraft:packed_ice", true, 0.98F));
        m.put(AIR.id, AIR);
        SUPPORTED = Collections.unmodifiableMap(m);
    }

    public final String id;
    public final boolean fullCube;
    public final float slipperiness;

    private BlockType(String id, boolean fullCube, float slipperiness) {
        this.id = id;
        this.fullCube = fullCube;
        this.slipperiness = slipperiness;
    }

    public static BlockType byId(String id) {
        String key = id.indexOf(':') < 0 ? "minecraft:" + id : id;
        BlockType t = SUPPORTED.get(key);
        if (t == null) {
            throw new IllegalArgumentException("unsupported block in problem format v1: " + id
                + " (supported: full cubes listed in BlockType, ice, packed_ice, air)");
        }
        return t;
    }

    public static Map<String, BlockType> supported() {
        return SUPPORTED;
    }
}
