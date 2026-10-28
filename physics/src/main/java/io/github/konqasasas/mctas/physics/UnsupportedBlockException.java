package io.github.konqasasas.mctas.physics;

/** A block whose behaviour in some situation the engine does not model. */
public final class UnsupportedBlockException extends RuntimeException {

    public UnsupportedBlockException(String message) {
        super(message);
    }
}
