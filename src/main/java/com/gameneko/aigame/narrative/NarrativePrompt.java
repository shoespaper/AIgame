package com.gameneko.aigame.narrative;

import java.util.List;

/** 提供器无关的叙事提示，明确区分确认事实、玩家输入与历史对话。 */
public record NarrativePrompt(String systemInstruction, String userInput,
                              NarrativeSnapshot snapshot, String locale,
                              List<NarrativeRequest.ConversationMessage> history, boolean repairOutput) {
    /** 兼容不带对话历史的提示构造。 */
    /** @param systemInstruction 系统叙事指令 @param userInput 玩家本轮输入 */
    /** @param snapshot 服务端确认的当前状态快照 @param locale 叙事语言标识 */
    public NarrativePrompt(String systemInstruction, String userInput, NarrativeSnapshot snapshot, String locale) {
        this(systemInstruction, userInput, snapshot, locale, List.of());
    }

    /** 构造无需格式纠错的提示。
     * @param systemInstruction 系统叙事指令
     * @param userInput 玩家本轮输入
     * @param snapshot 服务端确认的当前状态快照
     * @param locale 叙事语言标识
     * @param history 仅供参考的近期对话
     */
    public NarrativePrompt(String systemInstruction, String userInput, NarrativeSnapshot snapshot, String locale,
                           List<NarrativeRequest.ConversationMessage> history) {
        this(systemInstruction, userInput, snapshot, locale, history, false);
    }

    /** 规范化提示字段并复制只读对话历史。
     * @param systemInstruction 系统叙事指令
     * @param userInput 玩家本轮输入
     * @param snapshot 服务端确认的当前状态快照
     * @param locale 叙事语言标识
     * @param history 仅供参考的近期对话
     * @param repairOutput 是否允许在游玩回合结算前对模型输出纠错
     */
    public NarrativePrompt {
        systemInstruction = systemInstruction == null ? "" : systemInstruction;
        userInput = userInput == null ? "" : userInput;
        if (snapshot == null) throw new IllegalArgumentException("Narrative prompt snapshot is required");
        locale = locale == null || locale.isBlank() ? "zh-CN" : locale;
        history = history == null ? List.of() : List.copyOf(history);
    }
}
