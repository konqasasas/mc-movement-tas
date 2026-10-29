package io.github.konqasasas.mctas.physics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PhysicsTest {

    private static SparseBlockWorld floor() {
        return new SparseBlockWorld().fill(-30, 63, -30, 30, 63, 30, BlockType.STONE);
    }

    @Test
    public void standingStillIsAFixedPoint() {
        SparseBlockWorld w = floor();
        PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        PlayerState next = Physics.tick(w, s, PlayerInput.of(0, 0.0F));
        assertNull(next.firstDifference(s));
    }

    /**
     * The client's box touches the cell at x = 1.0 exactly, but the server rebuilds its box from the
     * position (1.3000000119209287 - 0.3F = 0.9999999999999998) and refuses the block (F-012).
     */
    @Test
    public void placingIsDecidedOnTheServersBoxToo() {
        SparseBlockWorld w = floor();
        BlockType cobble = VanillaBlocks.require("minecraft:cobblestone");
        w.setBlock(-1, 65, 3, cobble);
        PlayerInput click = new PlayerInput(PlayerInput.USE, 90.0F, 17.0F);

        PlayerState s = PlayerState.standing(1.3000000119209287D, 64, 3.5, 90.0F);
        Aabb b = s.box;
        s.box = new Aabb(1.0D, b.minY, b.minZ, 1.0D + (double) 0.6F, b.maxY, b.maxZ);
        s.hotbar = Hotbar.EMPTY.with(0, cobble, 5);
        Physics.step(w, s, click);
        assertTrue(s.unsupportedAction);
        assertTrue(s.placed.isEmpty());

        PlayerState t = PlayerState.standing(1.5, 64, 3.5, 90.0F);
        t.hotbar = Hotbar.EMPTY.with(0, cobble, 5);
        Physics.step(w, t, click);
        assertFalse(t.unsupportedAction);
        assertEquals(cobble, t.placed.get(0, 65, 3));
        assertEquals(4, t.hotbar.count(0));
    }

    /**
     * Standing on a shulker box facing up: the click opens it a tick later (F-704), the lid lifts the
     * player 0.06 in the first tick it moves, as on the real game, and once the screen is closed the lid
     * goes down again and the box is the world's block once more.
     */
    @Test
    public void aShulkerBoxLidLiftsThePlayer() {
        SparseBlockWorld w = floor();
        w.setBlock(0, 63, 0, VanillaBlocks.require("minecraft:purple_shulker_box[facing=up]"));
        PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        PlayerInput still = new PlayerInput(0, 0.0F, 90.0F);
        Physics.step(w, s, new PlayerInput(PlayerInput.USE, 0.0F, 90.0F));
        assertEquals(Shulker.Event.OPEN, s.shulker.event);
        assertEquals(64.0D, s.posY, 0.0D);
        Physics.step(w, s, still);
        assertTrue(s.shulker.screen);
        assertEquals(Shulker.Status.OPENING, s.shulker.status);
        assertEquals(64.06D, s.posY, 1e-6D);
        for (int i = 0; i < 10; i++) {
            Physics.step(w, s, still);
        }
        assertEquals(Shulker.Status.OPENED, s.shulker.status);
        assertEquals(64.5D, s.posY, 0.02D);
        assertFalse(s.unsupportedAction);

        Physics.step(w, s, new PlayerInput(PlayerInput.CLOSE, 0.0F, 90.0F));
        assertFalse(s.shulker.screen);
        for (int i = 0; i < 30; i++) {
            Physics.step(w, s, still);
        }
        assertTrue(s.shulker.isNone());
        assertEquals(64.0D, s.posY, 0.0D);
        assertFalse(s.unsupportedAction);

        PlayerState t = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        Physics.step(w, t, new PlayerInput(PlayerInput.USE, 0.0F, 90.0F));
        Physics.step(w, t, new PlayerInput(PlayerInput.W, 0.0F, 90.0F));
        assertTrue("keys do nothing while the screen is open", t.unsupportedAction);
    }

    @Test
    public void tickDoesNotModifyItsArgument() {
        SparseBlockWorld w = floor();
        PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        PlayerState before = s.copy();
        Physics.tick(w, s, PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 30.0F));
        assertNull(s.firstDifference(before));
    }

    @Test
    public void sprintJumpFromRest() {
        SparseBlockWorld w = floor();
        PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        Physics.step(w, s, PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 0.0F));
        assertTrue(s.sprinting);
        assertFalse(s.onGround);
        // 0.42 jump, one tick of gravity and drag.
        assertEquals((0.41999998688697815D - 0.08D) * 0.9800000190734863D, s.motionY, 0.0D);
        assertEquals(10, s.jumpTicks);
        // The air acceleration picks up the sprint flag only after this tick's move. It is computed
        // in double and rounds to the float just below 0.026F.
        assertEquals((float) ((double) 0.02F + (double) 0.02F * 0.3D), s.jumpMovementFactor, 0.0F);
        assertTrue(s.jumpMovementFactor < 0.026F);
    }

    @Test
    public void sneakingStopsAtTheEdge() {
        SparseBlockWorld w = new SparseBlockWorld().setBlock(0, 63, 0, BlockType.STONE);
        PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        for (int i = 0; i < 100; i++) {
            Physics.step(w, s, PlayerInput.of(PlayerInput.W | PlayerInput.SNEAK, 0.0F));
        }
        assertTrue(s.onGround);
        assertEquals(64.0D, s.posY, 0.0D);
        // Facing +Z: the box may overhang the block by less than 0.3 + the 0.05 search step.
        assertTrue(s.posZ < 1.3D + 0.05D);
        assertEquals(1.65F, s.height, 0.0F);
    }

    @Test
    public void copiesAreIndependent() {
        SparseBlockWorld w = floor();
        PlayerState a = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        PlayerState b = a.copy();
        Physics.step(w, b, PlayerInput.of(PlayerInput.W, 0.0F));
        assertNull(PlayerState.standing(0.5, 64, 0.5, 0.0F).firstDifference(a));
        SparseBlockWorld w2 = w.copy().setBlock(0, 64, 3, BlockType.STONE);
        assertEquals(BlockType.AIR, w.getBlock(0, 64, 3));
        assertEquals(BlockType.STONE, w2.getBlock(0, 64, 3));
    }

    @Test
    public void sparseWorldKeysRoundTrip() {
        final int[][] pts = {{0, 0, 0}, {-1, 255, -1}, {29999999, 64, -29999999}, {-30000000, 1, 12345}};
        SparseBlockWorld w = new SparseBlockWorld();
        for (int[] p : pts) {
            w.setBlock(p[0], p[1], p[2], BlockType.STONE);
        }
        final int[] seen = {0};
        w.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                boolean found = false;
                for (int[] p : pts) {
                    found |= p[0] == x && p[1] == y && p[2] == z;
                }
                assertTrue(x + "," + y + "," + z, found);
                seen[0]++;
            }
        });
        assertEquals(pts.length, seen[0]);
        assertEquals(BlockType.AIR, w.getBlock(0, 256, 0));
        assertEquals(BlockType.AIR, w.getBlock(0, -1, 0));
    }
}
