package com.gameneko.aigame.application;

/** 世界构建和开局确认的进度，用于向玩家展示当前阶段。
 * @param phase 当前步骤的稳定标识
 * @param attempt 当前尝试序号，非重试步骤为空
 * @param limit 当前步骤的尝试上限，非重试步骤为空
 * @param failed 是否为单次失败
 * @param message 面向玩家的简短提示
 */
public record CreationProgress(String phase, Integer attempt, Integer limit, boolean failed, String message) {}
