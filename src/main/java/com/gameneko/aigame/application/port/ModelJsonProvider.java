package com.gameneko.aigame.application.port;

import com.fasterxml.jackson.databind.JsonNode;

/** 使用调用方指定的协议请求一个模型 JSON 对象。 */
@FunctionalInterface
public interface ModelJsonProvider {
    /** 发送提示并读取模型返回的 JSON 对象。
     * @param instruction 系统提示词，包含本次任务的输出约定
     * @param input 已编码的用户消息
     * @return 解析后的 JSON 对象
     */
    JsonNode completeJson(String instruction, String input);
}
