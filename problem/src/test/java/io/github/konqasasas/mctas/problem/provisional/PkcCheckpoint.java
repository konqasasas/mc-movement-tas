package io.github.konqasasas.mctas.problem.provisional;

public final class PkcCheckpoint {
    private PkcCheckpoint() {
    }

    public static void restoreLikePkc(PlayerState s) {
        s.setPosition(s.posX, s.posY, s.posZ);
    }
}
