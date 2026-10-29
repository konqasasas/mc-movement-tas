package io.github.konqasasas.mctas.physics;

/**
 * Read access to the blocks the player can touch. Implementations must return {@link BlockType#AIR}
 * outside the build height (y &lt; 0 or y &gt;= 256), as {@code World.getBlockState} does.
 */
public interface BlockWorld {

    BlockType getBlock(int x, int y, int z);

    /**
     * False only when no block of this world is a {@link VanillaBlocks.Liquid}, so the water and lava
     * checks of a tick can be skipped. True is always safe.
     */
    default boolean mayContainLiquid() {
        return true;
    }

    /**
     * False only when no block of this world is a pressure plate, so the server's side of a tick
     * ({@link Mechanisms}) can be skipped while nothing is pressed. True is always safe.
     */
    default boolean mayContainPlates() {
        return true;
    }
}
