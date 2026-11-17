package com.gameneko.aigame.api;

/** Checkpoint request; authoritative state is loaded server-side. */
public record SaveGameRequest(long expectedRevision) {
}
