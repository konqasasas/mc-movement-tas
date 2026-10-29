package io.github.konqasasas.mctas.physics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Pressure plates the player pressed and the trapdoors they opened, immutable and shared between copies
 * like {@link Pressed}.
 *
 * <p>On the integrated server (D-700) a client tick's packets are handled at the start of the server tick
 * that follows it, then the world ticks, then {@code NetHandlerPlayServer.update} runs the server's own
 * player. What the server changes reaches the client at the start of the next client tick. {@link #serverTick}
 * plays the three phases after each client tick:
 *
 * <ol>
 * <li>Packets: {@code processPlayer} moves the server's player to the position the client last sent
 * ({@link PlayerState#lastReportedPosX}), and {@code Entity.doBlockCollisions} at that box presses an
 * unpressed plate whose {@code PRESSURE_AABB} the box overlaps ({@code BlockBasePressurePlate.onEntityCollision}).
 * The plate schedules its next check {@code tickRate} later and notifies its neighbours and those of the block
 * under it; a trapdoor among them opens or shuts to match {@code World.isBlockPowered}
 * ({@code BlockTrapDoor.neighborChanged}).</li>
 * <li>World: a plate whose check is due ({@code updateTick}) stays pressed and checks again {@code tickRate}
 * later while the box still overlaps it, and is let go otherwise.</li>
 * <li>Network: the server's player moves by its own motion from that box ({@code EntityLivingBase.travel}
 * with no input) and collides again, then is put back. The engine does not keep the server's motion; it
 * bounds the move ({@link PlayerState#serverDrift} across, this tick's fall downwards) and calls a plate
 * that the bounded box could reach "maybe pressed". If the next tick's packets press it, the press reached
 * the client a tick early or not; that tick is unsupported if the player came near a block the press
 * changed or right-clicked. If they do not press it, the plate may stay pressed for {@code tickRate} or not,
 * and the state is unsupported.</li>
 * </ol>
 *
 * <p>A door follows the power of both its halves ({@code BlockDoor.neighborChanged} on the lower half, which
 * the upper half passes its notifications to): when the power changes from what the upper half's {@code POWERED}
 * holds, the door opens or shuts to match. The engine does not name {@code POWERED}; it is the power before
 * the change, since every change of power notifies the door (a world built consistently).
 *
 * <p>A redstone block gives a trapdoor or a door beside it weak power 15 and does nothing when notified. Any other
 * block that reacts to power (D-702) next to a plate that changes, any other power source the trapdoor's
 * power depends on, and a box within {@link #EDGE} of a plate's edge are unsupported.
 */
public final class Mechanisms {

    /** Not a mechanism the engine models. */
    static final int NONE_KIND = 0;
    static final int WOODEN_PLATE = 1;
    static final int STONE_PLATE = 2;
    static final int LIGHT_PLATE = 3;
    static final int HEAVY_PLATE = 4;
    static final int TRAPDOOR = 5;
    /** A block that can give power ({@code Block.canProvidePower}) and is not a plate. */
    static final int OTHER_SOURCE = 6;
    /**
     * {@code BlockCompressedPowered}: weak power 15 on every side, no strong power, no {@code neighborChanged}
     * and never a normal cube, so the engine models it.
     */
    static final int REDSTONE_BLOCK = 7;
    /** {@code BlockDoor}, either half. */
    static final int DOOR = 8;

    /** How close to a plate's edge a box is taken as on the edge: the server's box can differ in the last bits. */
    static final double EDGE = 1.0E-6D;

    /** {@code BlockBasePressurePlate.PRESSURE_AABB}. */
    private static final Aabb PRESSURE_AABB = new Aabb(0.125D, 0.0D, 0.125D, 0.875D, 0.25D, 0.875D);

    public static final Mechanisms NONE = new Mechanisms(new int[0], new BlockType[0], new BlockType[0], new int[0], new int[0]);

    /** Positions of the blocks that differ from the base world on the server or the client, or wait for a check. */
    private final int[] xyz;
    private final BlockType[] server;
    private final BlockType[] client;
    /** World phases until the plate's scheduled check, 0 for none. */
    private final int[] due;
    /** Plates the server may have pressed in its last network phase. */
    private final int[] maybe;

    private Mechanisms(int[] xyz, BlockType[] server, BlockType[] client, int[] due, int[] maybe) {
        this.xyz = xyz;
        this.server = server;
        this.client = client;
        this.due = due;
        this.maybe = maybe;
    }

    /** Every field given, for the oracle that reads them off its own worlds. */
    public static Mechanisms of(int[] xyz, BlockType[] server, BlockType[] client, int[] due, int[] maybe) {
        if (xyz.length == 0 && maybe.length == 0) {
            return NONE;
        }
        return new Mechanisms(xyz.clone(), server.clone(), client.clone(), due.clone(), maybe.clone());
    }

    public boolean isNone() {
        return xyz.length == 0 && maybe.length == 0;
    }

    public int size() {
        return server.length;
    }

    public int x(int i) {
        return xyz[3 * i];
    }

    public int y(int i) {
        return xyz[3 * i + 1];
    }

    public int z(int i) {
        return xyz[3 * i + 2];
    }

    public BlockType server(int i) {
        return server[i];
    }

    public BlockType client(int i) {
        return client[i];
    }

    public int due(int i) {
        return due[i];
    }

    /** The plates that may have been pressed, as x, y, z triples. */
    public int[] maybe() {
        return maybe.clone();
    }

    // ---- kinds ----

    static int kindOf(String blockId) {
        switch (blockId) {
            case "minecraft:wooden_pressure_plate":
                return WOODEN_PLATE;
            case "minecraft:stone_pressure_plate":
                return STONE_PLATE;
            case "minecraft:light_weighted_pressure_plate":
                return LIGHT_PLATE;
            case "minecraft:heavy_weighted_pressure_plate":
                return HEAVY_PLATE;
            case "minecraft:trapdoor":
            case "minecraft:iron_trapdoor":
                return TRAPDOOR;
            case "minecraft:redstone_block":
                return REDSTONE_BLOCK;
            case "minecraft:wooden_door":
            case "minecraft:spruce_door":
            case "minecraft:birch_door":
            case "minecraft:jungle_door":
            case "minecraft:acacia_door":
            case "minecraft:dark_oak_door":
            case "minecraft:iron_door":
                return DOOR;
            case "minecraft:redstone_wire":
            case "minecraft:redstone_torch":
            case "minecraft:unlit_redstone_torch":
            case "minecraft:lever":
            case "minecraft:stone_button":
            case "minecraft:wooden_button":
            case "minecraft:tripwire_hook":
            case "minecraft:detector_rail":
            case "minecraft:daylight_detector":
            case "minecraft:daylight_detector_inverted":
            case "minecraft:powered_repeater":
            case "minecraft:unpowered_repeater":
            case "minecraft:powered_comparator":
            case "minecraft:unpowered_comparator":
            case "minecraft:observer":
            case "minecraft:trapped_chest":
                return OTHER_SOURCE;
            default:
                return NONE_KIND;
        }
    }

    /** True for a block that can give power and is not a pressure plate: the engine does not model it (D-702). */
    public static boolean isOtherSource(String blockId) {
        return kindOf(blockId) == OTHER_SOURCE;
    }

    /**
     * True for a power source the engine does not model that does nothing when a neighbour changes: in 1.12
     * {@code BlockDaylightDetector} does not override {@code neighborChanged}, so a plate pressed next to one
     * leaves it as it was. Its power still depends on the time of day, so a trapdoor beside it is unsupported.
     */
    public static boolean notifyInert(BlockType t) {
        switch (VanillaBlocks.blockId(t.name())) {
            case "minecraft:daylight_detector":
            case "minecraft:daylight_detector_inverted":
                return true;
            default:
                return false;
        }
    }

    /** True for the blocks this class moves: pressure plates, trapdoors and doors. */
    public static boolean isModelled(BlockType t) {
        int k = t.mechanism();
        return k >= WOODEN_PLATE && k <= TRAPDOOR || k == DOOR;
    }

    /**
     * A copy with the client's blocks replaced, for the recorder that reads them off the live world. A plate
     * the world shows with the power this one has keeps its name here (it may be named without its power).
     */
    public Mechanisms withClient(BlockType[] shown) {
        if (shown.length != client.length) {
            throw new IllegalArgumentException("one block per entry");
        }
        BlockType[] c = shown.clone();
        for (int i = 0; i < c.length; i++) {
            if (samePlate(client[i], c[i])) {
                c[i] = client[i];
            }
        }
        return new Mechanisms(xyz, server, c, due, maybe);
    }

    public static boolean isPlate(BlockType t) {
        int k = t.mechanism();
        return k >= WOODEN_PLATE && k <= HEAVY_PLATE;
    }

    /**
     * True if {@code a} and {@code b} are the same plate with the same power, a plate named without its power
     * (an older problem file) counting as unpressed.
     */
    public static boolean samePlate(BlockType a, BlockType b) {
        return isPlate(a) && isPlate(b) && VanillaBlocks.blockId(a.name()).equals(VanillaBlocks.blockId(b.name()))
                && power(a) == power(b);
    }

    /** {@code tickRate}: 20 for wooden and stone plates, 10 for weighted ones. */
    static int tickRate(BlockType plate) {
        int k = plate.mechanism();
        return k == LIGHT_PLATE || k == HEAVY_PLATE ? 10 : 20;
    }

    /** {@code getRedstoneStrength}; a plate named without its power (an older problem file) is unpressed. */
    static int power(BlockType plate) {
        Map<String, String> p = VanillaBlocks.parseProperties(plate.name());
        String v = p.get(plate.mechanism() <= STONE_PLATE ? "powered" : "power");
        if (v == null) {
            return 0;
        }
        if (plate.mechanism() <= STONE_PLATE) {
            return v.equals("true") ? 15 : 0;
        }
        return Integer.parseInt(v);
    }

    /** {@code setRedstoneStrength}. */
    static BlockType withPower(BlockType plate, int power) {
        String id = VanillaBlocks.blockId(plate.name());
        java.util.TreeMap<String, String> p = new java.util.TreeMap<String, String>();
        if (plate.mechanism() <= STONE_PLATE) {
            p.put("powered", power > 0 ? "true" : "false");
        } else {
            p.put("power", Integer.toString(power));
        }
        return VanillaBlocks.require(VanillaBlocks.name(id, p));
    }

    /** {@code computeRedstoneStrength} with the player, and nothing else, on the plate. */
    static int strengthWithPlayer(BlockType plate) {
        switch (plate.mechanism()) {
            case LIGHT_PLATE:
                return weighted(15);
            case HEAVY_PLATE:
                return weighted(150);
            default:
                return 15;
        }
    }

    /** {@code BlockPressurePlateWeighted.computeRedstoneStrength} for one entity. */
    private static int weighted(int maxWeight) {
        float f = (float) Math.min(maxWeight, 1) / (float) maxWeight;
        return McMath.ceil(f * 15.0F);
    }

    // ---- views ----

    private int index(int x, int y, int z) {
        for (int i = 0; i < server.length; i++) {
            if (xyz[3 * i] == x && xyz[3 * i + 1] == y && xyz[3 * i + 2] == z) {
                return i;
            }
        }
        return -1;
    }

    /** {@code base} as the client shows it. */
    public BlockWorld over(BlockWorld base) {
        return view(base, client);
    }

    /** {@code base} as the server has it. */
    public BlockWorld serverOver(BlockWorld base) {
        return view(base, server);
    }

    private BlockWorld view(final BlockWorld base, final BlockType[] states) {
        if (states.length == 0) {
            return base;
        }
        return new BlockWorld() {
            @Override
            public BlockType getBlock(int x, int y, int z) {
                for (int i = 0; i < states.length; i++) {
                    if (xyz[3 * i] == x && xyz[3 * i + 1] == y && xyz[3 * i + 2] == z) {
                        return states[i];
                    }
                }
                return base.getBlock(x, y, z);
            }

            @Override
            public boolean mayContainLiquid() {
                return base.mayContainLiquid();
            }
        };
    }

    /**
     * True if a block the client is about to take in from the server has a collision box that overlaps
     * {@code box}: a trapdoor shutting or opening into the player. {@code base} is the world without them.
     */
    public boolean arrivalHits(BlockWorld base, Aabb box) {
        BlockWorld world = serverOver(base);
        List<Aabb> out = new ArrayList<Aabb>();
        for (int i = 0; i < server.length; i++) {
            if (server[i] != client[i]) {
                server[i].addCollisionBoxes(world, xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2], box, out);
                if (server[i].mechanism() == DOOR) {
                    // The upper half takes open from the lower half.
                    world.getBlock(xyz[3 * i], xyz[3 * i + 1] + 1, xyz[3 * i + 2])
                            .addCollisionBoxes(world, xyz[3 * i], xyz[3 * i + 1] + 1, xyz[3 * i + 2], box, out);
                }
                if (!out.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** At the start of a client tick: the changes the server sent arrive. */
    Mechanisms arrive() {
        boolean changed = false;
        for (int i = 0; i < server.length; i++) {
            changed |= server[i] != client[i];
        }
        if (!changed) {
            return this;
        }
        return new Mechanisms(xyz, server, server.clone(), due, maybe);
    }

    // ---- the server's tick ----

    /**
     * The server tick after the client tick that just ended in {@code s}. {@code startBox} is the player's box
     * at the start of that client tick, {@code use} whether it right-clicked, {@code jumped} whether the server
     * takes the tick's packet as a jump ({@code processPlayer}: on the ground before, off it now, and higher).
     */
    static void serverTick(BlockWorld base, PlayerState s, Aabb startBox, boolean use, boolean jumped) {
        // The bound on the server player's motion across: a jump adds the sprint boost, every tick's friction
        // takes its share, and EntityLivingBase.onLivingUpdate drops what is left below 0.003.
        double drift = s.serverDrift;
        if (jumped && s.sprinting) {
            // EntityLivingBase.jump: 0.2F along the view, through the sine table.
            drift += 0.2001D;
        }
        BlockWorld view = s.placed.over(base);
        double hw = (double) s.width / 2.0D;
        Aabb box = new Aabb(s.lastReportedPosX - hw, s.lastReportedPosY, s.lastReportedPosZ - hw,
                s.lastReportedPosX + hw, s.lastReportedPosY + (double) s.height, s.lastReportedPosZ + hw);
        // EntityLivingBase.travel's friction, from the block under the player where the server's move starts.
        float friction = s.onGround ? view.getBlock(McMath.floor(s.lastReportedPosX), McMath.floor(box.minY - 1.0D),
                McMath.floor(s.lastReportedPosZ)).slipperiness() * 0.91F : 0.91F;
        double fall = Math.max(0.0D, -s.motionY);
        Aabb reach = new Aabb(box.minX - drift, box.minY - fall, box.minZ - drift, box.maxX + drift, box.maxY, box.maxZ + drift);
        if (s.mech.isNone() && !((base.mayContainPlates() || !s.placed.isEmpty()) && anyPlate(view, reach))) {
            // Nothing pressed and no plate within reach: the server changes nothing.
            drift *= (double) friction;
            s.serverDrift = drift < 0.003D ? 0.0D : drift;
            return;
        }
        Work w = new Work(s.mech, view);

        // Packets.
        int[] wasMaybe = s.mech.maybe;
        List<int[]> pressedNow = new ArrayList<int[]>();
        List<List<int[]>> changedBy = new ArrayList<List<int[]>>();
        forEachCell(box, (x, y, z) -> {
            BlockType b = w.get(x, y, z);
            if (!isPlate(b) || power(b) != 0) {
                return;
            }
            int t = touches(box, x, y, z);
            if (t == EDGE_TOUCH) {
                w.unsupported = true;
            } else if (t == YES) {
                List<int[]> changed = new ArrayList<int[]>();
                w.setPower(x, y, z, b, strengthWithPlayer(b), changed);
                w.schedule(x, y, z, tickRate(b));
                pressedNow.add(new int[] {x, y, z});
                changedBy.add(changed);
            }
        });
        Aabb swept = union(startBox, s.box);
        for (int m = 0; m < wasMaybe.length; m += 3) {
            int found = -1;
            for (int j = 0; j < pressedNow.size(); j++) {
                int[] p = pressedNow.get(j);
                if (p[0] == wasMaybe[m] && p[1] == wasMaybe[m + 1] && p[2] == wasMaybe[m + 2]) {
                    found = j;
                }
            }
            if (found < 0) {
                // Pressed a tick early for its whole tickRate, or not at all.
                w.unsupported = true;
                continue;
            }
            // Pressed in the last tick's network phase or in this tick's packets: the client saw it this tick or
            // will see it in the next.
            if (use) {
                w.unsupported = true;
            }
            for (int[] c : changedBy.get(found)) {
                // The plate itself has no collision box.
                if (c[0] == wasMaybe[m] && c[1] == wasMaybe[m + 1] && c[2] == wasMaybe[m + 2]) {
                    continue;
                }
                if (swept.intersects(new Aabb(c[0], c[1], c[2], c[0] + 1, c[1] + 1, c[2] + 1))) {
                    w.unsupported = true;
                }
            }
        }

        // World: the scheduled checks.
        w.age();
        for (int[] p : w.dueNow()) {
            BlockType b = w.get(p[0], p[1], p[2]);
            int now = power(b);
            int t = touches(box, p[0], p[1], p[2]);
            if (t == EDGE_TOUCH) {
                w.unsupported = true;
            }
            int strength = t == YES ? strengthWithPlayer(b) : 0;
            if (strength != now) {
                w.setPower(p[0], p[1], p[2], b, strength, new ArrayList<int[]>());
            }
            if (strength > 0) {
                w.schedule(p[0], p[1], p[2], tickRate(b));
            }
        }

        // Network: where the server's own move could take its player.
        List<int[]> maybe = new ArrayList<int[]>();
        forEachCell(reach, (x, y, z) -> {
            BlockType b = w.get(x, y, z);
            if (isPlate(b) && power(b) == 0 && touches(reach, x, y, z) != NO) {
                maybe.add(new int[] {x, y, z});
            }
        });
        drift *= (double) friction;
        s.serverDrift = drift < 0.003D ? 0.0D : drift;
        s.mech = w.build(maybe);
        if (w.unsupported) {
            s.unsupportedAction = true;
        }
    }

    /** True if a plate is among the blocks {@code reach} overlaps, shrunk by 0.001. */
    private static boolean anyPlate(BlockWorld world, Aabb reach) {
        int x0 = McMath.floor(reach.minX + 0.001D);
        int y0 = McMath.floor(reach.minY + 0.001D);
        int z0 = McMath.floor(reach.minZ + 0.001D);
        int x1 = McMath.floor(reach.maxX - 0.001D);
        int y1 = McMath.floor(reach.maxY - 0.001D);
        int z1 = McMath.floor(reach.maxZ - 0.001D);
        for (int x = x0; x <= x1; ++x) {
            for (int y = y0; y <= y1; ++y) {
                for (int z = z0; z <= z1; ++z) {
                    if (isPlate(world.getBlock(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Aabb union(Aabb a, Aabb b) {
        return new Aabb(Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
    }

    private interface CellVisitor {
        void visit(int x, int y, int z);
    }

    /** {@code Entity.doBlockCollisions}: every block the box overlaps, shrunk by 0.001. */
    private static void forEachCell(Aabb bb, CellVisitor v) {
        int x0 = McMath.floor(bb.minX + 0.001D);
        int y0 = McMath.floor(bb.minY + 0.001D);
        int z0 = McMath.floor(bb.minZ + 0.001D);
        int x1 = McMath.floor(bb.maxX - 0.001D);
        int y1 = McMath.floor(bb.maxY - 0.001D);
        int z1 = McMath.floor(bb.maxZ - 0.001D);
        for (int x = x0; x <= x1; ++x) {
            for (int y = y0; y <= y1; ++y) {
                for (int z = z0; z <= z1; ++z) {
                    v.visit(x, y, z);
                }
            }
        }
    }

    static final int NO = 0;
    static final int YES = 1;
    static final int EDGE_TOUCH = 2;

    /**
     * Whether the plate at (x, y, z) finds the box on it: {@code doBlockCollisions} reaches its cell and the box
     * overlaps its {@code PRESSURE_AABB} ({@code World.getEntitiesWithinAABB}). {@link #EDGE_TOUCH} when the
     * answer changes if the box moves by {@link #EDGE}.
     */
    static int touches(Aabb box, int x, int y, int z) {
        boolean in = touchesExactly(box.grow(-EDGE, -EDGE, -EDGE), x, y, z);
        boolean out = touchesExactly(box.grow(EDGE, EDGE, EDGE), x, y, z);
        if (in != out) {
            return EDGE_TOUCH;
        }
        return in ? YES : NO;
    }

    private static boolean touchesExactly(Aabb bb, int x, int y, int z) {
        if (x < McMath.floor(bb.minX + 0.001D) || x > McMath.floor(bb.maxX - 0.001D)
                || y < McMath.floor(bb.minY + 0.001D) || y > McMath.floor(bb.maxY - 0.001D)
                || z < McMath.floor(bb.minZ + 0.001D) || z > McMath.floor(bb.maxZ - 0.001D)) {
            return false;
        }
        return bb.intersects(PRESSURE_AABB.offset(x, y, z));
    }

    /** The server's blocks while a tick changes them. */
    private static final class Work {
        final List<int[]> pos = new ArrayList<int[]>();
        final List<BlockType> server = new ArrayList<BlockType>();
        final List<BlockType> client = new ArrayList<BlockType>();
        final List<Integer> due = new ArrayList<Integer>();
        final BlockWorld base;
        boolean unsupported;
        /** The plate whose change is being passed on, and its state before it. */
        int[] changing;
        BlockType changingWas;

        Work(Mechanisms m, BlockWorld base) {
            this.base = base;
            for (int i = 0; i < m.server.length; i++) {
                pos.add(new int[] {m.xyz[3 * i], m.xyz[3 * i + 1], m.xyz[3 * i + 2]});
                server.add(m.server[i]);
                client.add(m.client[i]);
                due.add(m.due[i]);
            }
        }

        int find(int x, int y, int z) {
            for (int i = 0; i < pos.size(); i++) {
                int[] p = pos.get(i);
                if (p[0] == x && p[1] == y && p[2] == z) {
                    return i;
                }
            }
            return -1;
        }

        BlockType get(int x, int y, int z) {
            int i = find(x, y, z);
            return i >= 0 ? server.get(i) : base.getBlock(x, y, z);
        }

        void set(int x, int y, int z, BlockType b) {
            BlockType was = base.getBlock(x, y, z);
            if (b.mechanism() == was.mechanism() && isPlate(b) && power(b) == power(was)) {
                // Back to the base world's plate, which may be named without its power.
                b = was;
            }
            int i = find(x, y, z);
            if (i < 0) {
                pos.add(new int[] {x, y, z});
                server.add(b);
                client.add(base.getBlock(x, y, z));
                due.add(0);
            } else {
                server.set(i, b);
            }
        }

        void schedule(int x, int y, int z, int ticks) {
            int i = find(x, y, z);
            due.set(i, ticks);
        }

        /** The world phase begins: every scheduled check comes one tick nearer. */
        void age() {
            for (int i = 0; i < due.size(); i++) {
                if (due.get(i) > 0) {
                    due.set(i, due.get(i) - 1);
                }
            }
        }

        /** The checks due now, in the order they were scheduled; their entries are cleared. */
        List<int[]> dueNow() {
            List<int[]> out = new ArrayList<int[]>();
            for (int i = 0; i < due.size(); i++) {
                if (due.get(i) == 0 && isPlate(server.get(i)) && power(server.get(i)) > 0) {
                    out.add(pos.get(i));
                }
            }
            return out;
        }

        /** {@code updateState}'s change: the plate's new power, then {@code updateNeighbors}. */
        void setPower(int x, int y, int z, BlockType plate, int power, List<int[]> changed) {
            changing = new int[] {x, y, z};
            changingWas = get(x, y, z);
            set(x, y, z, withPower(plate, power));
            changed.add(new int[] {x, y, z});
            notifyAround(x, y, z, changed);
            notifyAround(x, y - 1, z, changed);
        }

        /** {@code World.notifyNeighborsOfStateChange}: west, east, down, up, north, south. */
        void notifyAround(int x, int y, int z, List<int[]> changed) {
            neighborChanged(x - 1, y, z, changed);
            neighborChanged(x + 1, y, z, changed);
            neighborChanged(x, y - 1, z, changed);
            neighborChanged(x, y + 1, z, changed);
            neighborChanged(x, y, z - 1, changed);
            neighborChanged(x, y, z + 1, changed);
        }

        void neighborChanged(int x, int y, int z, List<int[]> changed) {
            BlockType b = get(x, y, z);
            int k = b.mechanism();
            if (k == TRAPDOOR) {
                Map<String, String> p = VanillaBlocks.parseProperties(b.name());
                String open = p.get("open");
                if (open == null) {
                    // Named without its state (an older problem file).
                    unsupported = true;
                    return;
                }
                // BlockTrapDoor.neighborChanged: the plate can provide power, so the trapdoor follows isBlockPowered.
                boolean powered = isBlockPowered(x, y, z);
                if (Boolean.parseBoolean(open) != powered) {
                    p.put("open", Boolean.toString(powered));
                    set(x, y, z, VanillaBlocks.require(VanillaBlocks.name(VanillaBlocks.blockId(b.name()), p)));
                    changed.add(new int[] {x, y, z});
                }
            } else if (k == DOOR) {
                doorChanged(x, y, z, b, changed);
            } else if (k == OTHER_SOURCE && !notifyInert(b)
                    || k != OTHER_SOURCE && !isPlate(b) && (b.reactsToPower() || b.isExcept(BlockType.UNSTABLE))) {
                // A plate only checks the block under it, which stays.
                unsupported = true;
            }
        }

        /** {@code BlockDoor.neighborChanged} on the lower half; the upper half passes the notification down. */
        void doorChanged(int x, int y, int z, BlockType b, List<int[]> changed) {
            String id = VanillaBlocks.blockId(b.name());
            int ly = "upper".equals(VanillaBlocks.parseProperties(b.name()).get("half")) ? y - 1 : y;
            BlockType lower = get(x, ly, z);
            BlockType upper = get(x, ly + 1, z);
            Map<String, String> lp = VanillaBlocks.parseProperties(lower.name());
            if (!VanillaBlocks.blockId(lower.name()).equals(id) || !"lower".equals(lp.get("half")) || lp.get("open") == null
                    || !VanillaBlocks.blockId(upper.name()).equals(id)
                    || !"upper".equals(VanillaBlocks.parseProperties(upper.name()).get("half"))
                    || !get(x, ly - 1, z).isNormalCube()) {
                // A door that breaks (a half missing, nothing solid under it) or is named without its state.
                unsupported = true;
                return;
            }
            boolean powered = isBlockPowered(x, ly, z) | isBlockPowered(x, ly + 1, z);
            if (powered != poweredBefore(x, ly, z) && Boolean.parseBoolean(lp.get("open")) != powered) {
                lp.put("open", Boolean.toString(powered));
                set(x, ly, z, VanillaBlocks.require(VanillaBlocks.name(id, lp)));
                // Both halves change shape.
                changed.add(new int[] {x, ly, z});
                changed.add(new int[] {x, ly + 1, z});
            }
        }

        /** The upper half's {@code POWERED}: the door's power with the changing plate as it was. */
        private boolean poweredBefore(int x, int ly, int z) {
            BlockType now = get(changing[0], changing[1], changing[2]);
            set(changing[0], changing[1], changing[2], changingWas);
            boolean was = isBlockPowered(x, ly, z) | isBlockPowered(x, ly + 1, z);
            set(changing[0], changing[1], changing[2], now);
            return was;
        }

        /**
         * {@code World.isBlockPowered}. Every side is looked at, not only up to the first powered one, so that
         * another power source anywhere around is unsupported whatever the plates do.
         */
        boolean isBlockPowered(int x, int y, int z) {
            boolean powered = false;
            for (Facing f : Facing.values()) {
                powered |= redstonePower(x + f.dx, y + f.dy, z + f.dz, f) > 0;
            }
            return powered;
        }

        /** {@code World.getRedstonePower}: a normal cube passes on the strong power into it. */
        int redstonePower(int x, int y, int z, Facing side) {
            BlockType b = get(x, y, z);
            if (b.isNormalCube()) {
                if (b.mechanism() == OTHER_SOURCE) {
                    unsupported = true;
                }
                int max = 0;
                for (Facing f : Facing.values()) {
                    max = Math.max(max, strongPower(x + f.dx, y + f.dy, z + f.dz, f));
                }
                return max;
            }
            return weakPower(b);
        }

        /** {@code getStrongPower}: a plate gives it only to the block under it. */
        int strongPower(int x, int y, int z, Facing side) {
            BlockType b = get(x, y, z);
            if (b.mechanism() == OTHER_SOURCE) {
                unsupported = true;
                return 0;
            }
            return isPlate(b) && side == Facing.UP ? power(b) : 0;
        }

        int weakPower(BlockType b) {
            if (b.mechanism() == OTHER_SOURCE) {
                unsupported = true;
                return 0;
            }
            if (b.mechanism() == REDSTONE_BLOCK) {
                return 15;
            }
            return isPlate(b) ? power(b) : 0;
        }

        Mechanisms build(List<int[]> maybe) {
            int n = 0;
            for (int i = 0; i < pos.size(); i++) {
                if (keep(i)) {
                    n++;
                }
            }
            int[] xyz = new int[3 * n];
            BlockType[] sv = new BlockType[n];
            BlockType[] cl = new BlockType[n];
            int[] d = new int[n];
            int j = 0;
            for (int i = 0; i < pos.size(); i++) {
                if (keep(i)) {
                    int[] p = pos.get(i);
                    xyz[3 * j] = p[0];
                    xyz[3 * j + 1] = p[1];
                    xyz[3 * j + 2] = p[2];
                    sv[j] = server.get(i);
                    cl[j] = client.get(i);
                    d[j] = due.get(i);
                    j++;
                }
            }
            int[] mb = new int[3 * maybe.size()];
            for (int i = 0; i < maybe.size(); i++) {
                System.arraycopy(maybe.get(i), 0, mb, 3 * i, 3);
            }
            if (n == 0 && mb.length == 0) {
                return NONE;
            }
            return new Mechanisms(xyz, sv, cl, d, mb);
        }

        private boolean keep(int i) {
            int[] p = pos.get(i);
            BlockType b = base.getBlock(p[0], p[1], p[2]);
            return server.get(i) != b || client.get(i) != b || due.get(i) > 0;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Mechanisms)) {
            return false;
        }
        Mechanisms m = (Mechanisms) o;
        if (m.server.length != server.length || !Arrays.equals(m.maybe, maybe)) {
            return false;
        }
        for (int i = 0; i < server.length; i++) {
            int j = m.index(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]);
            if (j < 0 || !server[i].name().equals(m.server[j].name()) || !client[i].name().equals(m.client[j].name())
                    || due[i] != m.due[j]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (isNone()) {
            return 0;
        }
        int h = Arrays.hashCode(maybe);
        for (int i = 0; i < server.length; i++) {
            // Summed so that the order of the entries does not matter.
            h += ((xyz[3 * i] * 31 + xyz[3 * i + 1]) * 31 + xyz[3 * i + 2]) * 31 * 31
                    + server[i].name().hashCode() * 7 + client[i].name().hashCode() * 3 + due[i];
        }
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < server.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(xyz[3 * i]).append(',').append(xyz[3 * i + 1]).append(',').append(xyz[3 * i + 2]).append(' ')
                    .append(server[i].name());
            if (client[i] != server[i]) {
                sb.append(" (client ").append(client[i].name()).append(')');
            }
            if (due[i] > 0) {
                sb.append(" due ").append(due[i]);
            }
        }
        for (int m = 0; m < maybe.length; m += 3) {
            sb.append(" maybe ").append(maybe[m]).append(',').append(maybe[m + 1]).append(',').append(maybe[m + 2]);
        }
        return sb.toString();
    }
}
