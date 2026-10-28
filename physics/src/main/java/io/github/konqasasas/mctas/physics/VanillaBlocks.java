package io.github.konqasasas.mctas.physics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The 1.12.2 block states the engine can model, by name.
 *
 * <p>A name is the block id followed by the properties that change collision, sorted by key, as in
 * {@code minecraft:oak_stairs[facing=north,half=bottom]}. Properties that only change the look
 * (colour, wood variant, slab material) and the ones the game derives from the neighbours (stair
 * shape, fence connections) are left out: the derived ones are computed from the world, as the game
 * does. {@link #properties(String)} lists the kept keys of a block id, so a mod can build the name of
 * a live block state.
 */
public final class VanillaBlocks {

    private static final Map<String, BlockType> BY_NAME = new LinkedHashMap<String, BlockType>();
    private static final Map<String, List<String>> PROPERTIES = new LinkedHashMap<String, List<String>>();

    private static final String[] HORIZONTAL = {"north", "south", "west", "east"};
    private static final String[] BOOL = {"false", "true"};
    private static final String[] HALF = {"bottom", "top"};

    private static final FaceShape S = FaceShape.SOLID;
    private static final FaceShape U = FaceShape.UNDEFINED;
    private static final FaceShape[] DOWN_SOLID = BlockType.faces(S, U, U);
    private static final FaceShape[] ALL_UNDEFINED = BlockType.faces(U, U, U);
    private static final FaceShape[] ALL_SOLID = BlockType.faces(S, S, S);
    private static final int BOTH = BlockType.EXCEPT_FENCE_WALL | BlockType.EXCEPT_PANE;
    private static final int PISTON = BlockType.EXCEPT_PISTON;

    static {
        put(BlockType.AIR, "minecraft:air");
        put(BlockType.STONE, "minecraft:stone");
        put(BlockType.ICE, "minecraft:ice");
        put(BlockType.PACKED_ICE, "minecraft:packed_ice");

        full("minecraft:double_stone_slab", true, 0);
        full("minecraft:glass", false, BlockType.EXCEPT_FENCE_WALL | PISTON);
        full("minecraft:stained_glass", false, BlockType.EXCEPT_FENCE_WALL | PISTON);
        full("minecraft:glowstone", false, BOTH | PISTON);
        full("minecraft:pumpkin", true, BOTH);
        full("minecraft:melon_block", true, BOTH);

        for (String id : new String[] {"minecraft:stone_slab", "minecraft:wooden_slab", "minecraft:stone_slab2", "minecraft:purpur_slab"}) {
            props(id, "half");
            put(simple(id + "[half=bottom]", box(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D), DOWN_SOLID, 0), null);
            put(simple(id + "[half=top]", box(0.0D, 0.5D, 0.0D, 1.0D, 1.0D, 1.0D), BlockType.faces(U, S, U), 0), null);
        }
        put(simple("minecraft:carpet", box(0.0D, 0.0D, 0.0D, 1.0D, 0.0625D, 1.0D), DOWN_SOLID, 0).notBlockingMovement(), "minecraft:carpet");
        props("minecraft:snow_layer", "layers");
        for (int layers = 1; layers <= 8; layers++) {
            // BlockSnow.getCollisionBoundingBox: one layer lower than it looks.
            Aabb b = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, (double) ((float) (layers - 1) * 0.125F), 1.0D);
            put(simple("minecraft:snow_layer[layers=" + layers + "]", Collections.singletonList(b), DOWN_SOLID, 0).notBlockingMovement(), null);
        }
        for (String id : new String[] {"minecraft:trapdoor", "minecraft:iron_trapdoor"}) {
            props(id, "facing", "half", "open");
            for (String facing : HORIZONTAL) {
                for (String half : HALF) {
                    for (String open : BOOL) {
                        put(trapdoor(id, Facing.valueOf(facing.toUpperCase(java.util.Locale.ROOT)), half.equals("top"), open.equals("true")), null);
                    }
                }
            }
        }
        simple("minecraft:enchanting_table", box(0.0D, 0.0D, 0.0D, 1.0D, 0.75D, 1.0D), DOWN_SOLID);
        props("minecraft:end_portal_frame", "eye");
        Aabb frame = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 0.8125D, 1.0D);
        Aabb eye = new Aabb(0.3125D, 0.8125D, 0.3125D, 0.6875D, 1.0D, 0.6875D);
        put(simple("minecraft:end_portal_frame[eye=false]", Collections.singletonList(frame), DOWN_SOLID, 0), null);
        put(simple("minecraft:end_portal_frame[eye=true]", Arrays.asList(frame, eye), DOWN_SOLID, 0), null);
        simple("minecraft:hopper", Arrays.asList(
                new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 0.625D, 1.0D),
                new Aabb(0.0D, 0.0D, 0.0D, 0.125D, 1.0D, 1.0D),
                new Aabb(0.875D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D),
                new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 0.125D),
                new Aabb(0.0D, 0.0D, 0.875D, 1.0D, 1.0D, 1.0D)), BlockType.faces(U, FaceShape.BOWL, U));
        props("minecraft:cauldron");
        put(simple("minecraft:cauldron", Arrays.asList(
                new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 0.3125D, 1.0D),
                new Aabb(0.0D, 0.0D, 0.0D, 0.125D, 1.0D, 1.0D),
                new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 0.125D),
                new Aabb(0.875D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D),
                new Aabb(0.0D, 0.0D, 0.875D, 1.0D, 1.0D, 1.0D)), BlockType.faces(U, FaceShape.BOWL, S), BOTH | PISTON), null);
        simple("minecraft:brewing_stand", Arrays.asList(
                new Aabb(0.4375D, 0.0D, 0.4375D, 0.5625D, 0.875D, 0.5625D),
                new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 0.125D, 1.0D)), ALL_UNDEFINED);
        simple("minecraft:ender_chest", box(0.0625D, 0.0D, 0.0625D, 0.9375D, 0.875D, 0.9375D), ALL_UNDEFINED);
        simple("minecraft:daylight_detector", box(0.0D, 0.0D, 0.0D, 1.0D, 0.375D, 1.0D), DOWN_SOLID);
        simple("minecraft:daylight_detector_inverted", box(0.0D, 0.0D, 0.0D, 1.0D, 0.375D, 1.0D), DOWN_SOLID);
        props("minecraft:cake", "bites");
        for (int bites = 0; bites <= 6; bites++) {
            double minX = (double) (1 + bites * 2) / 16.0D;
            put(simple("minecraft:cake[bites=" + bites + "]", box(minX, 0.0D, 0.0625D, 0.9375D, 0.5D, 0.9375D), ALL_UNDEFINED, 0), null);
        }
        put(simple("minecraft:flower_pot", box(0.3125D, 0.0D, 0.3125D, 0.6875D, 0.375D, 0.6875D), ALL_UNDEFINED, 0).notBlockingMovement(),
                "minecraft:flower_pot");

        for (String id : new String[] {"minecraft:chest", "minecraft:trapped_chest"}) {
            props(id);
            put(new Chest(id), null);
        }
        for (String id : new String[] {"minecraft:oak_stairs", "minecraft:stone_stairs", "minecraft:brick_stairs",
                "minecraft:stone_brick_stairs", "minecraft:nether_brick_stairs", "minecraft:sandstone_stairs",
                "minecraft:spruce_stairs", "minecraft:birch_stairs", "minecraft:jungle_stairs", "minecraft:quartz_stairs",
                "minecraft:acacia_stairs", "minecraft:dark_oak_stairs", "minecraft:red_sandstone_stairs",
                "minecraft:purpur_stairs"}) {
            props(id, "facing", "half");
            for (String facing : HORIZONTAL) {
                for (String half : HALF) {
                    put(new Stairs(id, Facing.valueOf(facing.toUpperCase(java.util.Locale.ROOT)), half.equals("top")), null);
                }
            }
        }
        for (String id : new String[] {"minecraft:fence", "minecraft:spruce_fence", "minecraft:birch_fence",
                "minecraft:jungle_fence", "minecraft:dark_oak_fence", "minecraft:acacia_fence"}) {
            props(id);
            put(new Fence(id, "wood"), null);
        }
        props("minecraft:nether_brick_fence");
        put(new Fence("minecraft:nether_brick_fence", "rock"), null);
        for (String id : new String[] {"minecraft:fence_gate", "minecraft:spruce_fence_gate", "minecraft:birch_fence_gate",
                "minecraft:jungle_fence_gate", "minecraft:dark_oak_fence_gate", "minecraft:acacia_fence_gate"}) {
            props(id, "facing", "open");
            for (String facing : HORIZONTAL) {
                for (String open : BOOL) {
                    put(new FenceGate(id, Facing.valueOf(facing.toUpperCase(java.util.Locale.ROOT)), open.equals("true")), null);
                }
            }
        }
        props("minecraft:ladder", "facing");
        for (String facing : HORIZONTAL) {
            put(new Ladder(Facing.valueOf(facing.toUpperCase(java.util.Locale.ROOT))), null);
        }
        put(new Vine(), "minecraft:vine");
        put(new Slime(), "minecraft:slime");
        put(new Bed(), "minecraft:bed");
        put(new SoulSand(), "minecraft:soul_sand");
        put(new Web(), "minecraft:web");
        for (String id : new String[] {"minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava"}) {
            props(id, "level");
            for (int level = 0; level < 16; level++) {
                put(new Liquid(id, level), null);
            }
        }
        props("minecraft:cobblestone_wall");
        put(new Wall("minecraft:cobblestone_wall"), null);
        for (String id : new String[] {"minecraft:glass_pane", "minecraft:stained_glass_pane", "minecraft:iron_bars"}) {
            props(id);
            put(new Pane(id), null);
        }
        for (String id : new String[] {"minecraft:wooden_door", "minecraft:spruce_door", "minecraft:birch_door",
                "minecraft:jungle_door", "minecraft:acacia_door", "minecraft:dark_oak_door", "minecraft:iron_door"}) {
            props(id, "facing", "half", "hinge", "open");
            for (String facing : HORIZONTAL) {
                for (String half : new String[] {"lower", "upper"}) {
                    for (String hinge : new String[] {"left", "right"}) {
                        for (String open : BOOL) {
                            put(new Door(id, Facing.valueOf(facing.toUpperCase(java.util.Locale.ROOT)), half.equals("upper"),
                                    hinge.equals("right"), open.equals("true")), null);
                        }
                    }
                }
            }
        }
        props("minecraft:chorus_plant");
        put(new ChorusPlant().notBlockingMovement(), null);
        CODED_IDS = Collections.unmodifiableSet(new HashSet<String>(PROPERTIES.keySet()));
        loadTable();
        loadInteractions();
    }

    /** Block ids with hand-written entries above; every other id comes from {@link #TABLE}. */
    private static final Set<String> CODED_IDS;

    /**
     * Every other 1.12.2 block whose collision depends only on its own state, written from the game by
     * {@code :oracle:writeBlockTable}. One line per state: name, slipperiness (float bits, hex), normal
     * cube, air, blocks movement, flags, the six face shapes as {@link FaceShape} ordinals, and the boxes
     * as {@code minX,minY,minZ,maxX,maxY,maxZ} in hex doubles separated by {@code ;}. Lines starting with
     * {@code props} give the property keys kept in the names of a block id.
     */
    static final String TABLE = "/io/github/konqasasas/mctas/physics/vanilla-blocks.tsv";

    private static void loadTable() {
        InputStream in = VanillaBlocks.class.getResourceAsStream(TABLE);
        if (in == null) {
            return;
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] f = line.split("\t", -1);
                    if (f[0].equals("props")) {
                        props(f[1], f[2].isEmpty() ? new String[0] : f[2].split(","));
                        continue;
                    }
                    FaceShape[] faces = new FaceShape[6];
                    for (int i = 0; i < 6; i++) {
                        faces[i] = FaceShape.values()[f[6].charAt(i) - '0'];
                    }
                    List<Aabb> boxes = new ArrayList<Aabb>();
                    if (f.length > 7 && !f[7].isEmpty()) {
                        for (String b : f[7].split(";")) {
                            String[] c = b.split(",");
                            boxes.add(new Aabb(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]),
                                    Double.parseDouble(c[3]), Double.parseDouble(c[4]), Double.parseDouble(c[5])));
                        }
                    }
                    BlockType t = new BlockType(f[0], boxes, Float.intBitsToFloat((int) Long.parseLong(f[1], 16)),
                            f[2].equals("1"), f[3].equals("1"), faces, Integer.parseInt(f[5]));
                    if (f[4].equals("0") && !t.isAir()) {
                        t.notBlockingMovement();
                    }
                    BY_NAME.put(t.name(), t);
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + TABLE, e);
        }
    }

    /**
     * What right-clicking reads from every state of the catalog, written from the game by
     * {@code :oracle:writeInteractionTable}: name, the view ray can hit it, material replaceable,
     * activates when right-clicked, places as a plain full block, block replaceable, and the selection
     * box ({@code -} for none). {@code dyn} marks a value that depends on the neighbours: the block types
     * below compute those selection boxes, and the others report {@link UnsupportedBlockException}.
     */
    static final String INTERACTION_TABLE = "/io/github/konqasasas/mctas/physics/vanilla-interaction.tsv";

    private static void loadInteractions() {
        InputStream in = VanillaBlocks.class.getResourceAsStream(INTERACTION_TABLE);
        if (in == null) {
            return;
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] f = line.split("\t", -1);
                    BlockType t = BY_NAME.get(f[0]);
                    if (t == null) {
                        throw new IllegalStateException(INTERACTION_TABLE + " names an unknown state " + f[0]);
                    }
                    Aabb box = null;
                    boolean offsetXZ = f[6].startsWith("xz:");
                    String sel = offsetXZ ? f[6].substring(3) : f[6];
                    if (!sel.equals("-") && !sel.equals("dyn")) {
                        String[] c = sel.split(",");
                        box = new Aabb(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]),
                                Double.parseDouble(c[3]), Double.parseDouble(c[4]), Double.parseDouble(c[5]));
                    }
                    t.setInteraction(f[1].equals("1"), f[2].equals("1"), f[3].equals("1"), f[4].equals("1"),
                            f[5].equals("1"), f[5].equals("dyn"), box, sel.equals("dyn"), offsetXZ, f.length > 7 && f[7].equals("1"));
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + INTERACTION_TABLE, e);
        }
    }

    /** True if the entries of this block id are written by hand here rather than read from {@link #TABLE}. */
    public static boolean isCoded(String blockId) {
        return CODED_IDS.contains(blockId);
    }

    private VanillaBlocks() {
    }

    /**
     * The name with the properties a saved or sent block keeps. A door half stores only some of its
     * properties (the lower half facing and open, the upper half the hinge), so the client can receive
     * a placed door with the others changed; for every other block this is the name itself.
     */
    public static String storedName(BlockType t) {
        if (!(t instanceof Door)) {
            return t.name();
        }
        Door d = (Door) t;
        return d.upper ? new Door(d.id, Facing.NORTH, true, d.rightHinge, false).name()
                : new Door(d.id, d.facing, false, false, d.open).name();
    }

    /** The block state with this name, or null if the engine cannot model it. */
    public static BlockType get(String name) {
        return BY_NAME.get(name);
    }

    private static final Map<String, BlockType> PARTIAL = new java.util.concurrent.ConcurrentHashMap<String, BlockType>();

    /**
     * A block named with only some of its kept properties, as problem files written before a property was
     * kept name it (a button without its facing). The states that match must differ in nothing but the
     * selection box; the block moves the player like any of them, and a view ray that reaches it reports
     * {@link UnsupportedBlockException}. Null if no state matches or the matches move the player apart.
     */
    public static BlockType partial(String blockId, Map<String, String> given) {
        String key = name(blockId, given);
        BlockType t = PARTIAL.get(key);
        if (t != null) {
            return t;
        }
        List<String> keys = PROPERTIES.get(blockId);
        if (keys == null || !keys.containsAll(given.keySet())) {
            return null;
        }
        List<BlockType> matches = new ArrayList<BlockType>();
        for (BlockType c : BY_NAME.values()) {
            if (blockId(c.name()).equals(blockId) && parseProperties(c.name()).entrySet().containsAll(given.entrySet())) {
                matches.add(c);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        for (BlockType c : matches) {
            if (!c.sameExceptSelection(matches.get(0))) {
                return null;
            }
        }
        t = matches.get(0).withUnknownSelection(key);
        BlockType prev = PARTIAL.putIfAbsent(key, t);
        return prev != null ? prev : t;
    }

    public static BlockType require(String name) {
        BlockType t = BY_NAME.get(name);
        if (t == null) {
            throw new IllegalArgumentException("unsupported block state " + name);
        }
        return t;
    }

    /** The property keys kept in the names of this block id, or null if the id is not supported. */
    public static List<String> properties(String blockId) {
        return PROPERTIES.get(blockId);
    }

    /** {@code id[k=v,...]} with the keys sorted, or just the id without properties. */
    public static String name(String blockId, Map<String, String> properties) {
        if (properties.isEmpty()) {
            return blockId;
        }
        StringBuilder sb = new StringBuilder(blockId).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<String, String>(properties).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    /** The block id of a name, without the properties. */
    public static String blockId(String name) {
        int i = name.indexOf('[');
        return i < 0 ? name : name.substring(0, i);
    }

    /** The properties of a name, in name order. */
    public static Map<String, String> parseProperties(String name) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        int i = name.indexOf('[');
        if (i >= 0) {
            for (String kv : name.substring(i + 1, name.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                out.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return out;
    }

    /** True for blocks that change the player's motion beyond colliding: slime, beds, soul sand, webs, ladders, vines. */
    public static boolean actsOnPlayer(BlockType t) {
        return t instanceof Slime || t instanceof Bed || t instanceof SoulSand || t instanceof Web
                || t instanceof Ladder || t instanceof Vine;
    }

    public static Collection<BlockType> all() {
        return Collections.unmodifiableCollection(BY_NAME.values());
    }

    private static void props(String id, String... keys) {
        PROPERTIES.put(id, Collections.unmodifiableList(Arrays.asList(keys)));
    }

    private static void put(BlockType type, String id) {
        if (id != null) {
            props(id);
        }
        if (BY_NAME.put(type.name(), type) != null) {
            throw new IllegalStateException("duplicate " + type.name());
        }
    }

    private static void full(String id, boolean normalCube, int except) {
        put(new BlockType(id, Collections.singletonList(BlockType.FULL_BLOCK), 0.6F, normalCube, false, ALL_SOLID, except), id);
    }

    private static void simple(String id, List<Aabb> boxes, FaceShape[] faces) {
        put(simple(id, boxes, faces, 0), id);
    }

    private static BlockType simple(String name, List<Aabb> boxes, FaceShape[] faces, int except) {
        return new BlockType(name, boxes, 0.6F, false, false, faces, except);
    }

    private static List<Aabb> box(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Collections.singletonList(new Aabb(x1, y1, z1, x2, y2, z2));
    }

    private static BlockType trapdoor(String id, Facing facing, boolean top, boolean open) {
        Aabb b;
        if (open) {
            switch (facing) {
                case SOUTH:
                    b = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 0.1875D);
                    break;
                case WEST:
                    b = new Aabb(0.8125D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
                    break;
                case EAST:
                    b = new Aabb(0.0D, 0.0D, 0.0D, 0.1875D, 1.0D, 1.0D);
                    break;
                default:
                    b = new Aabb(0.0D, 0.0D, 0.8125D, 1.0D, 1.0D, 1.0D);
            }
        } else if (top) {
            b = new Aabb(0.0D, 0.8125D, 0.0D, 1.0D, 1.0D, 1.0D);
        } else {
            b = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 0.1875D, 1.0D);
        }
        FaceShape down = !open && !top ? S : U;
        FaceShape up = !open && top ? S : U;
        String name = id + "[facing=" + facing.id() + ",half=" + (top ? "top" : "bottom") + ",open=" + open + "]";
        return new Trapdoor(name, b, BlockType.faces(down, up, U), facing, open);
    }

    /** {@code BlockTrapDoor}: an open trapdoor over a ladder facing the same way is climbable. */
    static final class Trapdoor extends BlockType {
        private final Facing facing;
        private final boolean open;

        Trapdoor(String name, Aabb box, FaceShape[] faces, Facing facing, boolean open) {
            super(name, Collections.singletonList(box), 0.6F, false, false, faces, EXCEPT_FENCE_WALL | EXCEPT_PISTON);
            this.facing = facing;
            this.open = open;
        }

        @Override
        boolean isLadder(BlockWorld world, int x, int y, int z) {
            BlockType below = world.getBlock(x, y - 1, z);
            return open && below instanceof Ladder && ((Ladder) below).facing == facing;
        }
    }

    /** {@code BlockLadder}. */
    static final class Ladder extends BlockType {
        final Facing facing;

        Ladder(Facing facing) {
            super("minecraft:ladder[facing=" + facing.id() + "]", Collections.singletonList(ladderBox(facing)),
                    0.6F, false, false, ALL_UNDEFINED, 0);
            this.facing = facing;
            notBlockingMovement();
        }

        private static Aabb ladderBox(Facing facing) {
            switch (facing) {
                case NORTH:
                    return new Aabb(0.0D, 0.0D, 0.8125D, 1.0D, 1.0D, 1.0D);
                case SOUTH:
                    return new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 0.1875D);
                case WEST:
                    return new Aabb(0.8125D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
                default:
                    return new Aabb(0.0D, 0.0D, 0.0D, 0.1875D, 1.0D, 1.0D);
            }
        }

        @Override
        boolean isLadder(BlockWorld world, int x, int y, int z) {
            return true;
        }
    }

    /** {@code BlockVine}: no collision, climbable. */
    static final class Vine extends BlockType {
        Vine() {
            super("minecraft:vine", Collections.<Aabb>emptyList(), 0.6F, false, false, ALL_UNDEFINED, 0);
            notBlockingMovement();
        }

        @Override
        boolean isLadder(BlockWorld world, int x, int y, int z) {
            return true;
        }
    }

    /** {@code BlockSlime}: bounces when landing and slows walking, unless sneaking. */
    static final class Slime extends BlockType {
        Slime() {
            super("minecraft:slime", Collections.singletonList(FULL_BLOCK), 0.8F, true, false, ALL_SOLID, 0);
        }

        @Override
        void onLanded(PlayerState s) {
            if (s.isSneaking()) {
                super.onLanded(s);
            } else if (s.motionY < 0.0D) {
                s.motionY = -s.motionY;
            }
        }

        @Override
        void onEntityWalk(PlayerState s) {
            if (Math.abs(s.motionY) < 0.1D && !s.isSneaking()) {
                double d0 = 0.4D + Math.abs(s.motionY) * 0.2D;
                s.motionX *= d0;
                s.motionZ *= d0;
            }
        }
    }

    /** {@code BlockBed}: a weaker bounce than slime. */
    static final class Bed extends BlockType {
        Bed() {
            super("minecraft:bed", Collections.singletonList(new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 0.5625D, 1.0D)),
                    0.6F, false, false, ALL_UNDEFINED, 0);
        }

        @Override
        void onLanded(PlayerState s) {
            if (s.isSneaking()) {
                super.onLanded(s);
            } else if (s.motionY < 0.0D) {
                s.motionY = -s.motionY * 0.6600000262260437D;
            }
        }
    }

    /** {@code BlockSoulSand}: every overlapped block slows the horizontal motion. */
    static final class SoulSand extends BlockType {
        SoulSand() {
            super("minecraft:soul_sand", Collections.singletonList(new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 0.875D, 1.0D)),
                    0.6F, true, false, ALL_SOLID, 0);
        }

        @Override
        void onEntityCollision(PlayerState s) {
            s.motionX *= 0.4D;
            s.motionZ *= 0.4D;
        }
    }

    /** {@code BlockWeb}: {@code Entity.setInWeb}. */
    static final class Web extends BlockType {
        Web() {
            super("minecraft:web", Collections.<Aabb>emptyList(), 0.6F, false, false, ALL_UNDEFINED, 0);
            notBlockingMovement();
        }

        @Override
        void onEntityCollision(PlayerState s) {
            s.inWeb = true;
            s.fallDistance = 0.0F;
        }
    }

    /**
     * {@code BlockLiquid}: water and lava, still or flowing, with no collision. The player's movement
     * in them is in {@link Physics}; this class gives the surface height and the current.
     */
    public static final class Liquid extends BlockType {
        final boolean lava;
        final int level;

        Liquid(String id, int level) {
            super(id + "[level=" + level + "]", Collections.<Aabb>emptyList(), 0.6F, false, false, ALL_UNDEFINED, 0);
            this.lava = id.endsWith("lava");
            this.level = level;
            notBlockingMovement();
        }

        public boolean isLava() {
            return lava;
        }

        /** {@code BlockLiquid.getLiquidHeightPercent}: the part of the block above the surface. */
        static float heightPercent(int level) {
            if (level >= 8) {
                level = 0;
            }
            return (float) (level + 1) / 9.0F;
        }

        /** {@code getRenderedDepth} as seen from this liquid: -1 for anything of another material. */
        private int renderedDepth(BlockType t) {
            if (!(t instanceof Liquid) || ((Liquid) t).lava != lava) {
                return -1;
            }
            int i = ((Liquid) t).level;
            return i >= 8 ? 0 : i;
        }

        /** {@code BlockLiquid.causesDownwardCurrent}. */
        private boolean causesDownwardCurrent(BlockWorld world, int x, int y, int z, Facing side) {
            BlockType t = world.getBlock(x, y, z);
            if (t instanceof Liquid && ((Liquid) t).lava == lava) {
                return false;
            }
            if (side == Facing.UP) {
                return true;
            }
            if (t.isIceMaterial()) {
                // Material.ICE; packed ice has a material of its own.
                return false;
            }
            boolean flag = t.isExcept(EXCEPT_PISTON) || t instanceof Stairs;
            return !flag && t.faceShape(world, x, y, z, side) == FaceShape.SOLID;
        }

        /** {@code BlockLiquid.getFlow}, as {x, y, z}. */
        public double[] flow(BlockWorld world, int x, int y, int z) {
            double d0 = 0.0D;
            double d1 = 0.0D;
            double d2 = 0.0D;
            int i = renderedDepth(this);
            for (Facing f : Facing.HORIZONTALS) {
                int nx = x + f.dx;
                int nz = z + f.dz;
                BlockType n = world.getBlock(nx, y, nz);
                int j = renderedDepth(n);
                if (j < 0) {
                    if (!n.blocksMovement()) {
                        j = renderedDepth(world.getBlock(nx, y - 1, nz));
                        if (j >= 0) {
                            int k = j - (i - 8);
                            d0 += (double) (f.dx * k);
                            d1 += (double) (f.dy * k);
                            d2 += (double) (f.dz * k);
                        }
                    }
                } else if (j >= 0) {
                    int l = j - i;
                    d0 += (double) (f.dx * l);
                    d1 += (double) (f.dy * l);
                    d2 += (double) (f.dz * l);
                }
            }
            double[] v = {d0, d1, d2};
            if (level >= 8) {
                for (Facing f : Facing.HORIZONTALS) {
                    int nx = x + f.dx;
                    int nz = z + f.dz;
                    if (causesDownwardCurrent(world, nx, y, nz, f) || causesDownwardCurrent(world, nx, y + 1, nz, f)) {
                        v = normalize(v);
                        v[1] += -6.0D;
                        break;
                    }
                }
            }
            return normalize(v);
        }

        /** {@code Vec3d.normalize}. */
        static double[] normalize(double[] v) {
            double d0 = (double) McMath.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            return d0 < 1.0E-4D ? new double[3] : new double[] {v[0] / d0, v[1] / d0, v[2] / d0};
        }
    }

    /**
     * {@code BlockDoor}: one panel whose side depends on facing, open and hinge. The lower half stores
     * facing and open, the upper half the hinge; each takes the others from the other half when that is
     * the same door.
     */
    static final class Door extends BlockType {
        private static final Aabb SOUTH_AABB = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 0.1875D);
        private static final Aabb NORTH_AABB = new Aabb(0.0D, 0.0D, 0.8125D, 1.0D, 1.0D, 1.0D);
        private static final Aabb WEST_AABB = new Aabb(0.8125D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        private static final Aabb EAST_AABB = new Aabb(0.0D, 0.0D, 0.0D, 0.1875D, 1.0D, 1.0D);

        private final String id;
        private final Facing facing;
        private final boolean upper;
        private final boolean rightHinge;
        private final boolean open;

        Door(String id, Facing facing, boolean upper, boolean rightHinge, boolean open) {
            super(id + "[facing=" + facing.id() + ",half=" + (upper ? "upper" : "lower") + ",hinge="
                    + (rightHinge ? "right" : "left") + ",open=" + open + "]",
                    Collections.<Aabb>emptyList(), 0.6F, false, false, ALL_UNDEFINED, 0);
            this.id = id;
            this.facing = facing;
            this.upper = upper;
            this.rightHinge = rightHinge;
            this.open = open;
        }

        @Override
        public void addCollisionBoxes(BlockWorld world, int x, int y, int z, Aabb area, List<Aabb> out) {
            Facing f = facing;
            boolean o = open;
            boolean right = rightHinge;
            BlockType other = world.getBlock(x, y + (upper ? -1 : 1), z);
            if (other instanceof Door && ((Door) other).id.equals(id)) {
                Door d = (Door) other;
                if (upper) {
                    f = d.facing;
                    o = d.open;
                } else {
                    right = d.rightHinge;
                }
            }
            Aabb b;
            switch (f) {
                case SOUTH:
                    b = !o ? SOUTH_AABB : (right ? EAST_AABB : WEST_AABB);
                    break;
                case WEST:
                    b = !o ? WEST_AABB : (right ? SOUTH_AABB : NORTH_AABB);
                    break;
                case NORTH:
                    b = !o ? NORTH_AABB : (right ? WEST_AABB : EAST_AABB);
                    break;
                default:
                    b = !o ? EAST_AABB : (right ? NORTH_AABB : SOUTH_AABB);
            }
            add(x, y, z, area, out, b);
        }

        /** {@code BlockDoor.getBoundingBox} is the collision box. */
        @Override
        public Aabb selectionBox(BlockWorld world, int x, int y, int z) {
            return localCollisionBoxes(this, world, x, y, z).get(0);
        }
    }

    /** {@code BlockChorusPlant}: a core with an arm towards each chorus plant or flower, and down to end stone. */
    static final class ChorusPlant extends BlockType {
        private static final Aabb CORE = new Aabb(0.1875D, 0.1875D, 0.1875D, 0.8125D, 0.8125D, 0.8125D);
        private static final Aabb WEST = new Aabb(0.0D, 0.1875D, 0.1875D, 0.1875D, 0.8125D, 0.8125D);
        private static final Aabb EAST = new Aabb(0.8125D, 0.1875D, 0.1875D, 1.0D, 0.8125D, 0.8125D);
        private static final Aabb UP = new Aabb(0.1875D, 0.8125D, 0.1875D, 0.8125D, 1.0D, 0.8125D);
        private static final Aabb DOWN = new Aabb(0.1875D, 0.0D, 0.1875D, 0.8125D, 0.1875D, 0.8125D);
        private static final Aabb NORTH = new Aabb(0.1875D, 0.1875D, 0.0D, 0.8125D, 0.8125D, 0.1875D);
        private static final Aabb SOUTH = new Aabb(0.1875D, 0.1875D, 0.8125D, 0.8125D, 0.8125D, 1.0D);

        ChorusPlant() {
            super("minecraft:chorus_plant", Collections.<Aabb>emptyList(), 0.6F, false, false, ALL_UNDEFINED, 0);
        }

        private static boolean connects(BlockType t) {
            return t instanceof ChorusPlant || blockId(t.name()).equals("minecraft:chorus_flower");
        }

        @Override
        public void addCollisionBoxes(BlockWorld world, int x, int y, int z, Aabb area, List<Aabb> out) {
            BlockType below = world.getBlock(x, y - 1, z);
            add(x, y, z, area, out, CORE);
            if (connects(world.getBlock(x - 1, y, z))) {
                add(x, y, z, area, out, WEST);
            }
            if (connects(world.getBlock(x + 1, y, z))) {
                add(x, y, z, area, out, EAST);
            }
            if (connects(world.getBlock(x, y + 1, z))) {
                add(x, y, z, area, out, UP);
            }
            if (connects(below) || below.name().equals("minecraft:end_stone")) {
                add(x, y, z, area, out, DOWN);
            }
            if (connects(world.getBlock(x, y, z - 1))) {
                add(x, y, z, area, out, NORTH);
            }
            if (connects(world.getBlock(x, y, z + 1))) {
                add(x, y, z, area, out, SOUTH);
            }
        }

        /** {@code BlockChorusPlant.getBoundingBox}: one box around the core and every arm. */
        @Override
        public Aabb selectionBox(BlockWorld world, int x, int y, int z) {
            BlockType below = world.getBlock(x, y - 1, z);
            double lo = 0.1875F;
            double hi = 0.8125F;
            return new Aabb(connects(world.getBlock(x - 1, y, z)) ? 0.0D : lo,
                    connects(below) || below.name().equals("minecraft:end_stone") ? 0.0D : lo,
                    connects(world.getBlock(x, y, z - 1)) ? 0.0D : lo,
                    connects(world.getBlock(x + 1, y, z)) ? 1.0D : hi,
                    connects(world.getBlock(x, y + 1, z)) ? 1.0D : hi,
                    connects(world.getBlock(x, y, z + 1)) ? 1.0D : hi);
        }
    }

    private static String stateName(String id, Facing facing, String key, String value) {
        return id + "[facing=" + facing.id() + "," + key + "=" + value + "]";
    }

    // ---- blocks whose shape depends on the neighbours ----

    private static final Aabb EVERYWHERE = new Aabb(-3.0E7D, -1.0E3D, -3.0E7D, 3.0E7D, 1.0E3D, 3.0E7D);

    /** The collision boxes of the block at (x, y, z), block-local, in the order the game adds them. */
    static List<Aabb> localCollisionBoxes(BlockType t, BlockWorld world, int x, int y, int z) {
        List<Aabb> out = new ArrayList<Aabb>();
        t.addCollisionBoxes(world, x, y, z, EVERYWHERE, out);
        for (int i = 0; i < out.size(); i++) {
            Aabb b = out.get(i);
            out.set(i, new Aabb(b.minX - x, b.minY - y, b.minZ - z, b.maxX - x, b.maxY - y, b.maxZ - z));
        }
        return out;
    }

    /** Fence, pane and wall-like selection boxes: a centre column reaching out to each connected side. */
    private static Aabb reach(double lo, double hi, double height, boolean n, boolean e, boolean s, boolean w) {
        return new Aabb(w ? 0.0D : lo, 0.0D, n ? 0.0D : lo, e ? 1.0D : hi, height, s ? 1.0D : hi);
    }

    /** {@code BlockChest}: the box reaches into a neighbouring chest of the same kind. */
    static final class Chest extends BlockType {
        private static final Aabb NORTH = new Aabb(0.0625D, 0.0D, 0.0D, 0.9375D, 0.875D, 0.9375D);
        private static final Aabb SOUTH = new Aabb(0.0625D, 0.0D, 0.0625D, 0.9375D, 0.875D, 1.0D);
        private static final Aabb WEST = new Aabb(0.0D, 0.0D, 0.0625D, 0.9375D, 0.875D, 0.9375D);
        private static final Aabb EAST = new Aabb(0.0625D, 0.0D, 0.0625D, 1.0D, 0.875D, 0.9375D);
        private static final Aabb SINGLE = new Aabb(0.0625D, 0.0D, 0.0625D, 0.9375D, 0.875D, 0.9375D);

        Chest(String id) {
            super(id, Collections.<Aabb>emptyList(), 0.6F, false, false, ALL_UNDEFINED, 0);
        }

        @Override
        public void addCollisionBoxes(BlockWorld world, int x, int y, int z, Aabb area, List<Aabb> out) {
            Aabb b;
            if (world.getBlock(x, y, z - 1) == this) {
                b = NORTH;
            } else if (world.getBlock(x, y, z + 1) == this) {
                b = SOUTH;
            } else if (world.getBlock(x - 1, y, z) == this) {
                b = WEST;
            } else {
                b = world.getBlock(x + 1, y, z) == this ? EAST : SINGLE;
            }
            add(x, y, z, area, out, b);
        }

        /** {@code BlockChest.getBoundingBox} is the collision box. */
        @Override
        public Aabb selectionBox(BlockWorld world, int x, int y, int z) {
            return localCollisionBoxes(this, world, x, y, z).get(0);
        }
    }

    /** {@code BlockStairs}, with the shape from {@code getStairsShape}. */
    static final class Stairs extends BlockType {
        private static final int STRAIGHT = 0;
        private static final int INNER_LEFT = 1;
        private static final int INNER_RIGHT = 2;
        private static final int OUTER_LEFT = 3;
        private static final int OUTER_RIGHT = 4;

        private static final Aabb SLAB_TOP = new Aabb(0.0D, 0.5D, 0.0D, 1.0D, 1.0D, 1.0D);
        private static final Aabb QTR_TOP_WEST = new Aabb(0.0D, 0.5D, 0.0D, 0.5D, 1.0D, 1.0D);
        private static final Aabb QTR_TOP_EAST = new Aabb(0.5D, 0.5D, 0.0D, 1.0D, 1.0D, 1.0D);
        private static final Aabb QTR_TOP_NORTH = new Aabb(0.0D, 0.5D, 0.0D, 1.0D, 1.0D, 0.5D);
        private static final Aabb QTR_TOP_SOUTH = new Aabb(0.0D, 0.5D, 0.5D, 1.0D, 1.0D, 1.0D);
        private static final Aabb OCT_TOP_NW = new Aabb(0.0D, 0.5D, 0.0D, 0.5D, 1.0D, 0.5D);
        private static final Aabb OCT_TOP_NE = new Aabb(0.5D, 0.5D, 0.0D, 1.0D, 1.0D, 0.5D);
        private static final Aabb OCT_TOP_SW = new Aabb(0.0D, 0.5D, 0.5D, 0.5D, 1.0D, 1.0D);
        private static final Aabb OCT_TOP_SE = new Aabb(0.5D, 0.5D, 0.5D, 1.0D, 1.0D, 1.0D);
        private static final Aabb SLAB_BOTTOM = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D);
        private static final Aabb QTR_BOT_WEST = new Aabb(0.0D, 0.0D, 0.0D, 0.5D, 0.5D, 1.0D);
        private static final Aabb QTR_BOT_EAST = new Aabb(0.5D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D);
        private static final Aabb QTR_BOT_NORTH = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 0.5D);
        private static final Aabb QTR_BOT_SOUTH = new Aabb(0.0D, 0.0D, 0.5D, 1.0D, 0.5D, 1.0D);
        private static final Aabb OCT_BOT_NW = new Aabb(0.0D, 0.0D, 0.0D, 0.5D, 0.5D, 0.5D);
        private static final Aabb OCT_BOT_NE = new Aabb(0.5D, 0.0D, 0.0D, 1.0D, 0.5D, 0.5D);
        private static final Aabb OCT_BOT_SW = new Aabb(0.0D, 0.0D, 0.5D, 0.5D, 0.5D, 1.0D);
        private static final Aabb OCT_BOT_SE = new Aabb(0.5D, 0.0D, 0.5D, 1.0D, 0.5D, 1.0D);

        final Facing facing;
        final boolean top;

        Stairs(String id, Facing facing, boolean top) {
            super(id + "[facing=" + facing.id() + ",half=" + (top ? "top" : "bottom") + "]",
                    Collections.<Aabb>emptyList(), 0.6F, false, false, ALL_UNDEFINED, 0);
            this.facing = facing;
            this.top = top;
        }

        private int shape(BlockWorld w, int x, int y, int z) {
            BlockType front = w.getBlock(x + facing.dx, y, z + facing.dz);
            if (front instanceof Stairs && ((Stairs) front).top == top) {
                Facing f1 = ((Stairs) front).facing;
                if (!f1.sameAxis(facing) && isDifferentStairs(w, x, y, z, f1.opposite())) {
                    return f1 == facing.rotateYCCW() ? OUTER_LEFT : OUTER_RIGHT;
                }
            }
            BlockType back = w.getBlock(x - facing.dx, y, z - facing.dz);
            if (back instanceof Stairs && ((Stairs) back).top == top) {
                Facing f2 = ((Stairs) back).facing;
                if (!f2.sameAxis(facing) && isDifferentStairs(w, x, y, z, f2)) {
                    return f2 == facing.rotateYCCW() ? INNER_LEFT : INNER_RIGHT;
                }
            }
            return STRAIGHT;
        }

        private boolean isDifferentStairs(BlockWorld w, int x, int y, int z, Facing dir) {
            BlockType b = w.getBlock(x + dir.dx, y, z + dir.dz);
            return !(b instanceof Stairs) || ((Stairs) b).facing != facing || ((Stairs) b).top != top;
        }

        @Override
        public void addCollisionBoxes(BlockWorld world, int x, int y, int z, Aabb area, List<Aabb> out) {
            int shape = shape(world, x, y, z);
            add(x, y, z, area, out, top ? SLAB_TOP : SLAB_BOTTOM);
            if (shape == STRAIGHT || shape == INNER_LEFT || shape == INNER_RIGHT) {
                add(x, y, z, area, out, quarter());
            }
            if (shape != STRAIGHT) {
                add(x, y, z, area, out, eighth(shape));
            }
        }

        // The game swaps the top and bottom boxes here, so a top stair's step hangs below its slab.
        private Aabb quarter() {
            switch (facing) {
                case SOUTH:
                    return top ? QTR_BOT_SOUTH : QTR_TOP_SOUTH;
                case WEST:
                    return top ? QTR_BOT_WEST : QTR_TOP_WEST;
                case EAST:
                    return top ? QTR_BOT_EAST : QTR_TOP_EAST;
                default:
                    return top ? QTR_BOT_NORTH : QTR_TOP_NORTH;
            }
        }

        private Aabb eighth(int shape) {
            Facing f;
            switch (shape) {
                case OUTER_RIGHT:
                    f = facing.rotateY();
                    break;
                case INNER_RIGHT:
                    f = facing.opposite();
                    break;
                case INNER_LEFT:
                    f = facing.rotateYCCW();
                    break;
                default:
                    f = facing;
            }
            switch (f) {
                case SOUTH:
                    return top ? OCT_BOT_SE : OCT_TOP_SE;
                case WEST:
                    return top ? OCT_BOT_SW : OCT_TOP_SW;
                case EAST:
                    return top ? OCT_BOT_NE : OCT_TOP_NE;
                default:
                    return top ? OCT_BOT_NW : OCT_TOP_NW;
            }
        }

        @Override
        public FaceShape faceShape(BlockWorld world, int x, int y, int z, Facing face) {
            if (face.isVertical()) {
                return (face == Facing.UP) == top ? S : U;
            }
            switch (shape(world, x, y, z)) {
                case INNER_RIGHT:
                    return facing != face && facing != face.rotateYCCW() ? U : S;
                case INNER_LEFT:
                    return facing != face && facing != face.rotateY() ? U : S;
                case STRAIGHT:
                    return facing == face ? S : U;
                default:
                    return U;
            }
        }
    
        /**
         * {@code BlockStairs.collisionRayTrace}: each collision box is traced, and the hit farthest from
         * the end of the ray (the first one along it) wins.
         */
        @Override
        Placement.Hit rayTrace(BlockWorld world, int x, int y, int z, double[] start, double[] end) {
            Placement.Hit best = null;
            double d1 = 0.0D;
            for (Aabb b : localCollisionBoxes(this, world, x, y, z)) {
                Placement.Hit h = Placement.rayTrace(x, y, z, start, end, b);
                if (h != null) {
                    double d0 = Placement.squareDistance(end, new double[] {h.hitX, h.hitY, h.hitZ});
                    if (d0 > d1) {
                        best = h;
                        d1 = d0;
                    }
                }
            }
            return best;
        }
    }

    /** {@code BlockFenceGate}: a fixed shape, but fences and walls connect to its sides. */
    static final class FenceGate extends BlockType {
        private final Facing facing;

        FenceGate(String id, Facing facing, boolean open) {
            super(stateName(id, facing, "open", String.valueOf(open)),
                    open ? Collections.<Aabb>emptyList() : Collections.singletonList(facing.isXAxis()
                            ? new Aabb(0.375D, 0.0D, 0.0D, 0.625D, 1.5D, 1.0D)
                            : new Aabb(0.0D, 0.0D, 0.375D, 1.0D, 1.5D, 0.625D)),
                    0.6F, false, false, gateFaces(facing), 0);
            this.facing = facing;
        }

        /** {@code BlockFenceGate.getBoundingBox}: lower when a cobblestone wall is at either side. */
        @Override
        public Aabb selectionBox(BlockWorld world, int x, int y, int z) {
            boolean inWall = facing.isXAxis()
                    ? isWall(world.getBlock(x, y, z - 1)) || isWall(world.getBlock(x, y, z + 1))
                    : isWall(world.getBlock(x - 1, y, z)) || isWall(world.getBlock(x + 1, y, z));
            double h = inWall ? 0.8125D : 1.0D;
            return facing.isXAxis() ? new Aabb(0.375D, 0.0D, 0.0D, 0.625D, h, 1.0D)
                    : new Aabb(0.0D, 0.0D, 0.375D, 1.0D, h, 0.625D);
        }

        private static boolean isWall(BlockType t) {
            return blockId(t.name()).equals("minecraft:cobblestone_wall");
        }

        private static FaceShape[] gateFaces(Facing facing) {
            FaceShape[] f = new FaceShape[6];
            for (Facing face : Facing.values()) {
                f[face.ordinal()] = !face.isVertical() && facing.sameAxis(face.rotateY()) ? FaceShape.MIDDLE_POLE : U;
            }
            return f;
        }
    }

    /** {@code BlockFence}. Fences of the same material connect; wood and nether brick do not. */
    static final class Fence extends BlockType {
        private static final Aabb PILLAR = new Aabb(0.375D, 0.0D, 0.375D, 0.625D, 1.5D, 0.625D);
        private static final Aabb SOUTH = new Aabb(0.375D, 0.0D, 0.625D, 0.625D, 1.5D, 1.0D);
        private static final Aabb WEST = new Aabb(0.0D, 0.0D, 0.375D, 0.375D, 1.5D, 0.625D);
        private static final Aabb NORTH = new Aabb(0.375D, 0.0D, 0.0D, 0.625D, 1.5D, 0.375D);
        private static final Aabb EAST = new Aabb(0.625D, 0.0D, 0.375D, 1.0D, 1.5D, 0.625D);

        final String material;

        Fence(String id, String material) {
            super(id, Collections.<Aabb>emptyList(), 0.6F, false, false,
                    BlockType.faces(FaceShape.CENTER, FaceShape.CENTER, FaceShape.MIDDLE_POLE), 0);
            this.material = material;
        }

        private boolean connects(BlockWorld w, int x, int y, int z, Facing dir) {
            int nx = x + dir.dx;
            int nz = z + dir.dz;
            BlockType n = w.getBlock(nx, y, nz);
            FaceShape shape = n.faceShape(w, nx, y, nz, dir.opposite());
            boolean pole = shape == FaceShape.MIDDLE_POLE
                    && (n instanceof FenceGate || n instanceof Fence && ((Fence) n).material.equals(material));
            return !n.isExcept(EXCEPT_FENCE_WALL) && shape == FaceShape.SOLID || pole;
        }

        @Override
        public void addCollisionBoxes(BlockWorld world, int x, int y, int z, Aabb area, List<Aabb> out) {
            add(x, y, z, area, out, PILLAR);
            if (connects(world, x, y, z, Facing.NORTH)) {
                add(x, y, z, area, out, NORTH);
            }
            if (connects(world, x, y, z, Facing.EAST)) {
                add(x, y, z, area, out, EAST);
            }
            if (connects(world, x, y, z, Facing.SOUTH)) {
                add(x, y, z, area, out, SOUTH);
            }
            if (connects(world, x, y, z, Facing.WEST)) {
                add(x, y, z, area, out, WEST);
            }
        }

        /** {@code BlockFence.getBoundingBox}: {@code BOUNDING_BOXES} by the connections, one block high. */
        @Override
        public Aabb selectionBox(BlockWorld world, int x, int y, int z) {
            return reach(0.375D, 0.625D, 1.0D, connects(world, x, y, z, Facing.NORTH),
                    connects(world, x, y, z, Facing.EAST), connects(world, x, y, z, Facing.SOUTH),
                    connects(world, x, y, z, Facing.WEST));
        }
    }

    /** {@code BlockWall}: one box, 1.5 high, picked by the connections. */
    static final class Wall extends BlockType {
        private static final Aabb[] CLIP = new Aabb[16];
        /** {@code AABB_BY_INDEX}, the selection boxes: one block high, or 0.875 for a straight wall. */
        private static final Aabb[] SELECT = new Aabb[16];

        static {
            double[][] b = {
                    {0.25D, 0.25D, 0.75D, 0.75D}, {0.25D, 0.25D, 0.75D, 1.0D}, {0.0D, 0.25D, 0.75D, 0.75D},
                    {0.0D, 0.25D, 0.75D, 1.0D}, {0.25D, 0.0D, 0.75D, 0.75D}, {0.3125D, 0.0D, 0.6875D, 1.0D},
                    {0.0D, 0.0D, 0.75D, 0.75D}, {0.0D, 0.0D, 0.75D, 1.0D}, {0.25D, 0.25D, 1.0D, 0.75D},
                    {0.25D, 0.25D, 1.0D, 1.0D}, {0.0D, 0.3125D, 1.0D, 0.6875D}, {0.0D, 0.25D, 1.0D, 1.0D},
                    {0.25D, 0.0D, 1.0D, 0.75D}, {0.25D, 0.0D, 1.0D, 1.0D}, {0.0D, 0.0D, 1.0D, 0.75D},
                    {0.0D, 0.0D, 1.0D, 1.0D}};
            for (int i = 0; i < 16; i++) {
                // AABB_BY_INDEX[i].setMaxY(1.5D)
                CLIP[i] = new Aabb(b[i][0], 0.0D, b[i][1], b[i][2], 1.5D, b[i][3]);
                SELECT[i] = new Aabb(b[i][0], 0.0D, b[i][1], b[i][2], i == 5 || i == 10 ? 0.875D : 1.0D, b[i][3]);
            }
        }

        Wall(String id) {
            super(id, Collections.<Aabb>emptyList(), 0.6F, false, false,
                    BlockType.faces(FaceShape.CENTER_BIG, FaceShape.CENTER_BIG, FaceShape.MIDDLE_POLE_THICK), 0);
        }

        private static boolean connects(BlockWorld w, int x, int y, int z, Facing dir) {
            int nx = x + dir.dx;
            int nz = z + dir.dz;
            BlockType n = w.getBlock(nx, y, nz);
            FaceShape shape = n.faceShape(w, nx, y, nz, dir.opposite());
            boolean pole = shape == FaceShape.MIDDLE_POLE_THICK || shape == FaceShape.MIDDLE_POLE && n instanceof FenceGate;
            return !n.isExcept(EXCEPT_FENCE_WALL) && shape == FaceShape.SOLID || pole;
        }

        @Override
        public void addCollisionBoxes(BlockWorld world, int x, int y, int z, Aabb area, List<Aabb> out) {
            add(x, y, z, area, out, CLIP[index(world, x, y, z)]);
        }

        @Override
        public Aabb selectionBox(BlockWorld world, int x, int y, int z) {
            return SELECT[index(world, x, y, z)];
        }

        private static int index(BlockWorld world, int x, int y, int z) {
            int i = 0;
            // Bits by EnumFacing.getHorizontalIndex: south 0, west 1, north 2, east 3.
            if (connects(world, x, y, z, Facing.NORTH)) {
                i |= 4;
            }
            if (connects(world, x, y, z, Facing.EAST)) {
                i |= 8;
            }
            if (connects(world, x, y, z, Facing.SOUTH)) {
                i |= 1;
            }
            if (connects(world, x, y, z, Facing.WEST)) {
                i |= 2;
            }
            return i;
        }
    }

    /** {@code BlockPane}: glass panes and iron bars. */
    static final class Pane extends BlockType {
        private static final Aabb POST = new Aabb(0.4375D, 0.0D, 0.4375D, 0.5625D, 1.0D, 0.5625D);
        private static final Aabb SOUTH = new Aabb(0.4375D, 0.0D, 0.4375D, 0.5625D, 1.0D, 1.0D);
        private static final Aabb WEST = new Aabb(0.0D, 0.0D, 0.4375D, 0.5625D, 1.0D, 0.5625D);
        private static final Aabb NORTH = new Aabb(0.4375D, 0.0D, 0.0D, 0.5625D, 1.0D, 0.5625D);
        private static final Aabb EAST = new Aabb(0.4375D, 0.0D, 0.4375D, 1.0D, 1.0D, 0.5625D);

        Pane(String id) {
            super(id, Collections.<Aabb>emptyList(), 0.6F, false, false,
                    BlockType.faces(FaceShape.CENTER_SMALL, FaceShape.CENTER_SMALL, FaceShape.MIDDLE_POLE_THIN), 0);
        }

        private static boolean attaches(BlockWorld w, int x, int y, int z, Facing dir) {
            int nx = x + dir.dx;
            int nz = z + dir.dz;
            BlockType n = w.getBlock(nx, y, nz);
            FaceShape shape = n.faceShape(w, nx, y, nz, dir.opposite());
            return !n.isExcept(EXCEPT_PANE) && shape == FaceShape.SOLID || shape == FaceShape.MIDDLE_POLE_THIN;
        }

        @Override
        public void addCollisionBoxes(BlockWorld world, int x, int y, int z, Aabb area, List<Aabb> out) {
            add(x, y, z, area, out, POST);
            if (attaches(world, x, y, z, Facing.NORTH)) {
                add(x, y, z, area, out, NORTH);
            }
            if (attaches(world, x, y, z, Facing.SOUTH)) {
                add(x, y, z, area, out, SOUTH);
            }
            if (attaches(world, x, y, z, Facing.EAST)) {
                add(x, y, z, area, out, EAST);
            }
            if (attaches(world, x, y, z, Facing.WEST)) {
                add(x, y, z, area, out, WEST);
            }
        }

        /** {@code BlockPane.getBoundingBox}: {@code AABB_BY_INDEX} by the connections. */
        @Override
        public Aabb selectionBox(BlockWorld world, int x, int y, int z) {
            return reach(0.4375D, 0.5625D, 1.0D, attaches(world, x, y, z, Facing.NORTH),
                    attaches(world, x, y, z, Facing.EAST), attaches(world, x, y, z, Facing.SOUTH),
                    attaches(world, x, y, z, Facing.WEST));
        }
    }
}
