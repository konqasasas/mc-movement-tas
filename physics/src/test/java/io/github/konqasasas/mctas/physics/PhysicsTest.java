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
