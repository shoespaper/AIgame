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

    /** 校验叙事、选项、世界更新与行动意图的通用协议，具体动作规则由引擎校验。 */
    void validateProtocol() {
        if (narration.isBlank() || narration.length() > 6000) throw NarrativeProviderException.invalidOutput("叙述文本长度无效", null);
        if (options.isEmpty()) throw NarrativeProviderException.invalidOutput("叙事响应缺少候选行动", null);
        if (options.size() > 4 || options.stream().anyMatch(option -> option.isBlank() || option.length() > 12)) {
            throw NarrativeProviderException.invalidOutput("候选行动字段无效", null);
        }
        validateUpdates();
        if (action == null) return;
        if (!identifier(action.type())) throw NarrativeProviderException.invalidOutput("行动意图类型无效", null);
        if (action.target().length() > 256 || action.arguments().size() > 32) {
            throw NarrativeProviderException.invalidOutput("行动意图无效", null);
        }
        for (ActionIntent.Argument argument : action.arguments()) {
            if (!identifier(argument.name())) throw NarrativeProviderException.invalidOutput("行动参数名无效", null);
            if (argument.value() instanceof InitializationValue.Text text && text.value().length() > 1000) {
                throw NarrativeProviderException.invalidOutput("行动文本参数过长", null);
            }
        }
    }

    /** 判断行动类型或参数名是否为长度不超过 64 的协议标识符。
     * @param value 待检查的标识符文本
     */
    private static boolean identifier(String value) {
        return value.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    }

    /** 校验声明数量、标识符及四种有限值类型，防止无界或无效输出。 */
    public void validateUpdates() {
        if (updates.size() > 16) throw NarrativeProviderException.invalidOutput("Too many narrative updates", null);
        updates.forEach((field, value) -> {
            if (!field.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) throw NarrativeProviderException.invalidOutput("Invalid update field", null);
            if (!(value instanceof InitializationValue.Text || value instanceof InitializationValue.IntegerNumber
                    || value instanceof InitializationValue.DecimalNumber || value instanceof InitializationValue.Flag)) {
                throw NarrativeProviderException.invalidOutput("Invalid update value type", null);
            }
            if (value instanceof InitializationValue.Text text && text.value().length() > 8000) {
                throw NarrativeProviderException.invalidOutput("Update text too long", null);
            }
        });
    }
}
