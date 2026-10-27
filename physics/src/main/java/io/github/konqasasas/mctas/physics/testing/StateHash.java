package io.github.konqasasas.mctas.physics.testing;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.PlayerState;

/** A 64-bit FNV-1a hash over the exact bits of every {@link PlayerState} field, chained across ticks. */
public final class StateHash {

    private long h = 0xcbf29ce484222325L;

    public StateHash add(PlayerState s) {
        mix(Double.doubleToLongBits(s.posX));
        mix(Double.doubleToLongBits(s.posY));
        mix(Double.doubleToLongBits(s.posZ));
        Aabb b = s.box;
        mix(Double.doubleToLongBits(b.minX));
        mix(Double.doubleToLongBits(b.minY));
        mix(Double.doubleToLongBits(b.minZ));
        mix(Double.doubleToLongBits(b.maxX));
        mix(Double.doubleToLongBits(b.maxY));
        mix(Double.doubleToLongBits(b.maxZ));
        mix(Double.doubleToLongBits(s.motionX));
        mix(Double.doubleToLongBits(s.motionY));
        mix(Double.doubleToLongBits(s.motionZ));
        mix(Float.floatToIntBits(s.rotationYaw));
        mix(Float.floatToIntBits(s.rotationPitch));
        mix((s.onGround ? 1 : 0) | (s.collidedHorizontally ? 2 : 0) | (s.collidedVertically ? 4 : 0)
                | (s.sprinting ? 8 : 0) | (s.prevJump ? 16 : 0) | (s.prevSneak ? 32 : 0));
        mix(Float.floatToIntBits(s.fallDistance));
        mix(Float.floatToIntBits(s.width));
        mix(Float.floatToIntBits(s.height));
        mix(s.jumpTicks);
        mix(Float.floatToIntBits(s.jumpMovementFactor));
        mix(s.sprintToggleTimer);
        mix(Float.floatToIntBits(s.prevMoveForward));
        mix(Float.floatToIntBits(s.prevMoveStrafe));
        mix(s.speedAmplifier);
        mix(s.slownessAmplifier);
        mix(s.jumpBoostAmplifier);
        return this;
    }

    private void mix(long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v >>> (i * 8)) & 0xFF;
            h *= 0x100000001b3L;
        }
    }

    public long value() {
        return h;
    }
}
