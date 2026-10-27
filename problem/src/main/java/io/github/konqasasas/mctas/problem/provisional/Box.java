package io.github.konqasasas.mctas.problem.provisional;

final class Box {
    final double minX;
    final double minY;
    final double minZ;
    final double maxX;
    final double maxY;
    final double maxZ;

    Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    Box offset(double x, double y, double z) {
        return new Box(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    Box expand(double x, double y, double z) {
        double a = minX, b = minY, c = minZ, d = maxX, e = maxY, f = maxZ;
        if (x < 0.0D) a += x; else if (x > 0.0D) d += x;
        if (y < 0.0D) b += y; else if (y > 0.0D) e += y;
        if (z < 0.0D) c += z; else if (z > 0.0D) f += z;
        return new Box(a, b, c, d, e, f);
    }

    boolean intersects(Box o) {
        return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ;
    }

    double calculateXOffset(Box other, double offsetX) {
        if (other.maxY > minY && other.minY < maxY && other.maxZ > minZ && other.minZ < maxZ) {
            if (offsetX > 0.0D && other.maxX <= minX) {
                double d1 = minX - other.maxX;
                if (d1 < offsetX) offsetX = d1;
            } else if (offsetX < 0.0D && other.minX >= maxX) {
                double d0 = maxX - other.minX;
                if (d0 > offsetX) offsetX = d0;
            }
        }
        return offsetX;
    }

    double calculateYOffset(Box other, double offsetY) {
        if (other.maxX > minX && other.minX < maxX && other.maxZ > minZ && other.minZ < maxZ) {
            if (offsetY > 0.0D && other.maxY <= minY) {
                double d1 = minY - other.maxY;
                if (d1 < offsetY) offsetY = d1;
            } else if (offsetY < 0.0D && other.minY >= maxY) {
                double d0 = maxY - other.minY;
                if (d0 > offsetY) offsetY = d0;
            }
        }
        return offsetY;
    }

    double calculateZOffset(Box other, double offsetZ) {
        if (other.maxX > minX && other.minX < maxX && other.maxY > minY && other.minY < maxY) {
            if (offsetZ > 0.0D && other.maxZ <= minZ) {
                double d1 = minZ - other.maxZ;
                if (d1 < offsetZ) offsetZ = d1;
            } else if (offsetZ < 0.0D && other.minZ >= maxZ) {
                double d0 = maxZ - other.minZ;
                if (d0 > offsetZ) offsetZ = d0;
            }
        }
        return offsetZ;
    }
}
