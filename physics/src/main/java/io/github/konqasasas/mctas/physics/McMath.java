package io.github.konqasasas.mctas.physics;

/**
 * Bit-exact copies of the 1.12.2 {@code MathHelper} routines the movement code uses.
 *
 * <p>The sine table is built with {@link StrictMath} so it does not depend on the JVM's
 * {@code Math.sin} intrinsic; {@code McMathTest} checks it against {@code Math.sin} for all
 * 65536 entries.
 */
public final class McMath {

    private static final float[] SIN_TABLE = new float[65536];

    static {
        for (int i = 0; i < 65536; ++i) {
            SIN_TABLE[i] = (float) StrictMath.sin((double) i * Math.PI * 2.0D / 65536.0D);
        }
    }

    private McMath() {
    }

    public static float sin(float value) {
        return SIN_TABLE[(int) (value * 10430.378F) & 65535];
    }

    public static float cos(float value) {
        return SIN_TABLE[(int) (value * 10430.378F + 16384.0F) & 65535];
    }

    public static float sqrt(float value) {
        return (float) Math.sqrt((double) value);
    }

    public static float sqrt(double value) {
        return (float) Math.sqrt(value);
    }

    public static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }

    public static int ceil(double value) {
        int i = (int) value;
        return value > (double) i ? i + 1 : i;
    }

    public static double clamp(double num, double min, double max) {
        if (num < min) {
            return min;
        }
        return num > max ? max : num;
    }

    static float sinTableEntry(int i) {
        return SIN_TABLE[i];
    }
}
