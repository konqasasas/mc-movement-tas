package io.github.konqasasas.mctas.physics;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The movement-relevant properties of one block state.
 *
 * <p>The name is the vanilla block state string with only the properties that matter here, such as
 * {@code minecraft:oak_stairs[facing=north,half=bottom]}; {@link VanillaBlocks} holds the catalog.
 * Shapes that depend on the neighbours (stairs, fences, walls, panes, chests) are subclasses that
 * read the world in {@link #addCollisionBoxes}, as the game computes the actual state on demand.
 *
 * <p>Blocks that act on the player override the {@code Block} callbacks of the same names
 * ({@link #onLanded}, {@link #onEntityWalk}, {@link #onEntityCollision}, {@link #isLadder}).
 */
public class BlockType {

    static final Aabb FULL_BLOCK = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
    private static final FaceShape[] ALL_SOLID = faces(FaceShape.SOLID, FaceShape.SOLID, FaceShape.SOLID);
    private static final FaceShape[] ALL_UNDEFINED = faces(FaceShape.UNDEFINED, FaceShape.UNDEFINED, FaceShape.UNDEFINED);

    /** {@code Block.isExceptBlockForAttachWithPiston} as fences and walls extend it. */
    public static final int EXCEPT_FENCE_WALL = 1;
    /** {@code BlockPane.isExcepBlockForAttachWithPiston}. */
    public static final int EXCEPT_PANE = 2;
    /** {@code Block.isExceptBlockForAttachWithPiston} itself, which liquids use for their downward current. */
    public static final int EXCEPT_PISTON = 4;
    /** {@code Material.ICE} (ice and frosted ice, not packed ice): liquids next to it have no downward current. */
    public static final int ICE_MATERIAL = 8;
    /**
     * Changes by itself in a live world: falls, decays, grows, melts or is trampled. It moves the player
     * like any other block; the random test courses leave it out so the real game keeps the course as built.
     */
    public static final int UNSTABLE = 16;

    public static final BlockType AIR = new BlockType("minecraft:air", Collections.<Aabb>emptyList(), 0.6F, false, true, ALL_UNDEFINED, 0);
    public static final BlockType STONE = new BlockType("minecraft:stone", Collections.singletonList(FULL_BLOCK), 0.6F, true, false);
    public static final BlockType ICE = new BlockType("minecraft:ice", Collections.singletonList(FULL_BLOCK), 0.98F, false, false,
            ALL_SOLID, EXCEPT_FENCE_WALL | EXCEPT_PANE | EXCEPT_PISTON | ICE_MATERIAL);
    public static final BlockType PACKED_ICE = new BlockType("minecraft:packed_ice", Collections.singletonList(FULL_BLOCK), 0.98F, true, false);

    private final String name;
    private final Aabb[] boxes;
    private final float slipperiness;
    private final boolean normalCube;
    private final boolean air;
    private final FaceShape[] faces;
    private final int except;
    private boolean blocksMovement;

    /** A block with solid faces that every fence, wall and pane connects to. */
    public BlockType(String name, List<Aabb> boxes, float slipperiness, boolean normalCube, boolean air) {
        this(name, boxes, slipperiness, normalCube, air, ALL_SOLID, 0);
    }

    /**
     * @param boxes        collision boxes in block-local coordinates, in the order the game adds them
     * @param slipperiness {@code Block.slipperiness}
     * @param normalCube   {@code IBlockState.isNormalCube()}, used by the client's push-out-of-blocks check
     * @param air          {@code Material.AIR}, used for the block-below lookup after a move
     * @param faces        {@code getBlockFaceShape} by {@link Facing#ordinal()}, for blocks whose faces do not
     *                     depend on the neighbours
     * @param except       {@link #EXCEPT_FENCE_WALL}, {@link #EXCEPT_PANE}, {@link #EXCEPT_PISTON}, {@link #ICE_MATERIAL}
     *                     and {@link #UNSTABLE} flags
     */
    public BlockType(String name, List<Aabb> boxes, float slipperiness, boolean normalCube, boolean air,
                     FaceShape[] faces, int except) {
        this.name = name;
        this.boxes = boxes.toArray(new Aabb[0]);
        this.slipperiness = slipperiness;
        this.normalCube = normalCube;
        this.air = air;
        this.faces = faces.clone();
        this.except = except;
        this.blocksMovement = !air;
    }

    /** Marks a block whose material does not block movement ({@code MaterialLogic}, webs). Returns this block. */
    public BlockType notBlockingMovement() {
        blocksMovement = false;
        return this;
    }

    /** Faces for down, up and the four sides, in {@link Facing} order. */
    public static FaceShape[] faces(FaceShape down, FaceShape up, FaceShape sides) {
        return new FaceShape[] {down, up, sides, sides, sides, sides};
    }

    public String name() {
        return name;
    }

    public float slipperiness() {
        return slipperiness;
    }

    public boolean isNormalCube() {
        return normalCube;
    }

    public boolean isAir() {
        return air;
    }

    /** {@code Material.blocksMovement()}, which decides where liquids flow towards. */
    public boolean blocksMovement() {
        return blocksMovement;
    }

    public boolean isIceMaterial() {
        return (except & ICE_MATERIAL) != 0;
    }

    public boolean isExcept(int flag) {
        return (except & flag) != 0;
    }

    /** The boxes of a block whose shape does not depend on its neighbours. */
    public List<Aabb> localBoxes() {
        return Collections.unmodifiableList(Arrays.asList(boxes));
    }

    /** {@code Block.addCollisionBoxToList}: adds each box at (x, y, z) that intersects {@code area}. */
    public void addCollisionBoxes(BlockWorld world, int x, int y, int z, Aabb area, List<Aabb> out) {
        for (Aabb local : boxes) {
            add(x, y, z, area, out, local);
        }
    }

    /** {@code IBlockState.getBlockFaceShape}. */
    public FaceShape faceShape(BlockWorld world, int x, int y, int z, Facing face) {
        return faces[face.ordinal()];
    }

    /** {@code Block.onLanded}: the move ended against something vertically while above this block. */
    void onLanded(PlayerState s) {
        s.motionY = 0.0D;
    }

    /** {@code Block.onEntityWalk}: the player walked on this block without sneaking. */
    void onEntityWalk(PlayerState s) {
    }

    /** {@code Block.onEntityCollision}: the player's box overlaps this block after a move. */
    void onEntityCollision(PlayerState s) {
    }

    /** {@code Block.isLadder} as Forge asks it; vanilla's {@code isOnLadder} gives the same answers. */
    boolean isLadder(BlockWorld world, int x, int y, int z) {
        return false;
    }

    /** The protected static {@code Block.addCollisionBoxToList}. */
    static void add(int x, int y, int z, Aabb area, List<Aabb> out, Aabb local) {
        Aabb box = local.offset(x, y, z);
        if (area.intersects(box)) {
            out.add(box);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
