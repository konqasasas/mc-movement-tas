package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A plain-text input script: one tick per line, {@code KEYS YAW PITCH}, where KEYS are letters from
 * W A S D, J (jump), R (sprint), N (sneak), or {@code -} for none, and YAW and PITCH are absolute
 * float angles. Lines starting with {@code #} are comments.
 *
 * <pre>
 * # sprint jump straight ahead
 * WRJ 0 0
 * WR  0 0
 * </pre>
 */
public final class TasScript {

    private TasScript() {
    }

    public static List<PlayerInput> read(File file) throws IOException {
        List<PlayerInput> out = new ArrayList<PlayerInput>();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\s+");
                if (p.length < 2) {
                    throw new IOException(file.getName() + " line " + n + ": expected KEYS YAW [PITCH]");
                }
                float pitch = p.length > 2 ? Float.parseFloat(p[2]) : 0.0F;
                out.add(new PlayerInput(parseKeys(p[0], n), Float.parseFloat(p[1]), pitch));
            }
        } finally {
            r.close();
        }
        return out;
    }

    static int parseKeys(String s, int line) throws IOException {
        if (s.equals("-")) {
            return 0;
        }
        int keys = 0;
        for (char c : s.toUpperCase().toCharArray()) {
            switch (c) {
                case 'W': keys |= PlayerInput.W; break;
                case 'A': keys |= PlayerInput.A; break;
                case 'S': keys |= PlayerInput.S; break;
                case 'D': keys |= PlayerInput.D; break;
                case 'J': keys |= PlayerInput.JUMP; break;
                case 'R': keys |= PlayerInput.SPRINT; break;
                case 'N': keys |= PlayerInput.SNEAK; break;
                default: throw new IOException("line " + line + ": unknown key '" + c + "'");
            }
        }
        return keys;
    }
}
