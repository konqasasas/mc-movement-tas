package io.github.konqasasas.mctas.problem.cli.importer;

import java.util.Map;
import java.util.TreeMap;

/**
 * The 1.12.2 block ids and metadata, as saves and schematics store them, turned into the block state names
 * of problem files.
 *
 * <p>{@link #state} is the state a chunk loads for a stored id and metadata ({@link #stored}): the block's
 * registry name and the properties {@code getStateFromMeta} decodes, written only for the properties that change collision (the
 * ones {@code VanillaBlocks.properties} keeps). Everything else (colour, wood type, stair shape, fence
 * connections) is left out, as {@code problem.world.Blocks} would drop it anyway. A door half stores only
 * half of its properties; {@link #door} completes it from the other half the way
 * {@code BlockDoor.getActualState} does.
 *
 * <p>Written from the 1.12.2 code ({@code Block.registerBlocks} and each block's {@code getStateFromMeta}).
 * The real game checks this table with {@code /mctas checkids} in the recorder mod.
 */
public final class LegacyBlocks {

    /** Registry names by numeric id ({@code Block.registerBlocks}); null where 1.12.2 has no block. */
    private static final String[] NAMES = {
        "air", "stone", "grass", "dirt", "cobblestone", "planks", "sapling", "bedrock",
        "flowing_water", "water", "flowing_lava", "lava", "sand", "gravel", "gold_ore", "iron_ore",
        "coal_ore", "log", "leaves", "sponge", "glass", "lapis_ore", "lapis_block", "dispenser",
        "sandstone", "noteblock", "bed", "golden_rail", "detector_rail", "sticky_piston", "web", "tallgrass",
        "deadbush", "piston", "piston_head", "wool", "piston_extension", "yellow_flower", "red_flower", "brown_mushroom",
        "red_mushroom", "gold_block", "iron_block", "double_stone_slab", "stone_slab", "brick_block", "tnt", "bookshelf",
        "mossy_cobblestone", "obsidian", "torch", "fire", "mob_spawner", "oak_stairs", "chest", "redstone_wire",
        "diamond_ore", "diamond_block", "crafting_table", "wheat", "farmland", "furnace", "lit_furnace", "standing_sign",
        "wooden_door", "ladder", "rail", "stone_stairs", "wall_sign", "lever", "stone_pressure_plate", "iron_door",
        "wooden_pressure_plate", "redstone_ore", "lit_redstone_ore", "unlit_redstone_torch", "redstone_torch", "stone_button", "snow_layer", "ice",
        "snow", "cactus", "clay", "reeds", "jukebox", "fence", "pumpkin", "netherrack",
        "soul_sand", "glowstone", "portal", "lit_pumpkin", "cake", "unpowered_repeater", "powered_repeater", "stained_glass",
        "trapdoor", "monster_egg", "stonebrick", "brown_mushroom_block", "red_mushroom_block", "iron_bars", "glass_pane", "melon_block",
        "pumpkin_stem", "melon_stem", "vine", "fence_gate", "brick_stairs", "stone_brick_stairs", "mycelium", "waterlily",
        "nether_brick", "nether_brick_fence", "nether_brick_stairs", "nether_wart", "enchanting_table", "brewing_stand", "cauldron", "end_portal",
        "end_portal_frame", "end_stone", "dragon_egg", "redstone_lamp", "lit_redstone_lamp", "double_wooden_slab", "wooden_slab", "cocoa",
        "sandstone_stairs", "emerald_ore", "ender_chest", "tripwire_hook", "tripwire", "emerald_block", "spruce_stairs", "birch_stairs",
        "jungle_stairs", "command_block", "beacon", "cobblestone_wall", "flower_pot", "carrots", "potatoes", "wooden_button",
        "skull", "anvil", "trapped_chest", "light_weighted_pressure_plate", "heavy_weighted_pressure_plate", "unpowered_comparator", "powered_comparator", "daylight_detector",
        "redstone_block", "quartz_ore", "hopper", "quartz_block", "quartz_stairs", "activator_rail", "dropper", "stained_hardened_clay",
        "stained_glass_pane", "leaves2", "log2", "acacia_stairs", "dark_oak_stairs", "slime", "barrier", "iron_trapdoor",
        "prismarine", "sea_lantern", "hay_block", "carpet", "hardened_clay", "coal_block", "packed_ice", "double_plant",
        "standing_banner", "wall_banner", "daylight_detector_inverted", "red_sandstone", "red_sandstone_stairs", "double_stone_slab2", "stone_slab2", "spruce_fence_gate",
        "birch_fence_gate", "jungle_fence_gate", "dark_oak_fence_gate", "acacia_fence_gate", "spruce_fence", "birch_fence", "jungle_fence", "dark_oak_fence",
        "acacia_fence", "spruce_door", "birch_door", "jungle_door", "acacia_door", "dark_oak_door", "end_rod", "chorus_plant",
        "chorus_flower", "purpur_block", "purpur_pillar", "purpur_stairs", "purpur_double_slab", "purpur_slab", "end_bricks", "beetroots",
        "grass_path", "end_gateway", "repeating_command_block", "chain_command_block", "frosted_ice", "magma", "nether_wart_block", "red_nether_brick",
        "bone_block", "structure_void", "observer", "white_shulker_box", "orange_shulker_box", "magenta_shulker_box", "light_blue_shulker_box", "yellow_shulker_box",
        "lime_shulker_box", "pink_shulker_box", "gray_shulker_box", "silver_shulker_box", "cyan_shulker_box", "purple_shulker_box", "blue_shulker_box", "brown_shulker_box",
        "green_shulker_box", "red_shulker_box", "black_shulker_box", "white_glazed_terracotta", "orange_glazed_terracotta", "magenta_glazed_terracotta", "light_blue_glazed_terracotta", "yellow_glazed_terracotta",
        "lime_glazed_terracotta", "pink_glazed_terracotta", "gray_glazed_terracotta", "silver_glazed_terracotta", "cyan_glazed_terracotta", "purple_glazed_terracotta", "blue_glazed_terracotta", "brown_glazed_terracotta",
        "green_glazed_terracotta", "red_glazed_terracotta", "black_glazed_terracotta", "concrete", "concrete_powder", null, null, "structure_block",
    };

    /**
     * Bit {@code meta} of entry {@code id} is set where the save's {@code id:meta} can be a block. The game
     * loads a chunk through {@code Block.BLOCK_STATE_IDS}, which holds only the metadata that some state's
     * {@code getMetaFromState} gives (stairs 0 to 7, ladders 2 to 5, ...); any other metadata loads as air.
     * Taken from the id:meta pairs the 1.13 world upgrade knows (minecraft-data {@code legacy.json}), then
     * corrected where {@code /mctas checkids} showed the real game differs (beds 4 to 7, quartz 6 and 10,
     * double plants 12 and 13 load as air; tripwire 2, 3, 6, 7, 10, 11 and 14 do not).
     */
    private static final int[] STORED = {
        0x0001, 0x007f, 0x0001, 0x0007, 0x0001, 0x003f, 0x3f3f, 0x0001,
        0xffff, 0xffff, 0xffff, 0xffff, 0x0003, 0x0001, 0x0001, 0x0001,
        0x0001, 0xffff, 0xffff, 0x0003, 0x0001, 0x0001, 0x0001, 0x3f3f,
        0x0007, 0x0001, 0xff0f, 0x3f3f, 0x3f3f, 0x3f3f, 0x0001, 0x0007,
        0x0001, 0x3f3f, 0x3f3f, 0xffff, 0x3f3f, 0x0001, 0x01ff, 0x0001,
        0x0001, 0x0001, 0x0001, 0xffff, 0xffff, 0x0001, 0x0003, 0x0001,
        0x0001, 0x0001, 0x003e, 0xffff, 0x0001, 0x00ff, 0x003c, 0xffff,
        0x0001, 0x0001, 0x0001, 0x00ff, 0x00ff, 0x003c, 0x003c, 0xffff,
        0x0fff, 0x003c, 0x03ff, 0x00ff, 0x003c, 0xffff, 0x0003, 0x0fff,
        0x0003, 0x0001, 0x0001, 0x003e, 0x003e, 0x3f3f, 0x00ff, 0x0001,
        0x0001, 0xffff, 0x0001, 0xffff, 0x0003, 0x0001, 0x000f, 0x0001,
        0x0001, 0x0001, 0x0006, 0x000f, 0x007f, 0xffff, 0xffff, 0xffff,
        0xffff, 0x003f, 0x000f, 0xc7ff, 0xc7ff, 0x0001, 0x0001, 0x0001,
        0x00ff, 0x00ff, 0xffff, 0xffff, 0x00ff, 0x00ff, 0x0001, 0x0001,
        0x0001, 0x0001, 0x00ff, 0x000f, 0x0001, 0x00ff, 0x000f, 0x0001,
        0x00ff, 0x0001, 0x0001, 0x0001, 0x0001, 0x003f, 0x3f3f, 0x0fff,
        0x00ff, 0x0001, 0x003c, 0xffff, 0x7fff, 0x0001, 0x00ff, 0x00ff,
        0x00ff, 0x3f3f, 0x0001, 0x0003, 0xffff, 0x00ff, 0x00ff, 0x3f3f,
        0x3f3f, 0x0fff, 0x003c, 0xffff, 0xffff, 0xffff, 0xffff, 0xffff,
        0x0001, 0x0001, 0x3d3d, 0x001f, 0x00ff, 0x3f3f, 0x3f3f, 0xffff,
        0xffff, 0x3333, 0x3333, 0x00ff, 0x00ff, 0x0001, 0x0001, 0xffff,
        0x0007, 0x0001, 0x0111, 0xffff, 0x0001, 0x0001, 0x0001, 0x0f3f,
        0xffff, 0x003c, 0xffff, 0x0007, 0x00ff, 0x0101, 0x0101, 0xffff,
        0xffff, 0xffff, 0xffff, 0xffff, 0x0001, 0x0001, 0x0001, 0x0001,
        0x0001, 0x0fff, 0x0fff, 0x0fff, 0x0fff, 0x0fff, 0x003f, 0x0001,
        0x003f, 0x0001, 0x0111, 0x00ff, 0x0001, 0x0101, 0x0001, 0x000f,
        0x0001, 0x0001, 0x3f3f, 0x3f3f, 0x000f, 0x0001, 0x0001, 0x0001,
        0x0111, 0x0001, 0x3f3f, 0x003f, 0x003f, 0x003f, 0x003f, 0x003f,
        0x003f, 0x003f, 0x003f, 0x003f, 0x003f, 0x003f, 0x003f, 0x003f,
        0x003f, 0x003f, 0x003f, 0x000f, 0x000f, 0x000f, 0x000f, 0x000f,
        0x000f, 0x000f, 0x000f, 0x000f, 0x000f, 0x000f, 0x000f, 0x000f,
        0x000f, 0x000f, 0x000f, 0xffff, 0xffff, 0x0000, 0x0000, 0x000f,
    };

    /** {@code EnumFacing.VALUES}, the order of {@code EnumFacing.getFront}. */
    private static final String[] FRONT = {"down", "up", "north", "south", "west", "east"};
    /** {@code EnumFacing.HORIZONTALS}, the order of {@code EnumFacing.getHorizontal}. */
    private static final String[] HORIZONTAL = {"south", "west", "north", "east"};

    private LegacyBlocks() {
    }

    /** The registry name with {@code minecraft:}, or null if 1.12.2 has no block with this id. */
    public static String name(int id) {
        if (id < 0 || id >= NAMES.length || NAMES[id] == null) {
            return null;
        }
        return "minecraft:" + NAMES[id];
    }

    /** False where the game loads this id and metadata as air (see {@link #STORED}). */
    public static boolean stored(int id, int meta) {
        return id >= 0 && id < STORED.length && meta >= 0 && meta < 16 && (STORED[id] >> meta & 1) != 0;
    }

    /** True for the door blocks, whose two halves complete each other ({@link #door}). */
    public static boolean isDoor(int id) {
        return id == 64 || id == 71 || (id >= 193 && id <= 197);
    }

    /**
     * The state {@code getStateFromMeta} gives, as a problem-file name with only the collision properties.
     *
     * @throws IllegalArgumentException for an id 1.12.2 does not have, or metadata the game cannot decode
     */
    public static String state(int id, int meta) {
        return format(id, properties(id, meta));
    }

    /**
     * A door half completed from the block on its other side, as {@code BlockDoor.getActualState}: a lower
     * half takes the hinge of the block above if that is the same door, an upper half takes the facing and
     * open of the block below. {@code otherId} and {@code otherMeta} are that neighbour.
     */
    public static String door(int id, int meta, int otherId, int otherMeta) {
        Map<String, String> p = properties(id, meta);
        if (otherId == id) {
            Map<String, String> o = properties(otherId, otherMeta);
            if (p.get("half").equals("lower")) {
                p.put("hinge", o.get("hinge"));
            } else {
                p.put("facing", o.get("facing"));
                p.put("open", o.get("open"));
            }
        }
        return format(id, p);
    }

    private static String format(int id, Map<String, String> p) {
        StringBuilder sb = new StringBuilder(name(id));
        if (!p.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<String, String> e : p.entrySet()) {
                if (!first) sb.append(',');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static Map<String, String> properties(int id, int meta) {
        if (name(id) == null) {
            throw new IllegalArgumentException("block id " + id + " is not a 1.12.2 block (a mod's block?)");
        }
        if (meta < 0 || meta > 15) {
            throw new IllegalArgumentException("metadata " + meta + " out of range");
        }
        Map<String, String> p = new TreeMap<String, String>();
        switch (id) {
            case 8: case 9: case 10: case 11:
                p.put("level", Integer.toString(meta));
                break;
            case 29: case 33: // BlockPistonBase
                p.put("facing", pistonFacing(id, meta));
                p.put("extended", bool((meta & 8) != 0));
                break;
            case 34: // BlockPistonExtension (the head)
                p.put("facing", pistonFacing(id, meta));
                break;
            case 44: case 126: case 182: case 205: // BlockSlab
                p.put("half", (meta & 8) == 0 ? "bottom" : "top");
                break;
            case 53: case 67: case 108: case 109: case 114: case 128: case 134: case 135: case 136:
            case 156: case 163: case 164: case 180: case 203: // BlockStairs
                p.put("facing", FRONT[5 - (meta & 3)]);
                p.put("half", (meta & 4) == 0 ? "bottom" : "top");
                break;
            case 64: case 71: case 193: case 194: case 195: case 196: case 197: // BlockDoor
                if ((meta & 8) != 0) {
                    p.put("half", "upper");
                    p.put("hinge", (meta & 1) == 0 ? "left" : "right");
                    p.put("facing", "north");
                    p.put("open", "false");
                } else {
                    p.put("half", "lower");
                    p.put("facing", rotateYCCW(HORIZONTAL[meta & 3]));
                    p.put("open", bool((meta & 4) != 0));
                    p.put("hinge", "left");
                }
                break;
            case 65: { // BlockLadder
                String f = FRONT[meta % 6];
                p.put("facing", f.equals("down") || f.equals("up") ? "north" : f);
                break;
            }
            case 78: // BlockSnow
                p.put("layers", Integer.toString((meta & 7) + 1));
                break;
            case 92: // BlockCake
                if (meta > 6) throw badMeta(id, meta);
                p.put("bites", Integer.toString(meta));
                break;
            case 96: case 167: { // BlockTrapDoor
                String[] f = {"north", "south", "west", "east"};
                p.put("facing", f[meta & 3]);
                p.put("open", bool((meta & 4) != 0));
                p.put("half", (meta & 8) == 0 ? "bottom" : "top");
                break;
            }
            case 107: case 183: case 184: case 185: case 186: case 187: // BlockFenceGate
                p.put("facing", HORIZONTAL[meta & 3]);
                p.put("open", bool((meta & 4) != 0));
                break;
            case 120: // BlockEndPortalFrame
                p.put("eye", bool((meta & 4) != 0));
                break;
            case 127: // BlockCocoa
                if (meta >> 2 > 2) throw badMeta(id, meta);
                p.put("facing", HORIZONTAL[meta & 3]);
                p.put("age", Integer.toString(meta >> 2));
                break;
            case 144: // BlockSkull
                p.put("facing", FRONT[(meta & 7) % 6]);
                break;
            case 145: // BlockAnvil
                if (meta >> 2 > 2) throw badMeta(id, meta);
                p.put("facing", HORIZONTAL[meta & 3]);
                break;
            case 198: // BlockEndRod
                p.put("facing", FRONT[meta % 6]);
                break;
            default:
                break;
        }
        return p;
    }

    /** {@code BlockPistonBase.getFacing}: null, which the game cannot store, for 6 and 7. */
    private static String pistonFacing(int id, int meta) {
        int i = meta & 7;
        if (i > 5) throw badMeta(id, meta);
        return FRONT[i];
    }

    private static String rotateYCCW(String f) {
        if (f.equals("north")) return "west";
        if (f.equals("east")) return "north";
        if (f.equals("south")) return "east";
        return "south";
    }

    private static String bool(boolean b) {
        return b ? "true" : "false";
    }

    private static IllegalArgumentException badMeta(int id, int meta) {
        return new IllegalArgumentException("metadata " + meta + " of " + name(id) + " is not a state the game can decode");
    }
}
