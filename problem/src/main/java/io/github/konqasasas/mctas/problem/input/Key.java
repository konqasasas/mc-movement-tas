package io.github.konqasasas.mctas.problem.input;

public enum Key {
    W, A, S, D, JUMP, SPRINT, SNEAK,
    /** The use-item button (right mouse): places the held block, presses a button, opens a shulker box. */
    USE,
    /** Closes the open screen (a shulker box's) at the start of the tick. */
    CLOSE;

    public static Key parse(String token) {
        String t = token.trim().toUpperCase();
        if (t.equals("J") || t.equals("SPACE")) return JUMP;
        if (t.equals("R") || t.equals("CTRL")) return SPRINT;
        if (t.equals("N") || t.equals("SHIFT")) return SNEAK;
        if (t.equals("U") || t.equals("RMB")) return USE;
        if (t.equals("C") || t.equals("ESC")) return CLOSE;
        return Key.valueOf(t);
    }
}
