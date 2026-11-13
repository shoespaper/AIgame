package com.gameneko.aigame.engine;

public record ActionValidation(boolean accepted, String reason) {
    public static ActionValidation accept() {
        return new ActionValidation(true, "");
    }

    public static ActionValidation reject(String reason) {
        return new ActionValidation(false, reason);
    }
}
