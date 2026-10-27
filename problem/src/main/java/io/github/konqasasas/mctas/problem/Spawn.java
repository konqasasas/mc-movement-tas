package io.github.konqasasas.mctas.problem;

public final class Spawn {
    public final Vec3 pos;
    public final float yaw;
    public final float pitch;

    public Spawn(Vec3 pos, float yaw, float pitch) {
        this.pos = pos;
        this.yaw = yaw;
        this.pitch = pitch;
    }
}
