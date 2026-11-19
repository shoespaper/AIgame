package com.gameneko.aigame.api;

import java.util.Map;

public record InitializationValidationResponse(boolean accepted, Map<String, String> errors) {}
