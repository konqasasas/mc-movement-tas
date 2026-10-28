package io.github.konqasasas.mctas.problem.io;

/** Which engine produced a solution and how long it searched, for the score table. */
public final class SearchInfo {
    public final String engine;
    public final long wallMillis;

    public SearchInfo(String engine, long wallMillis) {
        this.engine = engine;
        this.wallMillis = wallMillis;
    }
}
