package io.github.konqasasas.mctas.physics;

/** {@code EnumFacing}, with the rotations the block shape rules use. */
public enum Facing {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    public static final Facing[] HORIZONTALS = {NORTH, EAST, SOUTH, WEST};

    public final int dx;
    public final int dy;
    public final int dz;

    Facing(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public boolean isVertical() {
        return dy != 0;
    }

    /** True for EAST and WEST. */
    public boolean isXAxis() {
        return dx != 0;
    }

    public boolean sameAxis(Facing o) {
        return dx != 0 ? o.dx != 0 : dz != 0 ? o.dz != 0 : o.dy != 0;
    }

    public Facing opposite() {
        switch (this) {
            case DOWN: return UP;
            case UP: return DOWN;
            case NORTH: return SOUTH;
            case SOUTH: return NORTH;
            case WEST: return EAST;
            default: return WEST;
        }
    }

    /** {@code rotateY}: clockwise seen from above. */
    public Facing rotateY() {
        switch (this) {
            case NORTH: return EAST;
            case EAST: return SOUTH;
            case SOUTH: return WEST;
            case WEST: return NORTH;
            default: throw new IllegalStateException("cannot rotate " + this);
        }
    }

    /** {@code rotateYCCW}. */
    public Facing rotateYCCW() {
        switch (this) {
            case NORTH: return WEST;
            case WEST: return SOUTH;
            case SOUTH: return EAST;
            case EAST: return NORTH;
            default: throw new IllegalStateException("cannot rotate " + this);
        }
    }

    /** The lowercase name used in block state strings. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
