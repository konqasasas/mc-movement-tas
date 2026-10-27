package io.github.konqasasas.mctas.physics;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    }

    private VanillaBlocks() {
    }

    /** The block state with this name, or null if the engine cannot model it. */
    public static BlockType get(String name) {
        return BY_NAME.get(name);
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
            if (t == BlockType.ICE) {
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

    private static String stateName(String id, Facing facing, String key, String value) {
        return id + "[facing=" + facing.id() + "," + key + "=" + value + "]";
    }

    // ---- blocks whose shape depends on the neighbours ----

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
    }

    /** {@code BlockFenceGate}: a fixed shape, but fences and walls connect to its sides. */
    static final class FenceGate extends BlockType {
        FenceGate(String id, Facing facing, boolean open) {
            super(stateName(id, facing, "open", String.valueOf(open)),
                    open ? Collections.<Aabb>emptyList() : Collections.singletonList(facing.isXAxis()
                            ? new Aabb(0.375D, 0.0D, 0.0D, 0.625D, 1.5D, 1.0D)
                            : new Aabb(0.0D, 0.0D, 0.375D, 1.0D, 1.5D, 0.625D)),
                    0.6F, false, false, gateFaces(facing), 0);
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
    }

    /** {@code BlockWall}: one box, 1.5 high, picked by the connections. */
    static final class Wall extends BlockType {
        private static final Aabb[] CLIP = new Aabb[16];

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
            add(x, y, z, area, out, CLIP[i]);
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
    }
}
