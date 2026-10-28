package io.github.konqasasas.mctas.physics;

import java.util.Arrays;

/**
 * Buttons the player pressed and that the client still shows pressed, immutable and shared between
 * copies like {@link WorldDelta}.
 *
 * <p>{@code BlockButton.onBlockActivated} runs on the client too and turns the button on at once. The
 * server turns it off {@code tickRate} of its ticks later (20 for stone, 30 for wood) and sends the
 * change, which the client takes in at the start of a tick some time after that: the client's ticks are
 * not in step with the server's. Until {@link #certainTicks} the button is surely still on; for the next
 * {@link #UNCERTAIN_TICKS} it may be either, and a view ray that reaches it is unsupported; after that it
 * is off again as in the base world. Only the selection box differs, as a button has no collision box.
 */
public final class Pressed {

    public static final Pressed NONE = new Pressed(new int[0], new BlockType[0], new int[0]);

    /** Ticks after the server's turn-off during which the client may or may not show it yet. */
    public static final int UNCERTAIN_TICKS = 8;

    private final int[] xyz;
    /** The button as it was before it was pressed. */
    private final BlockType[] off;
    /** Ticks since the press: 0 during the tick of the click. */
    private final int[] age;

    private Pressed(int[] xyz, BlockType[] off, int[] age) {
        this.xyz = xyz;
        this.off = off;
        this.age = age;
    }

    public boolean isEmpty() {
        return off.length == 0;
    }

    public int size() {
        return off.length;
    }

    public int x(int i) {
        return xyz[3 * i];
    }

    public int y(int i) {
        return xyz[3 * i + 1];
    }

    public int z(int i) {
        return xyz[3 * i + 2];
    }

    /** Ticks since the i-th button was pressed. */
    public int age(int i) {
        return age[i];
    }

    /** The i-th button as it was before it was pressed. */
    public BlockType off(int i) {
        return off[i];
    }

    /** The server's {@code tickRate} of the button: 30 for wooden buttons, 20 for stone ones. */
    static int tickRate(BlockType button) {
        return VanillaBlocks.blockId(button.name()).equals("minecraft:wooden_button") ? 30 : 20;
    }

    /** Ticks after the press during which the client surely still shows the button on. */
    public static int certainTicks(BlockType button) {
        // The server's ticks and the client's last 50 ms each but drift apart by up to one tick, and the
        // turn-off reaches the client at the start of a later tick.
        return tickRate(button) - 2;
    }

    /** True if the button at (x, y, z) was pressed and is not surely off again. */
    public boolean has(int x, int y, int z) {
        return index(x, y, z) >= 0;
    }

    private int index(int x, int y, int z) {
        for (int i = 0; i < off.length; i++) {
            if (xyz[3 * i] == x && xyz[3 * i + 1] == y && xyz[3 * i + 2] == z) {
                return i;
            }
        }
        return -1;
    }

    /** This list with the button {@code off} at (x, y, z) pressed now. */
    public Pressed with(int x, int y, int z, BlockType button) {
        if (has(x, y, z)) {
            throw new IllegalArgumentException("the button at " + x + " " + y + " " + z + " is already on");
        }
        int n = off.length;
        int[] p = Arrays.copyOf(xyz, 3 * n + 3);
        p[3 * n] = x;
        p[3 * n + 1] = y;
        p[3 * n + 2] = z;
        BlockType[] o = Arrays.copyOf(off, n + 1);
        o[n] = button;
        int[] a = Arrays.copyOf(age, n + 1);
        a[n] = 0;
        return new Pressed(p, o, a);
    }

    /** One tick later, without the buttons that are surely off again. */
    public Pressed aged() {
        int n = 0;
        for (int i = 0; i < off.length; i++) {
            if (age[i] + 1 <= certainTicks(off[i]) + UNCERTAIN_TICKS) {
                n++;
            }
        }
        if (n == 0) {
            return NONE;
        }
        int[] p = new int[3 * n];
        BlockType[] o = new BlockType[n];
        int[] a = new int[n];
        int j = 0;
        for (int i = 0; i < off.length; i++) {
            if (age[i] + 1 <= certainTicks(off[i]) + UNCERTAIN_TICKS) {
                p[3 * j] = xyz[3 * i];
                p[3 * j + 1] = xyz[3 * i + 1];
                p[3 * j + 2] = xyz[3 * i + 2];
                o[j] = off[i];
                a[j] = age[i] + 1;
                j++;
            }
        }
        return new Pressed(p, o, a);
    }

    /** The button as the client shows it now: on, or of unknown state. */
    public BlockType shown(int i) {
        java.util.Map<String, String> props = VanillaBlocks.parseProperties(off[i].name());
        String id = VanillaBlocks.blockId(off[i].name());
        if (age[i] <= certainTicks(off[i])) {
            props.put("powered", "true");
            return VanillaBlocks.require(VanillaBlocks.name(id, props));
        }
        props.remove("powered");
        return VanillaBlocks.partial(id, props);
    }

    /** {@code base} with the pressed buttons as the client shows them. */
    public BlockWorld over(final BlockWorld base) {
        if (isEmpty()) {
            return base;
        }
        final BlockType[] shown = new BlockType[off.length];
        for (int i = 0; i < off.length; i++) {
            shown[i] = shown(i);
        }
        return new BlockWorld() {
            @Override
            public BlockType getBlock(int x, int y, int z) {
                for (int i = 0; i < shown.length; i++) {
                    if (xyz[3 * i] == x && xyz[3 * i + 1] == y && xyz[3 * i + 2] == z) {
                        return shown[i];
                    }
                }
                return base.getBlock(x, y, z);
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pressed)) {
            return false;
        }
        Pressed p = (Pressed) o;
        return Arrays.equals(xyz, p.xyz) && Arrays.equals(off, p.off) && Arrays.equals(age, p.age);
    }

    @Override
    public int hashCode() {
        return isEmpty() ? 0 : 31 * (31 * Arrays.hashCode(xyz) + Arrays.hashCode(off)) + Arrays.hashCode(age);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < off.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(xyz[3 * i]).append(',').append(xyz[3 * i + 1]).append(',').append(xyz[3 * i + 2])
                    .append('+').append(age[i]);
        }
        return sb.toString();
    }
}
