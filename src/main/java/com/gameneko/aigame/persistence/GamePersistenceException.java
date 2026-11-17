package com.gameneko.aigame.persistence;

/** Indicates an invalid or unavailable local game save. */
public class GamePersistenceException extends RuntimeException {
    public GamePersistenceException(String message) { super(message); }
    public GamePersistenceException(String message, Throwable cause) { super(message, cause); }
}
