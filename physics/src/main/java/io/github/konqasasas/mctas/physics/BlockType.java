package io.github.konqasasas.mctas.physics;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The movement-relevant properties of one block state.
 *
 * <p>Only blocks whose behaviour is fully described by these fields are defined here. Blocks with
 * their own collision callbacks (slime, soul sand, ladders, webs, liquids, beds) need extra hooks in
 * {@link Physics} and are added together with those hooks.
 */
public final class BlockType {

    private static final Aabb FULL_BLOCK = new Aabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

    public static final BlockType AIR = new BlockType("air", Collections.<Aabb>emptyList(), 0.6F, false, true);
    public static final BlockType STONE = new BlockType("stone", Collections.singletonList(FULL_BLOCK), 0.6F, true, false);
    public static final BlockType ICE = new BlockType("ice", Collections.singletonList(FULL_BLOCK), 0.98F, false, false);
    public static final BlockType PACKED_ICE = new BlockType("packed_ice", Collections.singletonList(FULL_BLOCK), 0.98F, true, false);

    private final String name;
    private final Aabb[] boxes;
    private final float slipperiness;
    private final boolean normalCube;
    private final boolean air;

    /**
     * @param boxes        collision boxes in block-local coordinates, in the order the game adds them
     * @param slipperiness {@code Block.slipperiness}
     * @param normalCube   {@code IBlockState.isNormalCube()}, used by the client's push-out-of-blocks check
     * @param air          {@code Material.AIR}, used for the block-below lookup after a move
     */
    public BlockType(String name, List<Aabb> boxes, float slipperiness, boolean normalCube, boolean air) {
        this.name = name;
        this.boxes = boxes.toArray(new Aabb[0]);
        this.slipperiness = slipperiness;
        this.normalCube = normalCube;
        this.air = air;
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

    public List<Aabb> localBoxes() {
        return Collections.unmodifiableList(Arrays.asList(boxes));
    }

    /** {@code Block.addCollisionBoxToList}: adds each box at (x, y, z) that intersects {@code area}. */
    void addCollisionBoxes(int x, int y, int z, Aabb area, List<Aabb> out) {
        for (Aabb local : boxes) {
            Aabb box = local.offset(x, y, z);
            if (area.intersects(box)) {
                out.add(box);
            }
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
