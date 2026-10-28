package io.github.konqasasas.mctas.problem.cli.importer;

import java.io.IOException;

/** Numeric 1.12.2 blocks by position, as a save or schematic stores them. */
interface BlockSource {

    /** The block id and metadata at a position as {@code id << 4 | meta}, or -1 where the source has no data. */
    int get(int x, int y, int z) throws IOException;

    /** A short description for messages and the problem file, such as the save path. */
    String describe();
}
