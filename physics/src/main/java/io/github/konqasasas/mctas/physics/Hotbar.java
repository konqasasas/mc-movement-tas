package io.github.konqasasas.mctas.physics;

import java.util.Arrays;

/**
 * The nine hotbar slots, each empty or a stack of a placeable block. Immutable, so states can share it;
 * {@link #withOneUsed} returns a new hotbar. The off hand is always empty.
 */
public final class Hotbar {

    public static final int SLOTS = 9;
    public static final Hotbar EMPTY = new Hotbar(new BlockType[SLOTS], new int[SLOTS]);

    private final BlockType[] items;
    private final int[] counts;

    private Hotbar(BlockType[] items, int[] counts) {
        this.items = items;
        this.counts = counts;
    }

    /** A hotbar with this stack in the slot. {@code count} 0 or a null block empties it. */
    public Hotbar with(int slot, BlockType block, int count) {
        if (block != null && count > 0 && !block.isPlaceable()) {
            throw new IllegalArgumentException(block.name() + " is not a full block the engine can place");
        }
        if (count < 0 || count > 64) {
            throw new IllegalArgumentException("stack size must be 0 to 64: " + count);
        }
        BlockType[] i = items.clone();
        int[] c = counts.clone();
        boolean empty = block == null || count == 0;
        i[slot] = empty ? null : block;
        c[slot] = empty ? 0 : count;
        return new Hotbar(i, c);
    }

    /** The block in the slot, or null when it is empty. */
    public BlockType item(int slot) {
        return items[slot];
    }

    public int count(int slot) {
        return counts[slot];
    }

    /** {@code ItemStack.shrink(1)} on the slot. */
    public Hotbar withOneUsed(int slot) {
        return with(slot, items[slot], counts[slot] - 1);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Hotbar && Arrays.equals(items, ((Hotbar) o).items) && Arrays.equals(counts, ((Hotbar) o).counts);
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (int i = 0; i < SLOTS; i++) {
            h = h * 31 + (items[i] == null ? 0 : items[i].name().hashCode());
            h = h * 31 + counts[i];
        }
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(items[i] == null ? "-" : items[i].name() + "x" + counts[i]);
        }
        return sb.append(']').toString();
    }
}
