package io.github.konqasasas.mctas.physics;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A plate named without its power, as in an older problem file, is the unpressed plate the game shows. */
public class MechanismsTest {

    private static final BlockType BARE = VanillaBlocks.partial("minecraft:wooden_pressure_plate", new java.util.HashMap<String, String>());
    private static final BlockType OFF = VanillaBlocks.require("minecraft:wooden_pressure_plate[powered=false]");
    private static final BlockType ON = VanillaBlocks.require("minecraft:wooden_pressure_plate[powered=true]");

    @Test
    public void bareNameIsUnpressed() {
        assertTrue(Mechanisms.samePlate(BARE, OFF));
        assertFalse(Mechanisms.samePlate(BARE, ON));
        assertFalse(Mechanisms.samePlate(BARE, VanillaBlocks.require("minecraft:stone_pressure_plate[powered=false]")));
        assertTrue(Mechanisms.samePlate(VanillaBlocks.partial("minecraft:light_weighted_pressure_plate", new java.util.HashMap<String, String>()),
                VanillaBlocks.require("minecraft:light_weighted_pressure_plate[power=0]")));
        assertFalse(Mechanisms.samePlate(BlockType.STONE, BlockType.STONE));
    }

    @Test
    public void clientKeepsTheBareName() {
        Mechanisms m = Mechanisms.of(new int[] {0, 0, 0}, new BlockType[] {ON}, new BlockType[] {BARE}, new int[] {20},
                new int[0]);
        assertSame(BARE, m.withClient(new BlockType[] {OFF}).client(0));
        assertSame(ON, m.withClient(new BlockType[] {ON}).client(0));
    }

    @Test
    public void plateOnRedstoneBlockIsSupported() {
        // BlockCompressedPowered has no neighborChanged: pressing the plate on it changes nothing there.
        SparseBlockWorld w = new SparseBlockWorld().fill(-5, 63, -5, 5, 63, 5, BlockType.STONE);
        w.setBlock(0, 63, 0, VanillaBlocks.require("minecraft:redstone_block"));
        w.setBlock(0, 64, 0, VanillaBlocks.require("minecraft:stone_pressure_plate[powered=false]"));
        PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        for (int i = 0; i < 5; i++) {
            Physics.step(w, s, PlayerInput.of(0, 0.0F));
        }
        assertFalse(s.unsupportedAction);
        assertFalse(s.mech.isNone());

        // A redstone torch there is a source the engine does not model, so it stays unsupported.
        w.setBlock(1, 64, 0, VanillaBlocks.require("minecraft:redstone_torch[facing=up]"));
        w.setBlock(0, 64, 0, VanillaBlocks.require("minecraft:stone_pressure_plate[powered=false]"));
        w.setBlock(0, 63, 0, BlockType.STONE);
        PlayerState t = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        for (int i = 0; i < 5; i++) {
            Physics.step(w, t, PlayerInput.of(0, 0.0F));
        }
        assertTrue(t.unsupportedAction);
    }

    @Test
    public void plateOpensTheDoorBesideIt() {
        SparseBlockWorld w = new SparseBlockWorld().fill(-5, 63, -5, 5, 63, 5, BlockType.STONE);
        w.setBlock(0, 64, 0, VanillaBlocks.require("minecraft:stone_pressure_plate[powered=false]"));
        w.setBlock(1, 64, 0, VanillaBlocks.require("minecraft:iron_door[facing=east,half=lower,hinge=left,open=false]"));
        w.setBlock(1, 65, 0, VanillaBlocks.require("minecraft:iron_door[facing=east,half=upper,hinge=left,open=false]"));
        PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0.0F);
        for (int i = 0; i < 3; i++) {
            Physics.step(w, s, PlayerInput.of(0, 0.0F));
        }
        assertFalse(s.unsupportedAction);
        assertTrue(s.mech.toString().contains("1,64,0 minecraft:iron_door[facing=east,half=lower,hinge=left,open=true]"));
    }
}
