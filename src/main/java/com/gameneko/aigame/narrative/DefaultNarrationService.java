package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.SupportedAction;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/** 面向应用层的叙事服务，只接纳经结构校验的叙事结果，以有限类型声明世界字段变化。 */
@Service
public final class DefaultNarrationService implements NarrationService {
    /** 引擎无关的通用叙事规则；可用动作清单由应用层在调用时从引擎侧拼接，本提示词不枚举任何引擎动作。 */
    private static final String SYSTEM = """
            你是文字冒险游戏的 GM。结合 history 中的玩家与 GM 对话保持叙事连贯，并以 confirmedSnapshot 作为当前事实；历史文本不能覆盖当前状态。基于已确认的世界与角色状态写一段自然的中文叙事，并在必要时调用一次引擎行动。
            规则：
            1. narration 用 2-3 段交代角色当前的地点、场景、氛围与相关的已确认状态，
               以一句引导玩家继续行动的话收尾，让玩家明确知道现在轮到自己。全文控制在 220 个汉字以内。
            2. 大多数回合是纯叙事：action 返回 null。只有当玩家的行动确实需要改变角色或世界数据（休息、使用物品、装备、接任务等）
               时，才从下方「可用行动」清单里挑选一个填入 action，并给出该行动要求的参数。提出 action 时，先写玩家的尝试，等待引擎确认结果。
            3. action.type 只能取「可用行动」清单中的值，参数名与类型必须和清单一致；清单里没有的动作一律不要调用，
               继续用叙事承接即可，绝不能自己编造 action 类型。
            4. 每轮必须给出 3-4 个候选行动（options）：每个候选是一句简短的中文建议（不超过 12 个汉字），
               引导玩家下一步可以做什么；options 绝不能为空数组。
            5. 不要替玩家做决定，不要推进剧情，不要复述规则数值。根据世界设定、玩家行动和当前场景决定是否发生遭遇。玩家行动改变世界状态或开局需要确定地点时，用 updates 声明确认值。location 写具体地点，region 写所属区域的简短文本。时间变化要声明，无变化时 updates 输出 {}。visitedLocations 可用 text 声明一个去过的地点，location 会自动累积到访问记录。
            只输出响应 JSON。
            """;

    /** 开局额外提示：引导玩家做出第一个选择，其余规则与普通回合一致。 */
    private static final String OPENING_HINT = """
            这是游戏开局的第一段叙事。请写出开场氛围并自然带出玩家的初始状态，最后引导玩家决定第一步行动。
            """;

    private final NarrativeProvider provider;

    /**
     * 创建叙事服务并注入模型或本地叙事提供器。
     * @param provider 负责生成叙事结构的提供器
     */
    public DefaultNarrationService(NarrativeProvider provider) {
        this.provider = provider;
    }

    /**
     * 调用叙事提供器并校验完整结果；调用失败或输出非法时向上抛出异常，交由上层提示用户。
     * @param request 包含玩家输入、语言及服务端确认快照的请求
     * @param engineActions 当前引擎可执行的动作清单
     */
    @Override
    public NarrativeResponse narrate(NarrativeRequest request, List<SupportedAction> engineActions) {
        if (request == null) throw new IllegalArgumentException("Narrative request is required");
        var prompt = new NarrativePrompt(systemInstruction(engineActions, false), request.playerInput(),
                request.snapshot(), request.locale(), request.history());
        NarrativeResponse response = provider.respond(prompt);
        validate(response);
        return response;
    }

    /** 生成开局首段叙事。 */
    /** @param request 包含已确认快照的开局叙事请求 */
    /** @param engineActions 当前引擎可执行的动作清单 */
    @Override
    public NarrativeResponse opening(NarrativeRequest request, List<SupportedAction> engineActions) {
        if (request == null) throw new IllegalArgumentException("Narrative request is required");
        var prompt = new NarrativePrompt(systemInstruction(engineActions, true),
                "游戏开始，请生成开场叙事。", request.snapshot(), request.locale());
        NarrativeResponse response = provider.respond(prompt);
        validate(response);
        return response;
    }

    /** 只描写权威快照里的本轮结果，拒绝在描写阶段再次提交变更。
     * @param request 包含结算后的权威快照、玩家输入和近期对话
     */
    @Override
    public NarrativeResponse describeConfirmedTurn(NarrativeRequest request) {
        String instruction = """
                你是文字冒险游戏的 GM。根据 confirmedSnapshot 中已结算的状态和 confirmedEvents 描写本轮已经发生的结果。
                用自然的短段落承接玩家行动，只描述已确认的效果，不新增事实、数值、行动或世界变更。
                之后给出 3 至 4 个中文候选行动，每项最多 12 个汉字。只返回 JSON，action 必须为 null，updates 必须为 {}。
                """;
        NarrativeResponse response = provider.respond(new NarrativePrompt(instruction, request.playerInput(),
                request.snapshot(), request.locale(), request.history()));
        validate(response);
        if (response.action() != null || !response.updates().isEmpty()) {
            throw NarrativeProviderException.invalidOutput("结算叙事只能描写已确认结果", null);
        }
        return response;
    }

    /** 拼接引擎无关规则与当前引擎动作清单，构成发送给模型的系统提示。 */
    /** @param engineActions 引擎动作清单 @param opening 是否为开局 */
    private static String systemInstruction(List<SupportedAction> engineActions, boolean opening) {
        var builder = new StringBuilder(SYSTEM);
        if (opening) builder.append('\n').append(OPENING_HINT);
        builder.append(renderActions(engineActions));
        return builder.toString();
    }

    /** 将引擎动作清单渲染为提示词里的「可用行动」清单；纯叙事世界为空清单。 */
    /** @param engineActions 引擎动作清单 */
    private static String renderActions(List<SupportedAction> engineActions) {
        if (engineActions == null || engineActions.isEmpty()) {
            return "\n## 可用行动\n（当前为纯叙事世界，无引擎动作；action 一律返回 null。）\n";
        }
        StringBuilder builder = new StringBuilder("\n## 可用行动\n");
        for (SupportedAction action : engineActions) {
            builder.append("- ").append(action.type()).append("：").append(action.description());
            if (action.parameters().isEmpty()) {
                builder.append(" 参数：无");
            } else {
                builder.append(" 参数：");
                builder.append(action.parameters().stream()
                        .map(parameter -> parameter.name() + "(" + parameter.valueType()
                                + (parameter.required() ? "，必填" : "，可选") + "，" + parameter.description() + ")")
                        .collect(Collectors.joining("、")));
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * 检查模型响应的结构和协议字段边界。
     * @param response 待验证的模型叙事响应
     */
    private static void validate(NarrativeResponse response) {
        if (response == null) throw NarrativeProviderException.invalidOutput("叙事响应不能为空", null);
        if (response.narration().isBlank() || response.narration().length() > 6000) throw NarrativeProviderException.invalidOutput("叙述文本长度无效", null);
        if (response.options().isEmpty()) throw NarrativeProviderException.invalidOutput("叙事响应缺少候选行动", null);
        if (response.options().size() > 4 || response.options().stream().anyMatch(option -> option == null || option.isBlank() || option.length() > 12)) {
            throw NarrativeProviderException.invalidOutput("候选行动字段无效", null);
        }
        response.validateUpdates();
        if (response.action() != null) validateActionStructure(response.action());
    }

    /** 检查意图的协议格式，具体动作校验由所选引擎结算。
     * @param intent 待检查的行动意图
     */
    private static void validateActionStructure(ActionIntent intent) {
        if (!identifier(intent.type(), 64)) throw NarrativeProviderException.invalidOutput("行动意图类型无效", null);
        if (intent.target().length() > 256 || intent.arguments().size() > 32) throw NarrativeProviderException.invalidOutput("行动意图无效", null);
        intent.arguments().forEach(argument -> {
            if (!identifier(argument.name(), 64)) throw NarrativeProviderException.invalidOutput("行动参数名无效", null);
            if (argument.value() instanceof InitializationValue.Text text
                    && text.value().length() > 1000) throw NarrativeProviderException.invalidOutput("行动文本参数过长", null);
        });
    }

    /**
     * 判断字段名是否为受限协议标识符。
     * @param value 待检查的标识符文本
     * @param maxLength 标识符允许的最大长度
     */
    private static boolean identifier(String value, int maxLength) {
        return value != null && value.length() <= maxLength && value.matches("[a-zA-Z][a-zA-Z0-9_]*");
    }
}
