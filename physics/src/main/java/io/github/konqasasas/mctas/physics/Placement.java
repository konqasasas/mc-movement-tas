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
        BlockWorld world = s.placed.over(base);
        try {
            Hit hit = lookingAt(world, s);
            if (hit == null) {
                return;
            }
            BlockType target = world.getBlock(hit.x, hit.y, hit.z);
            BlockType item = s.hotbar.item(s.selectedSlot);
            boolean sneaking = s.isSneaking();
            if ((!sneaking || item == null) && target.activates()) {
                // A door, chest, button, ... does its own thing, which is not modelled.
                s.unsupportedAction = true;
                return;
            }
            if (item == null) {
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
            s.placed = s.placed.with(qx, qy, qz, item);
            s.hotbar = s.hotbar.withOneUsed(s.selectedSlot);
        } catch (UnsupportedBlockException e) {
            s.unsupportedAction = true;
        }
    }

    /**
     * {@code World.mayPlace} for a full block: no entity (the player) in the way, and the material there
     * is replaceable.
     */
    private static boolean mayPlace(BlockWorld world, PlayerState s, int x, int y, int z) {
        Aabb b = s.box;
        // checkNoEntityCollision: AxisAlignedBB.intersects is strict on every axis.
        if (b.minX < x + 1.0D && b.maxX > x && b.minY < y + 1.0D && b.maxY > y && b.minZ < z + 1.0D && b.maxZ > z) {
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
