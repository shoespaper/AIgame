package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;

import java.util.List;

/** 叙事 HTTP 输入及仅供模型参考的近期对话上下文。 */
public record NarrationHttpRequest(long expectedRevision, String playerInput, String locale,
                                   List<ConversationMessage> history) {
    /** 限制并清理客户端提供的叙事上下文，避免其成为权威状态输入。 */
    /** @param expectedRevision 客户端读取到的游戏修订号 @param playerInput 玩家本轮输入 */
    /** @param locale 叙事语言标识 @param history 仅供模型参考的玩家与 GM 对话 */
    public NarrationHttpRequest {
        List<ConversationMessage> messages = history == null ? List.of() : history;
        if (messages.size() > 40) messages = messages.subList(messages.size() - 40, messages.size());
        for (ConversationMessage message : messages) {
            if (message == null || !List.of("player", "gm").contains(message.role())
                    || message.text() == null || message.text().length() > 4000) {
                throw GameRequestException.invalid("Narrative history is invalid");
            }
        }
        history = List.copyOf(messages);
    }

    /** 单条展示与模型参考消息；内容不会直接写入游戏状态。 */
    public record ConversationMessage(String role, String text) {}
}
