package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.GameRequestException;

import java.util.List;

/** 玩家叙事请求；快照由服务端创建，对话文本仅作为叙事上下文。 */
public record NarrativeRequest(String gameId, long expectedRevision, String playerInput,
                               String locale, NarrativeSnapshot snapshot, List<ConversationMessage> history) {
    /** 兼容不带对话历史的调用。 */
    /** @param gameId 游戏标识 @param expectedRevision 客户端观察到的修订号 @param playerInput 玩家本轮输入 */
    /** @param locale 叙事语言标识 @param snapshot 服务端构建的权威快照 */
    public NarrativeRequest(String gameId, long expectedRevision, String playerInput, String locale, NarrativeSnapshot snapshot) {
        this(gameId, expectedRevision, playerInput, locale, snapshot, List.of());
    }

    /** 规范化玩家输入和语言，并限制叙事请求大小。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端提交的修订号
     * @param playerInput 可空的玩家本轮输入
     * @param locale 可空的叙事语言标识
     * @param snapshot 服务端构造的权威快照
     * @param history 可空的近期对话上下文
     */
    public NarrativeRequest {
        if (gameId == null || gameId.isBlank()) throw new IllegalArgumentException("Game id is required");
        playerInput = playerInput == null ? "" : playerInput.trim();
        if (playerInput.length() > 2000) throw GameRequestException.invalid("Player narrative input is too long");
        locale = locale == null || locale.isBlank() ? "zh-CN" : locale.trim();
        if (locale.length() > 32) throw GameRequestException.invalid("Narrative locale is invalid");
        if (snapshot == null) throw new IllegalArgumentException("Narrative snapshot is required");
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** 玩家或 GM 的一条非权威叙事消息。 */
    /** 一条不参与状态结算的玩家或 GM 对话。 */
    /** @param role 对话角色，只允许 player 或 gm @param text 对话正文 */
    public record ConversationMessage(String role, String text) {}
}
