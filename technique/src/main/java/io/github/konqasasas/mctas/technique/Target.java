package io.github.konqasasas.mctas.technique;

/** A surface to land on: the feet point inside the rectangle, standing at height {@link #topY}. */
public final class Target {
    public final double minX;
    public final double maxX;
    public final double minZ;
    public final double maxZ;
    public final double topY;

    public Target(double minX, double maxX, double minZ, double maxZ, double topY) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.topY = topY;
    }

    public double centerX() {
        return (minX + maxX) / 2;
    }

    public double centerZ() {
        return (minZ + maxZ) / 2;
    }

    @Override
    public String toString() {
        return "x " + minX + ".." + maxX + " z " + minZ + ".." + maxZ + " top " + topY;
    }
}
