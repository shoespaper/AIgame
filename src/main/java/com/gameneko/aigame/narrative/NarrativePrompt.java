package com.gameneko.aigame.narrative;

/** Provider-neutral prompt with an explicit separation between confirmed facts and player text. */
public record NarrativePrompt(String systemInstruction, String userInput,
                              NarrativeSnapshot snapshot, String locale) {
    public NarrativePrompt {
        systemInstruction = systemInstruction == null ? "" : systemInstruction;
        userInput = userInput == null ? "" : userInput;
        if (snapshot == null) throw new IllegalArgumentException("Narrative prompt snapshot is required");
        locale = locale == null || locale.isBlank() ? "zh-CN" : locale;
    }
}
