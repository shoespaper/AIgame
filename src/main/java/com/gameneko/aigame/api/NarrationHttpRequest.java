package com.gameneko.aigame.api;

public record NarrationHttpRequest(long expectedRevision, String playerInput, String locale) {}
