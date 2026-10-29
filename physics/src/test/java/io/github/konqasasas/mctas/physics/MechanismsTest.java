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
}
