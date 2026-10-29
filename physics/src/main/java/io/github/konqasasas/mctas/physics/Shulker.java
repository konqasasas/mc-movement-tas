package io.github.konqasasas.mctas.physics;

/**
 * The shulker box the player opened, from the click until its lid is shut again, immutable and shared
 * between copies like {@link Pressed}. At most one box moves at a time; clicking another meanwhile is
 * unsupported.
 *
 * <p>On the integrated server (D-700): the client's {@code BlockShulkerBox.onBlockActivated} only answers
 * the click. The server opens the box in its tick between this client tick and the next, if nothing is in
 * the way of the lid, and its block event ({@code TileEntityShulkerBox.receiveClientEvent(1, 1)}) and the
 * screen reach the client at the start of the next tick (F-704). Closing the screen likewise sets the lid
 * closing at the start of the tick after. The client's tile entity moves the lid by 0.1 after the player
 * in each tick ({@code TileEntityShulkerBox.update}) and pushes the player out of its way; its progress
 * gives the box its collision and selection box.
 */
public final class Shulker {

    /** {@code TileEntityShulkerBox.AnimationStatus}, in its order. */
    public enum Status {
        CLOSED, OPENING, OPENED, CLOSING
    }

    /** What reaches the client at the start of the next tick. */
    public enum Event {
        NONE, OPEN, CLOSE
    }

    public static final Shulker NONE = new Shulker(0, 0, 0, null, null, Status.CLOSED, 0.0F, 0.0F, false, Event.NONE);

    public final int x;
    public final int y;
    public final int z;
    /** The box as the world holds it, shut. */
    public final BlockType block;
    public final Facing facing;
    public final Status status;
    /** {@code TileEntityShulkerBox.progress} and {@code progressOld}. */
    public final float progress;
    public final float progressOld;
    /** The box's screen ({@code GuiShulkerBox}) is open. */
    public final boolean screen;
    public final Event event;

    private Shulker(int x, int y, int z, BlockType block, Facing facing, Status status, float progress,
                    float progressOld, boolean screen, Event event) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block;
        this.facing = facing;
        this.status = status;
        this.progress = progress;
        this.progressOld = progressOld;
        this.screen = screen;
        this.event = event;
    }

    /** Every field given, for the oracle that reads them off the game's tile entity. */
    public static Shulker of(int x, int y, int z, BlockType block, Facing facing, Status status, float progress,
                             float progressOld, boolean screen, Event event) {
        return new Shulker(x, y, z, block, facing, status, progress, progressOld, screen, event);
    }

    /** A shut box the server is about to open. */
    static Shulker clicked(int x, int y, int z, BlockType block, Facing facing) {
        return new Shulker(x, y, z, block, facing, Status.CLOSED, 0.0F, 0.0F, false, Event.OPEN);
    }

    public boolean isNone() {
        return block == null;
    }

    /** The screen is closed now; the server sets the lid closing for the next tick. */
    Shulker closeScreen() {
        return new Shulker(x, y, z, block, facing, status, progress, progressOld, false, Event.CLOSE);
    }

    /** At the start of a tick: the block event and the screen the server sent, if any. */
    Shulker arrive() {
        switch (event) {
            case OPEN:
                return new Shulker(x, y, z, block, facing, Status.OPENING, progress, progressOld, true, Event.NONE);
            case CLOSE:
                return new Shulker(x, y, z, block, facing, Status.CLOSING, progress, progressOld, screen, Event.NONE);
            default:
                return this;
        }
    }

    /** {@code TileEntityShulkerBox.updateAnimation}, without the push when the lid reaches the top. */
    Shulker animated() {
        float old = progress;
        float p = progress;
        Status st = status;
        switch (status) {
            case CLOSED:
                p = 0.0F;
                break;
            case OPENING:
                p += 0.1F;
                break;
            case CLOSING:
                p -= 0.1F;
                if (p <= 0.0F) {
                    st = Status.CLOSED;
                    p = 0.0F;
                }
                break;
            case OPENED:
                p = 1.0F;
                break;
            default:
                throw new AssertionError(status);
        }
        return new Shulker(x, y, z, block, facing, st, p, old, screen, event);
    }

    /** The lid has reached the top: {@code updateAnimation} pushes once more, then this. */
    boolean reachedTop() {
        return status == Status.OPENING && progress >= 1.0F;
    }

    Shulker opened() {
        return new Shulker(x, y, z, block, facing, Status.OPENED, 1.0F, progressOld, screen, event);
    }

    /** True once the box is shut and still and nothing more is on its way: it is the world's block again. */
    boolean settled() {
        return status == Status.CLOSED && progress == 0.0F && progressOld == 0.0F && !screen && event == Event.NONE;
    }

    /** {@code TileEntityShulkerBox.getProgress(1.0F)}. */
    public float renderProgress() {
        return progressOld + (progress - progressOld) * 1.0F;
    }

    /** {@code TileEntityShulkerBox.getBoundingBox(facing)}, block-local: the collision and selection box. */
    public Aabb box() {
        float p = renderProgress();
        return BlockType.FULL_BLOCK.expand((double) (0.5F * p * (float) facing.dx),
                (double) (0.5F * p * (float) facing.dy), (double) (0.5F * p * (float) facing.dz));
    }

    /** {@code TileEntityShulkerBox.getTopBoundingBox}, block-local: the part of the box the lid sweeps. */
    Aabb topBox() {
        Facing o = facing.opposite();
        return box().contract((double) o.dx, (double) o.dy, (double) o.dz);
    }

    /** {@code BlockShulkerBox.getBlockFaceShape} for each face, in {@link Facing} order. */
    private FaceShape[] shapes() {
        FaceShape[] f = new FaceShape[6];
        for (Facing face : Facing.values()) {
            boolean solid = status == Status.CLOSED
                    || status == Status.OPENED && (facing == face.opposite() || facing == face);
            f[face.ordinal()] = solid ? FaceShape.SOLID : FaceShape.UNDEFINED;
        }
        return f;
    }

    /**
     * {@code BlockShulkerBox.onBlockActivated} on the server for a shut box: whether anything in the world
     * is in the way of the lid, which then stays shut.
     */
    static boolean blocked(BlockWorld world, int x, int y, int z, Facing facing) {
        Aabb lid = BlockType.FULL_BLOCK.expand((double) (0.5F * (float) facing.dx), (double) (0.5F * (float) facing.dy),
                (double) (0.5F * (float) facing.dz)).contract((double) facing.dx, (double) facing.dy, (double) facing.dz)
                .offset(x + facing.dx, y + facing.dy, z + facing.dz);
        return Physics.collidesWithAnyBlock(world, lid);
    }

    /** {@code base} with this box in its current shape. */
    public BlockWorld over(final BlockWorld base) {
        if (isNone()) {
            return base;
        }
        final BlockType shown = block.withShape(box(), shapes());
        return new BlockWorld() {
            @Override
            public BlockType getBlock(int bx, int by, int bz) {
                if (bx == x && by == y && bz == z) {
                    return shown;
                }
                return base.getBlock(bx, by, bz);
            }

            @Override
            public boolean mayContainLiquid() {
                return base.mayContainLiquid();
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Shulker)) {
            return false;
        }
        Shulker s = (Shulker) o;
        return x == s.x && y == s.y && z == s.z && (block == null ? s.block == null : s.block != null && block.name().equals(s.block.name())) && facing == s.facing && status == s.status
                && Float.floatToIntBits(progress) == Float.floatToIntBits(s.progress)
                && Float.floatToIntBits(progressOld) == Float.floatToIntBits(s.progressOld)
                && screen == s.screen && event == s.event;
    }

    @Override
    public int hashCode() {
        if (isNone()) {
            return 0;
        }
        int h = ((x * 31 + y) * 31 + z) * 31 + status.ordinal();
        h = h * 31 + Float.floatToIntBits(progress);
        h = h * 31 + Float.floatToIntBits(progressOld);
        return (h * 31 + event.ordinal()) * 2 + (screen ? 1 : 0);
    }

    @Override
    public String toString() {
        if (isNone()) {
            return "-";
        }
        return x + "," + y + "," + z + " " + facing.id() + " " + status + " " + progress + "/" + progressOld
                + (screen ? " screen" : "") + (event == Event.NONE ? "" : " " + event);
    }
}
