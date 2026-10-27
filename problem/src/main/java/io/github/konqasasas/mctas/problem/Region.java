package io.github.konqasasas.mctas.problem;

public final class Region {
    public final double minX;
    public final double minY;
    public final double minZ;
    public final double maxX;
    public final double maxY;
    public final double maxZ;

    public Region(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("region min must not exceed max");
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Vec3 p) {
        return contains(p.x, p.y, p.z);
    }

    public boolean intersects(Region o) {
        return minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY && minZ <= o.maxZ && o.minZ <= maxZ;
    }

    @Override
    public String toString() {
        return "[" + minX + ", " + minY + ", " + minZ + "] .. [" + maxX + ", " + maxY + ", " + maxZ + "]";
    }
}
