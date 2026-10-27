package io.github.konqasasas.mctas.physics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class McMathTest {

    /** The game builds its table with {@code Math.sin}; ours uses {@code StrictMath.sin}. */
    @Test
    public void sineTableMatchesMathSin() {
        for (int i = 0; i < 65536; ++i) {
            float game = (float) Math.sin((double) i * Math.PI * 2.0D / 65536.0D);
            assertEquals("entry " + i, Float.floatToIntBits(game), Float.floatToIntBits(McMath.sinTableEntry(i)));
        }
    }

    @Test
    public void floorAndCeilMatchMathHelper() {
        double[] values = {-2.5, -2.0, -0.0, 0.0, 1e-12, 0.5, 1.0, 1.0000001, -1e-12, 1e9 + 0.5};
        for (double v : values) {
            assertEquals("floor " + v, (int) Math.floor(v), McMath.floor(v));
            assertEquals("ceil " + v, (int) Math.ceil(v), McMath.ceil(v));
        }
    }

    @Test
    public void cosIsQuarterTurnOfSin() {
        assertEquals(1.0F, McMath.cos(0.0F), 0.0F);
        assertEquals(1.0F, McMath.sin(90.0F * 0.017453292F), 0.0F);
    }
}
