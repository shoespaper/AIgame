package com.gameneko.aigame.api;

public record NarratedActionRequest(GameActionRequest action, String playerInput, String locale) {}
