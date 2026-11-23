package com.gameneko.aigame.narrative;

import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.InitializationValue;

import java.util.List;
import java.util.Map;

/** GM 输出的叙事、引擎意图、建议选项与确定字段值。
 * @param narration 叙事正文，空值转为空字符串
 * @param action 可空的引擎调用意图
 * @param options 玩家下一步建议，空值转为空列表
 * @param updates 本轮声明的类型化字段值，空值转为空对象
 */
public record NarrativeResponse(String narration, ActionIntent action, List<String> options,
                                Map<String, InitializationValue> updates) {
    /** 复制集合并规范化缺失的可选输出。 */
    public NarrativeResponse {
        narration = narration == null ? "" : narration;
        options = options == null ? List.of() : List.copyOf(options);
        updates = updates == null ? Map.of() : Map.copyOf(updates);
    }

    /** 创建纯叙事响应。
     * @param narration 叙事正文
     */
    public NarrativeResponse(String narration) {
        this(narration, null, List.of(), Map.of());
    }

    /** 创建包含引擎调用的响应。
     * @param narration 叙事正文
     * @param action 可空的引擎调用意图
     */
    public NarrativeResponse(String narration, ActionIntent action) {
        this(narration, action, List.of(), Map.of());
    }

    /** 创建带建议选项的响应。
     * @param narration 叙事正文
     * @param action 可空的引擎调用意图
     * @param options 玩家下一步建议
     */
    public NarrativeResponse(String narration, ActionIntent action, List<String> options) {
        this(narration, action, options, Map.of());
    }

    /** 校验声明数量、标识符及四种有限值类型，防止无界或无效输出。 */
    public void validateUpdates() {
        if (updates.size() > 16) throw new IllegalArgumentException("Too many narrative updates");
        updates.forEach((field, value) -> {
            if (!field.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid update field");
            if (!(value instanceof InitializationValue.Text || value instanceof InitializationValue.IntegerNumber
                    || value instanceof InitializationValue.DecimalNumber || value instanceof InitializationValue.Flag)) {
                throw new IllegalArgumentException("Invalid update value type");
            }
            if (value instanceof InitializationValue.Text text && text.value().length() > 8000) {
                throw new IllegalArgumentException("Update text too long");
            }
        });
    }
}
