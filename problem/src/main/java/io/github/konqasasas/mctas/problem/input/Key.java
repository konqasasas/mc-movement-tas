package io.github.konqasasas.mctas.problem.input;

public enum Key {
    W, A, S, D, JUMP, SPRINT, SNEAK,
    /** The use-item button (right mouse): places the held block. */
    USE;

    public static Key parse(String token) {
        String t = token.trim().toUpperCase();
        if (t.equals("J") || t.equals("SPACE")) return JUMP;
        if (t.equals("R") || t.equals("CTRL")) return SPRINT;
        if (t.equals("N") || t.equals("SHIFT")) return SNEAK;
        if (t.equals("U") || t.equals("RMB")) return USE;
        return Key.valueOf(t);
    }
}
