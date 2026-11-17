package com.gameneko.aigame.content;

/** Raised when a requested world is missing or its definition is invalid. */
public class WorldContentException extends RuntimeException {
    public WorldContentException(String message) {
        super(message);
    }

    public WorldContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
