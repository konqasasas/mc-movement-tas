package io.github.konqasasas.mctas.physics;

/** Immutable axis-aligned box with the exact arithmetic of 1.12.2 {@code AxisAlignedBB}. */
public final class Aabb {

    public final double minX;
    public final double minY;
    public final double minZ;
    public final double maxX;
    public final double maxY;
    public final double maxZ;

    public Aabb(double x1, double y1, double z1, double x2, double y2, double z2) {
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    /** {@code AxisAlignedBB.grow}: moves every face outwards (inwards for negative values). */
    public Aabb grow(double x, double y, double z) {
        return new Aabb(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
    }

    /** {@code AxisAlignedBB.expand}: stretches the box in the direction of each component. */
    public Aabb expand(double x, double y, double z) {
        double d0 = minX;
        double d1 = minY;
        double d2 = minZ;
        double d3 = maxX;
        double d4 = maxY;
        double d5 = maxZ;
        if (x < 0.0D) {
            d0 += x;
        } else if (x > 0.0D) {
            d3 += x;
        }
        if (y < 0.0D) {
            d1 += y;
        } else if (y > 0.0D) {
            d4 += y;
        }
        if (z < 0.0D) {
            d2 += z;
        } else if (z > 0.0D) {
            d5 += z;
        }
        return new Aabb(d0, d1, d2, d3, d4, d5);
    }

    public Aabb offset(double x, double y, double z) {
        return new Aabb(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    /** {@code AxisAlignedBB.offset(BlockPos)}. */
    public Aabb offset(int x, int y, int z) {
        return new Aabb(minX + (double) x, minY + (double) y, minZ + (double) z,
                maxX + (double) x, maxY + (double) y, maxZ + (double) z);
    }

    public boolean intersects(Aabb o) {
        return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ;
    }

    /** {@code this} is the obstacle, {@code other} the moving box. */
    public double calculateXOffset(Aabb other, double offsetX) {
        return calculateXOffset(other.minX, other.minY, other.minZ, other.maxX, other.maxY, other.maxZ, offsetX);
    }

    /** {@link #calculateXOffset(Aabb, double)} against the box with these bounds. */
    double calculateXOffset(double oMinX, double oMinY, double oMinZ, double oMaxX, double oMaxY, double oMaxZ,
                             double offsetX) {
        if (oMaxY > minY && oMinY < maxY && oMaxZ > minZ && oMinZ < maxZ) {
            if (offsetX > 0.0D && oMaxX <= minX) {
                double d1 = minX - oMaxX;
                if (d1 < offsetX) {
                    offsetX = d1;
                }
            } else if (offsetX < 0.0D && oMinX >= maxX) {
                double d0 = maxX - oMinX;
                if (d0 > offsetX) {
                    offsetX = d0;
                }
            }
        }
        return offsetX;
    }

    public double calculateYOffset(Aabb other, double offsetY) {
        return calculateYOffset(other.minX, other.minY, other.minZ, other.maxX, other.maxY, other.maxZ, offsetY);
    }

    /** {@link #calculateYOffset(Aabb, double)} against the box with these bounds. */
    double calculateYOffset(double oMinX, double oMinY, double oMinZ, double oMaxX, double oMaxY, double oMaxZ,
                             double offsetY) {
        if (oMaxX > minX && oMinX < maxX && oMaxZ > minZ && oMinZ < maxZ) {
            if (offsetY > 0.0D && oMaxY <= minY) {
                double d1 = minY - oMaxY;
                if (d1 < offsetY) {
                    offsetY = d1;
                }
            } else if (offsetY < 0.0D && oMinY >= maxY) {
                double d0 = maxY - oMinY;
                if (d0 > offsetY) {
                    offsetY = d0;
                }
            }
        }
        return offsetY;
    }

    public double calculateZOffset(Aabb other, double offsetZ) {
        return calculateZOffset(other.minX, other.minY, other.minZ, other.maxX, other.maxY, other.maxZ, offsetZ);
    }

    /** {@link #calculateZOffset(Aabb, double)} against the box with these bounds. */
    double calculateZOffset(double oMinX, double oMinY, double oMinZ, double oMaxX, double oMaxY, double oMaxZ,
                             double offsetZ) {
        if (oMaxX > minX && oMinX < maxX && oMaxY > minY && oMinY < maxY) {
            if (offsetZ > 0.0D && oMaxZ <= minZ) {
                double d1 = minZ - oMaxZ;
                if (d1 < offsetZ) {
                    offsetZ = d1;
                }
            } else if (offsetZ < 0.0D && oMinZ >= maxZ) {
                double d0 = maxZ - oMinZ;
                if (d0 > offsetZ) {
                    offsetZ = d0;
                }
            }
        }
        return offsetZ;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Aabb)) {
            return false;
        }
        Aabb b = (Aabb) o;
        return Double.doubleToLongBits(minX) == Double.doubleToLongBits(b.minX)
                && Double.doubleToLongBits(minY) == Double.doubleToLongBits(b.minY)
                && Double.doubleToLongBits(minZ) == Double.doubleToLongBits(b.minZ)
                && Double.doubleToLongBits(maxX) == Double.doubleToLongBits(b.maxX)
                && Double.doubleToLongBits(maxY) == Double.doubleToLongBits(b.maxY)
                && Double.doubleToLongBits(maxZ) == Double.doubleToLongBits(b.maxZ);
    }

    @Override
    public int hashCode() {
        long h = Double.doubleToLongBits(minX);
        h = h * 31 + Double.doubleToLongBits(minY);
        h = h * 31 + Double.doubleToLongBits(minZ);
        h = h * 31 + Double.doubleToLongBits(maxX);
        h = h * 31 + Double.doubleToLongBits(maxY);
        h = h * 31 + Double.doubleToLongBits(maxZ);
        return (int) (h ^ (h >>> 32));
    }

    @Override
    public String toString() {
        return "box[" + minX + ", " + minY + ", " + minZ + " -> " + maxX + ", " + maxY + ", " + maxZ + "]";
    }
}
