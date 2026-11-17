package com.gameneko.aigame.api;

/** Client command envelope; the server maps the type to a closed AP action. */
public record GameActionRequest(long expectedRevision, String type) {
}
