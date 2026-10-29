package io.github.konqasasas.mctas.physics;

/**
 * Right-clicking with a block in hand: the part of {@code Minecraft.runTick} that runs before the player
 * moves. The view ray ({@code EntityRenderer.getMouseOver(1.0F)}) starts at the eyes where the last tick
 * left the player and points along this tick's yaw and pitch; {@code Minecraft.processKeyBinds} selects
 * the hotbar slot, then right-clicks on a press and every 4 ticks while the button is held
 * ({@code rightClickMouse}); {@code PlayerControllerMP.processRightClickBlock} and
 * {@code ItemBlock.onItemUse} place the block, which the move in the same tick then collides with.
 */
public final class Placement {

    /** {@code PlayerControllerMP.getBlockReachDistance()} in survival. */
    static final double REACH = 4.5F;
    /** {@code Minecraft.rightClickDelayTimer} after a right-click. */
    static final int RIGHT_CLICK_DELAY = 4;
    /**
     * Ticks after a sneak change is sent by which the server is taken to have resized its player. It does
     * so in its own tick ({@code NetHandlerPlayServer.update -> EntityPlayer.updateSize}), which is not in
     * step with the client's, so until then either height counts.
     */
    public static final int SNEAK_SETTLE = 3;

    private Placement() {
    }

    /** {@code RayTraceResult} of type BLOCK: the block, the face the ray entered and the point, in world coordinates. */
    public static final class Hit {
        public final int x;
        public final int y;
        public final int z;
        public final Facing side;
        public final double hitX;
        public final double hitY;
        public final double hitZ;

        Hit(int x, int y, int z, Facing side, double hitX, double hitY, double hitZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
            this.hitX = hitX;
            this.hitY = hitY;
            this.hitZ = hitZ;
        }
    }

    /**
     * Runs the right-click part of the tick on {@code s}: the delay timer, the hotbar selection and any
     * right-click. {@code s.rotationYaw} and {@code s.rotationPitch} must already hold this tick's angles.
     */
    static void beforeMove(BlockWorld base, PlayerState s, PlayerInput input) {
        if (s.rightClickDelay > 0) {
            --s.rightClickDelay;
        }
        if (input.slot >= 0) {
            s.selectedSlot = input.slot;
        }
        boolean use = input.has(PlayerInput.USE);
        if (use && !s.prevUse) {
            rightClick(base, s);
        }
        if (use && s.rightClickDelay == 0) {
            rightClick(base, s);
        }
        s.prevUse = use;
    }

    /** {@code Minecraft.rightClickMouse} for a survival player with an empty off hand. */
    private static void rightClick(BlockWorld base, PlayerState s) {
        s.rightClickDelay = RIGHT_CLICK_DELAY;
        BlockWorld world = s.world(base);
        try {
            Hit hit = lookingAt(world, s);
            if (hit == null) {
                return;
            }
            if (!s.shulker.isNone()) {
                // The server's lid pushes its copy of the player too, so where it has the player is not
                // known well enough to place a block or open another box.
                s.unsupportedAction = true;
                return;
            }
            BlockType target = world.getBlock(hit.x, hit.y, hit.z);
            BlockType item = s.hotbar.item(s.selectedSlot);
            boolean sneaking = s.isSneaking();
            if ((!sneaking || item == null) && target.activates()) {
                activate(world, s, hit, target, item);
                return;
            }
            if (item == null) {
                return;
            }
            if (target.activates() && s.sneakReportedTicks < SNEAK_SETTLE) {
                // The server may not have seen the sneak yet and activate the block instead.
                s.unsupportedAction = true;
                return;
            }
            // ItemBlock.canPlaceBlockOnSide, checked by processRightClickBlock before onItemUse.
            Facing side = hit.side;
            int px = hit.x;
            int py = hit.y;
            int pz = hit.z;
            if (VanillaBlocks.blockId(target.name()).equals("minecraft:snow_layer")) {
                side = Facing.UP;
            } else if (!target.isReplaceable(world, hit.x, hit.y, hit.z)) {
                px += hit.side.dx;
                py += hit.side.dy;
                pz += hit.side.dz;
            }
            if (!mayPlace(world, s, px, py, pz)) {
                return;
            }
            // ItemBlock.onItemUse
            int qx = hit.x;
            int qy = hit.y;
            int qz = hit.z;
            if (!target.isReplaceable(world, hit.x, hit.y, hit.z)) {
                qx += hit.side.dx;
                qy += hit.side.dy;
                qz += hit.side.dz;
            }
            if (!mayPlace(world, s, qx, qy, qz)) {
                return;
            }
            if (qy < 0 || qy >= 256) {
                // World.setBlockState refuses; onItemUse still succeeds without using the item.
                return;
            }
            if (!serverPlaces(world, s, hit, qx, qy, qz)) {
                // The client shows the block and uses the item, the server takes the block back a tick or
                // two later and the hotbar count stays wrong: neither is modelled.
                s.unsupportedAction = true;
                return;
            }
            s.placed = s.placed.with(qx, qy, qz, item);
            s.hotbar = s.hotbar.withOneUsed(s.selectedSlot);
        } catch (UnsupportedBlockException e) {
            s.unsupportedAction = true;
        }
    }

    /**
     * {@code onBlockActivated} of the block the player right-clicked without sneaking (or with an empty
     * hand). A button turns on at once on the client; a lever only answers the click there and changes
     * when the server says so, which does not change its selection box. Both power the block they hang
     * on, so either is unsupported next to anything that reacts to power or to a neighbour update
     * ({@link #reacts}). A shulker box opens ({@link #openShulker}). Every other block that does
     * something of its own (doors, chests, ...) is unsupported.
     */
    private static void activate(BlockWorld world, PlayerState s, Hit hit, BlockType target, BlockType item) {
        String id = VanillaBlocks.blockId(target.name());
        if (id.endsWith("_shulker_box")) {
            openShulker(world, s, hit, target, item);
            return;
        }
        boolean button = id.equals("minecraft:stone_button") || id.equals("minecraft:wooden_button");
        if (!button && !id.equals("minecraft:lever")) {
            s.unsupportedAction = true;
            return;
        }
        java.util.Map<String, String> props = VanillaBlocks.parseProperties(target.name());
        String facing = props.get("facing");
        if (facing == null || button && !props.containsKey("powered")) {
            // A block from an older problem file, named without its facing: its state is unknown.
            s.unsupportedAction = true;
            return;
        }
        // The server activates it too only if it does not take the player to be sneaking with a block in
        // hand ({@code PlayerInteractionManager.processRightClickBlock}).
        if (item != null && (s.serverSneakState || s.sneakReportedTicks < SNEAK_SETTLE)) {
            s.unsupportedAction = true;
            return;
        }
        Facing attached = attachedSide(facing);
        int ax = hit.x + attached.dx;
        int ay = hit.y + attached.dy;
        int az = hit.z + attached.dz;
        // BlockButton.notifyNeighbors and BlockLever's: the neighbours of the block and of the one it
        // hangs on, when it turns on and again when it turns off.
        for (Facing f : Facing.values()) {
            if (reacts(world.getBlock(hit.x + f.dx, hit.y + f.dy, hit.z + f.dz))
                    || f != attached.opposite() && reacts(world.getBlock(ax + f.dx, ay + f.dy, az + f.dz))) {
                s.unsupportedAction = true;
                return;
            }
        }
        if (!button) {
            return;
        }
        if (props.get("powered").equals("true")) {
            // BlockButton.onBlockActivated: already on, the click does nothing. One that may have turned
            // off again has no powered property here and was refused above.
            return;
        }
        s.pressed = s.pressed.with(hit.x, hit.y, hit.z, target);
    }

    /**
     * A shulker box: the client only answers the click, and the server opens the box if it takes the click
     * as one on the box, it is in reach, and nothing is in the way of the lid ({@code BlockShulkerBox
     * .onBlockActivated}). The lid then starts to move a tick later ({@link Shulker}).
     */
    private static void openShulker(BlockWorld world, PlayerState s, Hit hit, BlockType target, BlockType item) {
        String facing = VanillaBlocks.parseProperties(target.name()).get("facing");
        if (facing == null) {
            s.unsupportedAction = true;
            return;
        }
        // PlayerInteractionManager.processRightClickBlock: a player the server takes to be sneaking with a
        // block in hand uses the block instead.
        if (item != null && (s.serverSneakState || s.sneakReportedTicks < SNEAK_SETTLE)) {
            s.unsupportedAction = true;
            return;
        }
        if (!serverReaches(s, hit)) {
            return;
        }
        Facing f = Facing.valueOf(facing.toUpperCase(java.util.Locale.ROOT));
        if (Shulker.blocked(world, hit.x, hit.y, hit.z, f)) {
            return;
        }
        s.shulker = Shulker.clicked(hit.x, hit.y, hit.z, target, f);
    }

    /**
     * {@code NetHandlerPlayServer.processTryUseItemOnBlock}: the server acts on the click only below the
     * build limit and within 8 blocks of where the client last said the player was (Forge's reach
     * attribute, 5, plus 3).
     */
    private static boolean serverReaches(PlayerState s, Hit hit) {
        if (!(hit.y < 255 || hit.side != Facing.UP && hit.y < 256)) {
            return false;
        }
        double dx = s.lastReportedPosX - ((double) hit.x + 0.5D);
        double dy = s.lastReportedPosY - ((double) hit.y + 0.5D);
        double dz = s.lastReportedPosZ - ((double) hit.z + 0.5D);
        return dx * dx + dy * dy + dz * dz < 64.0D;
    }

    /**
     * Whether a neighbour update or power may change the block: it reacts to power or changes by itself.
     * Blocks that need support ({@link BlockType#isFragile}) are taken to have it, as in a world built by
     * playing; one that hangs in the air would break.
     */
    private static boolean reacts(BlockType t) {
        return t.reactsToPower() || t.isExcept(BlockType.UNSTABLE);
    }

    /** The side of a button or lever that touches the block it hangs on: opposite its facing. */
    private static Facing attachedSide(String facing) {
        switch (facing) {
            case "down":
            case "down_x":
            case "down_z":
                return Facing.UP;
            case "up":
            case "up_x":
            case "up_z":
                return Facing.DOWN;
            case "north":
                return Facing.SOUTH;
            case "south":
                return Facing.NORTH;
            case "west":
                return Facing.EAST;
            case "east":
                return Facing.WEST;
            default:
                throw new IllegalArgumentException(facing);
        }
    }

    /**
     * Whether the server surely places the block too. The click reaches it after the position the client
     * last sent ({@code NetHandlerPlayServer.processTryUseItemOnBlock}), and its {@code ItemBlock.onItemUse}
     * checks the block against its own copy of the player's box, which {@code Entity.setPosition} rebuilt
     * from that position, so it can differ from the client's box in the last bit or by a move too small
     * to have been sent. The world is the same on both sides.
     */
    private static boolean serverPlaces(BlockWorld world, PlayerState s, Hit hit, int x, int y, int z) {
        if (!serverReaches(s, hit)) {
            return false;
        }
        float height = s.serverSneakState ? 1.65F : 1.8F;
        if (intersects(serverBox(s, height), x, y, z)) {
            return false;
        }
        if (s.sneakReportedTicks < SNEAK_SETTLE && intersects(serverBox(s, s.serverSneakState ? 1.8F : 1.65F), x, y, z)) {
            return false;
        }
        return true;
    }

    /** {@code Entity.setPosition} at the last sent position, for a player of this height. */
    static Aabb serverBox(PlayerState s, float height) {
        float f = 0.6F / 2.0F;
        double x = s.lastReportedPosX;
        double y = s.lastReportedPosY;
        double z = s.lastReportedPosZ;
        return new Aabb(x - (double) f, y, z - (double) f, x + (double) f, y + (double) height, z + (double) f);
    }

    /** {@code AxisAlignedBB.intersects} with the block cell at (x, y, z): strict on every axis. */
    private static boolean intersects(Aabb b, int x, int y, int z) {
        return b.minX < x + 1.0D && b.maxX > x && b.minY < y + 1.0D && b.maxY > y && b.minZ < z + 1.0D && b.maxZ > z;
    }

    /**
     * {@code EntityPlayerSP.onUpdateWalkingPlayer}, the parts the server's placement check depends on: the
     * sneak state and the position sent at the end of each tick.
     */
    static void afterMove(PlayerState s) {
        boolean sneaking = s.isSneaking();
        if (sneaking != s.serverSneakState) {
            s.serverSneakState = sneaking;
            s.sneakReportedTicks = 0;
        } else if (s.sneakReportedTicks < SNEAK_SETTLE) {
            ++s.sneakReportedTicks;
        }
        double d0 = s.posX - s.lastReportedPosX;
        double d1 = s.box.minY - s.lastReportedPosY;
        double d2 = s.posZ - s.lastReportedPosZ;
        ++s.positionUpdateTicks;
        if (d0 * d0 + d1 * d1 + d2 * d2 > 9.0E-4D || s.positionUpdateTicks >= 20) {
            s.reportPosition();
        }
    }

    /**
     * {@code World.mayPlace} for a full block: no entity (the player) in the way, and the material there
     * is replaceable.
     */
    private static boolean mayPlace(BlockWorld world, PlayerState s, int x, int y, int z) {
        // checkNoEntityCollision
        if (intersects(s.box, x, y, z)) {
            return false;
        }
        // Material.isReplaceable, and Block.canPlaceBlockAt checks the same: a snow layer of any height is
        // replaced here, though Block.isReplaceable is true only for one layer.
        return world.getBlock(x, y, z).isMaterialReplaceable();
    }

    /** {@code EntityPlayer.getEyeHeight()}. */
    public static float eyeHeight(PlayerState s) {
        float f = 1.62F;
        // The sneaking height lowers the eyes too, so they stay low until the player has room to stand.
        if (!s.isSneaking() && s.height != 1.65F) {
            if (s.height == 0.6F) {
                f = 0.4F;
            }
        } else {
            f -= 0.08F;
        }
        return f;
    }

    /** {@code Entity.rayTrace(REACH, 1.0F)}: the block the player looks at, or null. */
    public static Hit lookingAt(BlockWorld world, PlayerState s) {
        double[] eye = {s.posX, s.posY + (double) eyeHeight(s), s.posZ};
        // Entity.getVectorForRotation
        float f = McMath.cos(-s.rotationYaw * 0.017453292F - (float) Math.PI);
        float f1 = McMath.sin(-s.rotationYaw * 0.017453292F - (float) Math.PI);
        float f2 = -McMath.cos(-s.rotationPitch * 0.017453292F);
        float f3 = McMath.sin(-s.rotationPitch * 0.017453292F);
        double lx = (double) (f1 * f2);
        double ly = (double) f3;
        double lz = (double) (f * f2);
        double[] end = {eye[0] + lx * REACH, eye[1] + ly * REACH, eye[2] + lz * REACH};
        return rayTraceBlocks(world, eye, end);
    }

    /** {@code World.rayTraceBlocks(start, end, false, false, true)}, keeping only a BLOCK result. */
    public static Hit rayTraceBlocks(BlockWorld world, double[] start, double[] end) {
        double sx = start[0];
        double sy = start[1];
        double sz = start[2];
        if (Double.isNaN(sx) || Double.isNaN(sy) || Double.isNaN(sz)
                || Double.isNaN(end[0]) || Double.isNaN(end[1]) || Double.isNaN(end[2])) {
            return null;
        }
        int i = McMath.floor(end[0]);
        int j = McMath.floor(end[1]);
        int k = McMath.floor(end[2]);
        int l = McMath.floor(sx);
        int i1 = McMath.floor(sy);
        int j1 = McMath.floor(sz);
        BlockType first = world.getBlock(l, i1, j1);
        if (first.rayHittable()) {
            Hit h = first.rayTrace(world, l, i1, j1, new double[] {sx, sy, sz}, end);
            if (h != null) {
                return h;
            }
        }
        int k1 = 200;
        while (k1-- >= 0) {
            if (Double.isNaN(sx) || Double.isNaN(sy) || Double.isNaN(sz)) {
                return null;
            }
            if (l == i && i1 == j && j1 == k) {
                return null;
            }
            boolean flag2 = true;
            boolean flag = true;
            boolean flag1 = true;
            double d0 = 999.0D;
            double d1 = 999.0D;
            double d2 = 999.0D;
            if (i > l) {
                d0 = (double) l + 1.0D;
            } else if (i < l) {
                d0 = (double) l + 0.0D;
            } else {
                flag2 = false;
            }
            if (j > i1) {
                d1 = (double) i1 + 1.0D;
            } else if (j < i1) {
                d1 = (double) i1 + 0.0D;
            } else {
                flag = false;
            }
            if (k > j1) {
                d2 = (double) j1 + 1.0D;
            } else if (k < j1) {
                d2 = (double) j1 + 0.0D;
            } else {
                flag1 = false;
            }
            double d3 = 999.0D;
            double d4 = 999.0D;
            double d5 = 999.0D;
            double d6 = end[0] - sx;
            double d7 = end[1] - sy;
            double d8 = end[2] - sz;
            if (flag2) {
                d3 = (d0 - sx) / d6;
            }
            if (flag) {
                d4 = (d1 - sy) / d7;
            }
            if (flag1) {
                d5 = (d2 - sz) / d8;
            }
            if (d3 == -0.0D) {
                d3 = -1.0E-4D;
            }
            if (d4 == -0.0D) {
                d4 = -1.0E-4D;
            }
            if (d5 == -0.0D) {
                d5 = -1.0E-4D;
            }
            Facing facing;
            if (d3 < d4 && d3 < d5) {
                facing = i > l ? Facing.WEST : Facing.EAST;
                double ny = sy + d7 * d3;
                double nz = sz + d8 * d3;
                sx = d0;
                sy = ny;
                sz = nz;
            } else if (d4 < d5) {
                facing = j > i1 ? Facing.DOWN : Facing.UP;
                double nx = sx + d6 * d4;
                double nz = sz + d8 * d4;
                sx = nx;
                sy = d1;
                sz = nz;
            } else {
                facing = k > j1 ? Facing.NORTH : Facing.SOUTH;
                double nx = sx + d6 * d5;
                double ny = sy + d7 * d5;
                sx = nx;
                sy = ny;
                sz = d2;
            }
            l = McMath.floor(sx) - (facing == Facing.EAST ? 1 : 0);
            i1 = McMath.floor(sy) - (facing == Facing.UP ? 1 : 0);
            j1 = McMath.floor(sz) - (facing == Facing.SOUTH ? 1 : 0);
            BlockType t = world.getBlock(l, i1, j1);
            if (t.rayHittable()) {
                Hit h = t.rayTrace(world, l, i1, j1, new double[] {sx, sy, sz}, end);
                if (h != null) {
                    return h;
                }
            }
        }
        return null;
    }

    /** {@code Block.rayTrace}: the segment against one block-local box. */
    static Hit rayTrace(int x, int y, int z, double[] start, double[] end, Aabb box) {
        double[] a = {start[0] - (double) x, start[1] - (double) y, start[2] - (double) z};
        double[] b = {end[0] - (double) x, end[1] - (double) y, end[2] - (double) z};
        // AxisAlignedBB.calculateIntercept
        double[] v = collideX(box, box.minX, a, b);
        Facing f = Facing.WEST;
        double[] v1 = collideX(box, box.maxX, a, b);
        if (v1 != null && isClosest(a, v, v1)) {
            v = v1;
            f = Facing.EAST;
        }
        v1 = collideY(box, box.minY, a, b);
        if (v1 != null && isClosest(a, v, v1)) {
            v = v1;
            f = Facing.DOWN;
        }
        v1 = collideY(box, box.maxY, a, b);
        if (v1 != null && isClosest(a, v, v1)) {
            v = v1;
            f = Facing.UP;
        }
        v1 = collideZ(box, box.minZ, a, b);
        if (v1 != null && isClosest(a, v, v1)) {
            v = v1;
            f = Facing.NORTH;
        }
        v1 = collideZ(box, box.maxZ, a, b);
        if (v1 != null && isClosest(a, v, v1)) {
            v = v1;
            f = Facing.SOUTH;
        }
        if (v == null) {
            return null;
        }
        return new Hit(x, y, z, f, v[0] + (double) x, v[1] + (double) y, v[2] + (double) z);
    }

    private static boolean isClosest(double[] a, double[] current, double[] candidate) {
        return current == null || squareDistance(a, candidate) < squareDistance(a, current);
    }

    static double squareDistance(double[] a, double[] b) {
        double d0 = b[0] - a[0];
        double d1 = b[1] - a[1];
        double d2 = b[2] - a[2];
        return d0 * d0 + d1 * d1 + d2 * d2;
    }

    private static double[] collideX(Aabb box, double x, double[] a, double[] b) {
        double[] v = intermediate(a, b, 0, x);
        return v != null && v[1] >= box.minY && v[1] <= box.maxY && v[2] >= box.minZ && v[2] <= box.maxZ ? v : null;
    }

    private static double[] collideY(Aabb box, double y, double[] a, double[] b) {
        double[] v = intermediate(a, b, 1, y);
        return v != null && v[0] >= box.minX && v[0] <= box.maxX && v[2] >= box.minZ && v[2] <= box.maxZ ? v : null;
    }

    private static double[] collideZ(Aabb box, double z, double[] a, double[] b) {
        double[] v = intermediate(a, b, 2, z);
        return v != null && v[0] >= box.minX && v[0] <= box.maxX && v[1] >= box.minY && v[1] <= box.maxY ? v : null;
    }

    /** {@code Vec3d.getIntermediateWithXValue} and its Y and Z twins. */
    private static double[] intermediate(double[] a, double[] b, int axis, double value) {
        double d0 = b[0] - a[0];
        double d1 = b[1] - a[1];
        double d2 = b[2] - a[2];
        double d = axis == 0 ? d0 : axis == 1 ? d1 : d2;
        if (d * d < 1.0000000116860974E-7D) {
            return null;
        }
        double d3 = (value - a[axis]) / d;
        return d3 >= 0.0D && d3 <= 1.0D ? new double[] {a[0] + d0 * d3, a[1] + d1 * d3, a[2] + d2 * d3} : null;
    }
}
