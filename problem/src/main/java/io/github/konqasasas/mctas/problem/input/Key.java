package io.github.konqasasas.mctas.problem.input;

public enum Key {
    W, A, S, D, JUMP, SPRINT, SNEAK;

    public static Key parse(String token) {
        String t = token.trim().toUpperCase();
        if (t.equals("J") || t.equals("SPACE")) return JUMP;
        if (t.equals("R") || t.equals("CTRL")) return SPRINT;
        if (t.equals("N") || t.equals("SHIFT")) return SNEAK;
        return Key.valueOf(t);
    }
}
