package io.github.konqasasas.mctas.search.bound;

/**
 * The vertical part of the player state, tracked exactly. In a world of full blocks the vertical
 * motion does not depend on the horizontal inputs, only on whether the box is over a block when it
 * moves down (landing) or up (ceiling), so it can be enumerated bit for bit like the game does.
 */
final class VState {

    final double minY;
    final double maxY;
    final double motionY;
    final boolean onGround;

    VState(double minY, double maxY, double motionY, boolean onGround) {
        this.minY = minY;
        this.maxY = maxY;
        this.motionY = motionY;
        this.onGround = onGround;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VState)) return false;
        VState v = (VState) o;
        return Double.doubleToLongBits(minY) == Double.doubleToLongBits(v.minY)
            && Double.doubleToLongBits(maxY) == Double.doubleToLongBits(v.maxY)
            && Double.doubleToLongBits(motionY) == Double.doubleToLongBits(v.motionY)
            && onGround == v.onGround;
    }

    @Override
    public int hashCode() {
        long h = Double.doubleToLongBits(minY);
        h = h * 31 + Double.doubleToLongBits(maxY);
        h = h * 31 + Double.doubleToLongBits(motionY);
        return (int) (h ^ (h >>> 32)) * 2 + (onGround ? 1 : 0);
    }

    @Override
    public String toString() {
        return "y=" + minY + " vy=" + motionY + (onGround ? " ground" : " air");
    }
}
