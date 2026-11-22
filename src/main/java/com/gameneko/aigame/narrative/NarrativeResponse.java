package com.gameneko.aigame.narrative;

import com.gameneko.aigame.engine.ActionIntent;

import java.util.List;

/** Narration output. Text is presentational; action, when present, is one engine call the server resolves. */
public record NarrativeResponse(String narration, ActionIntent action, List<String> options,
                                List<NarrativeInitializationQuestion> initializationQuestions,
                                List<NarrativeParseCandidate> parseCandidates) {
    public NarrativeResponse {
        narration = narration == null ? "" : narration;
        options = options == null ? List.of() : List.copyOf(options);
        initializationQuestions = initializationQuestions == null ? List.of() : List.copyOf(initializationQuestions);
        parseCandidates = parseCandidates == null ? List.of() : List.copyOf(parseCandidates);
    }

    /** 纯叙事响应的便捷构造。 */
    public NarrativeResponse(String narration) {
        this(narration, null, List.of(), List.of(), List.of());
    }

    /** 带引擎调用的叙事响应便捷构造。 */
    public NarrativeResponse(String narration, ActionIntent action) {
        this(narration, action, List.of(), List.of(), List.of());
    }

    /** 带引擎调用与纯文本候选的叙事响应便捷构造。 */
    public NarrativeResponse(String narration, ActionIntent action, List<String> options) {
        this(narration, action, options, List.of(), List.of());
    }
}
