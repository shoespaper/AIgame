package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProvider;

/** Deterministic fallback used when no external model is configured. */
public final class LocalNarrativeProvider implements NarrativeProvider {
    @Override
    public NarrativeResponse respond(NarrativePrompt prompt) {
        var state = prompt.snapshot().character();
        var events = prompt.snapshot().confirmedEvents();
        String eventText = events.isEmpty() ? "暂无新确认事件。" : "已确认事件：" +
                events.stream().map(event -> event.type() + "(" + event.amount() + ")")
                        .collect(java.util.stream.Collectors.joining("、"));
        String location = prompt.snapshot().world().location() == null
                ? "旅途" : prompt.snapshot().world().location();
        String narration = state.name() + "来到第" + prompt.snapshot().world().day() +
                "天的" + location + "。" + eventText;
        return new NarrativeResponse(narration, java.util.List.of(), java.util.List.of());
    }
}
