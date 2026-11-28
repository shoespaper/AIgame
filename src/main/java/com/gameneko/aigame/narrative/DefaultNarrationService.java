package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProvider;
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
            1. narration 分段写清行动结果、人物反应和本轮的新进展。通常建议写 300 至 600 个汉字，重要对话或场景转折可放宽至 1000 个汉字。
               把篇幅用在影响后续行动的线索、人物动机和场景变化上，避免重复上一轮的气氛、动作和对白。
            2. 大多数回合是纯叙事：action 返回 null。玩家尝试改变规则状态，或当前情节需要先登记引擎状态时，从「可用行动」中提交一次 action。
               提案阶段只描写尝试或准备。只有服务端确认后，才能描述资源增减、获得物品、登记或接取成功等规则结果。action 为 null 时也遵守此限制。
            3. action.type 只能取「可用行动」清单中的值，参数名与类型必须和清单一致；清单里没有的动作一律不要调用，
               继续用叙事承接即可，绝不能自己编造 action 类型。
            4. 每轮给出 3-4 个纯文本候选行动（options），每项最多 24 个汉字。选项从本轮结束时真正可选的下一步出发，避免让玩家重选刚完成的动作。正文和候选行动都使用 confirmedSnapshot 中的当前状态和数量。
               玩家自述与历史只提供叙事上下文。候选行动可引导询问或准备，不能把仍待引擎确认的事项说成已经可执行。引擎会另外提供直接执行的按钮。
            5. 在玩家已经表达的意图范围内推进剧情。把同一意图下连续的常规步骤在本轮完成，交代行动结果，并让场景发展到有实际意义的下一步。
               玩家主动询问时，让人物按自己掌握的信息和立场作答，可以一次给出几条相关线索。遇到新风险、重要承诺、交出关键物品或改变目标等需要玩家决定的节点时停下。
               根据世界设定、玩家行动和当前场景决定是否发生遭遇。不要替玩家作新的决定，也不要复述规则数值。
            6. 玩家行动改变世界状态或开局需要确定地点时，用 updates 声明确认值。location 写具体地点，region 写所属区域的简短文本。时间变化要声明，无变化时 updates 输出 {}。visitedLocations 可用 text 声明一个去过的地点，location 会自动累积到访问记录。
            只输出响应 JSON。
            """;

    /** 开局额外提示：引导玩家做出第一个选择，其余规则与普通回合一致。 */
    private static final String OPENING_HINT = """
            这是游戏开局的第一段叙事。若 confirmedSnapshot.worldbook.descriptionHint 中有开局切入点，
            请结合 confirmedSnapshot.character 中已确认的角色资料与世界状态从那里展开。角色背景用于叙事，请保留玩家填写的身份资料。最后引导玩家决定第一步行动。
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
        NarrativePrompt prompt = new NarrativePrompt(systemInstruction(engineActions, false), request.playerInput(),
                request.snapshot(), request.locale(), request.history(), true);
        NarrativeResponse response = provider.respond(prompt);
        validate(response);
        return response;
    }

    /** 在回合预算内生成一次完整答复，纠正说明不改变玩家原文。
     * @param request 玩家本轮输入与确认快照
     * @param engineActions 引擎支持的动作
     * @param correction 上次失败的安全纠正指令，可为空
     */
    @Override
    public NarrativeResponse proposeOnce(NarrativeRequest request, List<SupportedAction> engineActions, String correction) {
        String instruction = systemInstruction(engineActions, false);
        if (correction != null) instruction += "\n\n## 上一次尝试与修正要求\n" + correction;
        NarrativeResponse response = provider.respond(new NarrativePrompt(instruction, request.playerInput(),
                request.snapshot(), request.locale(), request.history(), true, true));
        validate(response);
        return response;
    }

    /** 生成开局首段叙事。 */
    /** @param request 包含已确认快照的开局叙事请求 */
    /** @param engineActions 当前引擎可执行的动作清单 */
    @Override
    public NarrativeResponse opening(NarrativeRequest request, List<SupportedAction> engineActions) {
        if (request == null) throw new IllegalArgumentException("Narrative request is required");
        NarrativePrompt prompt = new NarrativePrompt(systemInstruction(engineActions, true),
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
        return describe(request, false);
    }

    /** 在统一预算中尝试描写一次。
     * @param request 结算后的权威快照及玩家上下文
     */
    @Override
    public NarrativeResponse describeConfirmedTurnOnce(NarrativeRequest request) {
        return describe(request, true);
    }

    /** 描写已确认结果并验证模型没有再次提出变更。
     * @param request 已结算的回合快照
     * @param singleAttempt 是否禁止提供器内部重试
     */
    private NarrativeResponse describe(NarrativeRequest request, boolean singleAttempt) {
        String instruction = """
                你是文字冒险游戏的 GM。根据 confirmedSnapshot 中已结算的状态和 confirmedEvents 描写本轮已经发生的结果。
                用自然的段落完整承接玩家已经决定的行动，先交代已确认的结果，再写与这些结果相符的人物反应和处境变化。
                正文通常建议写 300 至 600 个汉字，重要对话或场景转折可放宽至 1000 个汉字。承接历史中的线索和人物关系，避免复述或填充。
                规则效果以本轮确认结果为限。可以描写承接结果的对话和反应，不得添加新的规则结果、数值或世界状态变更，也不要替玩家作出新决定。
                正文与选项里的状态和数量以快照为准，不能引用与当前状态冲突的自述或历史。引擎会另外提供直接执行的按钮。
                之后给出 3 至 4 个针对当前新处境的中文文本建议，每项最多 12 个汉字。只返回 JSON，action 必须为 null，updates 必须为 {}。
                """;
        NarrativeResponse response = provider.respond(new NarrativePrompt(instruction, request.playerInput(),
                request.snapshot(), request.locale(), request.history(), singleAttempt, singleAttempt));
        validate(response);
        if (response.action() != null || !response.updates().isEmpty()) {
            throw NarrativeProviderException.invalidOutput("结算叙事只能描写已确认结果", null);
        }
        return response;
    }

    /** 拼接引擎无关规则与当前引擎动作清单，构成发送给模型的系统提示。 */
    /** @param engineActions 引擎动作清单 @param opening 是否为开局 */
    private static String systemInstruction(List<SupportedAction> engineActions, boolean opening) {
        StringBuilder builder = new StringBuilder(SYSTEM);
        if (opening) builder.append('\n').append(OPENING_HINT);
        builder.append(renderActions(engineActions));
        return builder.toString();
    }

    /** 将引擎动作清单渲染为提示词里的「可用行动」清单；纯叙事世界为空清单。 */
    /** @param engineActions 引擎动作清单 */
    private static String renderActions(List<SupportedAction> engineActions) {
        if (engineActions == null || engineActions.isEmpty()) {
            return "\n## 可用行动\n（当前没有可提交的引擎动作；action 一律返回 null。）\n";
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
        response.validateProtocol();
    }
}
