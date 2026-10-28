package io.github.konqasasas.mctas.improve;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Small random edits near one tick: drop or repeat a tick, swap two ticks, press or release jump,
 * turn the view, switch between W, W+A and W+D while keeping the direction of movement, copy a
 * neighbouring tick, release the movement keys. One to three edits within a few ticks of each
 * other, then the run is judged again from the first edited tick.
 */
final class Micro {
    private Micro() {
    }

    static Run mutate(Course c, Run cur, Random rnd) {
        List<PlayerInput> in = new ArrayList<PlayerInput>(Arrays.asList(cur.inputs).subList(0, cur.goalTick));
        int n = in.size();
        int center = rnd.nextInt(n);
        int edits = 1 + (rnd.nextDouble() < 0.35 ? 1 + rnd.nextInt(2) : 0);
        int first = Integer.MAX_VALUE;
        for (int e = 0; e < edits; e++) {
            int i = Math.max(0, Math.min(in.size() - 1, center + (e == 0 ? 0 : rnd.nextInt(9) - 4)));
            first = Math.min(first, edit(in, i, rnd));
        }
        if (first == Integer.MAX_VALUE || in.isEmpty()) return null;
        return c.evaluate(cur, first, Edits.pad(in));
    }

    /** One edit at tick index {@code i}; returns the first index it changed, or MAX_VALUE for none. */
    static int edit(List<PlayerInput> in, int i, Random rnd) {
        PlayerInput x = in.get(i);
        switch (rnd.nextInt(10)) {
            case 0:
                if (in.size() < 2) return Integer.MAX_VALUE;
                in.remove(i);
                return i;
            case 1:
                in.add(i, x);
                return i;
            case 2:
                if (i + 1 >= in.size()) return Integer.MAX_VALUE;
                in.set(i, in.get(i + 1));
                in.set(i + 1, x);
                return i;
            case 3:
                in.set(i, Edits.withKeys(x, x.keys ^ PlayerInput.JUMP));
                return i;
            case 4: {
                float[] sig = {0.25F, 1, 4, 15};
                in.set(i, Edits.withYaw(x, x.yaw + (float) rnd.nextGaussian() * sig[rnd.nextInt(sig.length)]));
                return i;
            }
            case 5: {
                int len = 2 + rnd.nextInt(20);
                float d = (float) rnd.nextGaussian() * (rnd.nextBoolean() ? 1 : 5);
                for (int k = i; k < Math.min(in.size(), i + len); k++) in.set(k, Edits.withYaw(in.get(k), in.get(k).yaw + d));
                return i;
            }
            case 6: {
                float dir = Edits.moveDirection(x);
                if (Float.isNaN(dir)) return Integer.MAX_VALUE;
                int mode = x.has(PlayerInput.A) ? 1 : x.has(PlayerInput.D) ? 2 : 0;
                int m = (mode + 1 + rnd.nextInt(2)) % 3;
                // Switch a whole stretch of identical inputs at once half the time.
                int end = i + 1;
                if (rnd.nextBoolean()) while (end < in.size() && in.get(end).equals(x)) end++;
                for (int k = i; k < end; k++) in.set(k, Edits.withMode(in.get(k), dir, m));
                return i;
            }
            case 7: {
                int j = rnd.nextBoolean() ? i - 1 : i + 1;
                if (j < 0 || j >= in.size()) return Integer.MAX_VALUE;
                in.set(i, in.get(j));
                return i;
            }
            case 8:
                in.set(i, Edits.withKeys(x, x.keys & ~(PlayerInput.W | PlayerInput.A | PlayerInput.D | PlayerInput.S)));
                return i;
            default: {
                // Move a jump press one tick earlier or later.
                int j = i;
                while (j < in.size() && !in.get(j).has(PlayerInput.JUMP)) j++;
                if (j >= in.size()) return Integer.MAX_VALUE;
                int k = rnd.nextBoolean() ? j - 1 : j + 1;
                if (k < 0 || k >= in.size()) return Integer.MAX_VALUE;
                PlayerInput a = in.get(j), b = in.get(k);
                in.set(k, Edits.withKeys(b, b.keys | PlayerInput.JUMP));
                in.set(j, Edits.withKeys(a, a.keys & ~PlayerInput.JUMP));
                return Math.min(j, k);
            }
        }
    }
}
