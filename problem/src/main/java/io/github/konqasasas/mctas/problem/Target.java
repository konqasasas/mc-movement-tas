package io.github.konqasasas.mctas.problem;

public final class Target {
    /**
     * {@code UPPER_BOUND}: a known solution, {@code OPTIMAL}: proven optimal, {@code HUMAN_BENCHMARK}:
     * a top human time on the course, with no witness and no claim about the optimum.
     */
    public enum Kind { UPPER_BOUND, OPTIMAL, HUMAN_BENCHMARK }

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
