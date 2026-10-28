package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;

/** One log line per tick. Doubles and floats are written exactly, as Java hex literals. */
final class StateFormat {

    static final String HEADER = "tick keys yaw pitch | posX posY posZ motionX motionY motionZ"
            + " boxMinX boxMinY boxMinZ boxMaxX boxMaxY boxMaxZ onGround collH collV sprinting sneaking"
            + " jumpTicks jumpMovementFactor sprintToggleTimer fallDistance height | check";

    private StateFormat() {
    }

    static String line(int tick, PlayerInput in, PlayerState s, String check) {
        StringBuilder b = new StringBuilder(400);
        b.append(tick).append(' ');
        if (in == null) {
            b.append("- - -");
        } else {
            // A hotbar slot picked this tick follows the keys after a slash, as in "WU/3".
            String k = in.keyString() + (in.slot >= 0 ? "/" + in.slot : "");
            b.append(k.isEmpty() ? "-" : k).append(' ').append(Float.toHexString(in.yaw)).append(' ')
                    .append(Float.toHexString(in.pitch));
        }
        b.append(" |");
        d(b, s.posX);
        d(b, s.posY);
        d(b, s.posZ);
        d(b, s.motionX);
        d(b, s.motionY);
        d(b, s.motionZ);
        d(b, s.box.minX);
        d(b, s.box.minY);
        d(b, s.box.minZ);
        d(b, s.box.maxX);
        d(b, s.box.maxY);
        d(b, s.box.maxZ);
        b.append(' ').append(s.onGround ? 1 : 0).append(' ').append(s.collidedHorizontally ? 1 : 0)
                .append(' ').append(s.collidedVertically ? 1 : 0).append(' ').append(s.sprinting ? 1 : 0)
                .append(' ').append(s.prevSneak ? 1 : 0).append(' ').append(s.jumpTicks)
                .append(' ').append(Float.toHexString(s.jumpMovementFactor)).append(' ').append(s.sprintToggleTimer)
                .append(' ').append(Float.toHexString(s.fallDistance)).append(' ').append(Float.toHexString(s.height));
        b.append(" | ").append(check);
        return b.toString();
    }

    private static void d(StringBuilder b, double v) {
        b.append(' ').append(Double.toHexString(v));
    }
}
