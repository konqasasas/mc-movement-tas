package io.github.konqasasas.mctas.problem;

public final class Target {
    public enum Kind { UPPER_BOUND, OPTIMAL }

    public final int ticks;
    public final Kind kind;
    public final String witness;
    public final String note;

    public Target(int ticks, Kind kind, String witness, String note) {
        this.ticks = ticks;
        this.kind = kind;
        this.witness = witness;
        this.note = note;
    }
}
