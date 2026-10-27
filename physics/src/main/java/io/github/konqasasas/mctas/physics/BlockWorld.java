package io.github.konqasasas.mctas.physics;

/**
 * Read access to the blocks the player can touch. Implementations must return {@link BlockType#AIR}
 * outside the build height (y &lt; 0 or y &gt;= 256), as {@code World.getBlockState} does.
 */
public interface BlockWorld {

    BlockType getBlock(int x, int y, int z);
}
