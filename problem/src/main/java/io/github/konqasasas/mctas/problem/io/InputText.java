package io.github.konqasasas.mctas.problem.io;

import io.github.konqasasas.mctas.problem.Spawn;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class InputText {
    private InputText() {
    }

    public static List<TickInput> parse(String text, Spawn spawn) {
        List<TickInput> out = new ArrayList<TickInput>();
        float yaw = spawn.yaw;
        float pitch = spawn.pitch;
        String[] lines = text.split("\r?\n");
        for (int ln = 0; ln < lines.length; ln++) {
            String line = lines[ln];
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] tok = line.split("\\s+");
            try {
                if (tok[0].equalsIgnoreCase("yaw")) {
                    yaw = Float.parseFloat(tok[1]);
                    continue;
                }
                if (tok[0].equalsIgnoreCase("pitch")) {
                    pitch = Float.parseFloat(tok[1]);
                    continue;
                }
                int count = Integer.parseInt(tok[0]);
                Set<Key> keys = EnumSet.noneOf(Key.class);
                int slot = -1;
                for (int i = 1; i < tok.length; i++) {
                    String t = tok[i];
                    if (t.equals("-")) continue;
                    if (t.toLowerCase().startsWith("slot=")) {
                        // The hotbar slot 0 to 8 (number key 1 to 9), as in the problem's inventory.
                        slot = Integer.parseInt(t.substring(5));
                        if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot must be 0 to 8");
                        continue;
                    }
                    if (t.toLowerCase().startsWith("yaw=")) yaw = Float.parseFloat(t.substring(4));
                    else if (t.toLowerCase().startsWith("pitch=")) pitch = Float.parseFloat(t.substring(6));
                    else keys.add(Key.parse(t));
                }
                for (int i = 0; i < count; i++) out.add(new TickInput(keys, yaw, pitch, slot));
            } catch (RuntimeException e) {
                throw new ProblemFormatException("line " + (ln + 1) + ": cannot parse \"" + lines[ln] + "\" (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    public static String format(List<TickInput> inputs, Spawn spawn) {
        StringBuilder sb = new StringBuilder();
        float yaw = spawn.yaw;
        float pitch = spawn.pitch;
        int i = 0;
        while (i < inputs.size()) {
            TickInput a = inputs.get(i);
            int j = i + 1;
            while (j < inputs.size() && same(a, inputs.get(j))) j++;
            sb.append(j - i);
            if (a.keys().isEmpty()) sb.append(" -");
            for (Key k : a.keys()) sb.append(' ').append(k.name());
            if (Float.floatToIntBits(a.yaw) != Float.floatToIntBits(yaw)) {
                sb.append(" yaw=").append(a.yaw);
                yaw = a.yaw;
            }
            if (Float.floatToIntBits(a.pitch) != Float.floatToIntBits(pitch)) {
                sb.append(" pitch=").append(a.pitch);
                pitch = a.pitch;
            }
            if (a.slot >= 0) sb.append(" slot=").append(a.slot);
            sb.append('\n');
            i = j;
        }
        return sb.toString();
    }

    private static boolean same(TickInput a, TickInput b) {
        return a.keys().equals(b.keys()) && a.slot == b.slot && Float.floatToIntBits(a.yaw) == Float.floatToIntBits(b.yaw)
            && Float.floatToIntBits(a.pitch) == Float.floatToIntBits(b.pitch);
    }
}
