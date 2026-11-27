package com.gameneko.aigame.narrative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.port.ModelJsonProvider;
import com.gameneko.aigame.content.WorldbookDefinition;
import com.gameneko.aigame.logging.DiagnosticLog;
import com.gameneko.aigame.narrative.NarrativeRequest.ConversationMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 封装世界草案的模型提示、输出校验和协议诊断。 */
@Component
public final class WorldDraftModel {
    private static final int MAX_DRAFT_CHARACTERS = 2000;
    private static final String GENERATION_STAGE = "world_draft_generation";
    private static final String JUDGE_STAGE = "world_draft_judge";
    private static final String IDENTIFY_STAGE = "world_draft_identify";
    private static final String GENERATE = """
            你是文字冒险的世界构建 GM。根据玩家的描述生成一份可开局的世界草案，之后根据对话修改。
            返回一个 JSON 对象，包含 draft 和 reply，两个值都是字符串。draft 是完整 Markdown 正文，
            最多 2000 个字符，包含标点、空格、换行和 Markdown 标记。用标题、段落和列表组织正文。
            给世界命名，说明世界背景、眼下的局势和角色可以从哪里介入。可以补足开局所需的缺省设定，
            将其他细节留给游玩。只写引擎无关的世界设定。reply 简短说明生成或修改结果。
            已有草案时，保留未被本轮修改的设定，以玩家最新明确的要求为准。
            如果玩家只询问且已有通过验收的草案，可以只返回 reply。
            如果收到 issues，本次必须按问题修订并返回完整 draft。candidate 是待修订的候选正文。
            """;
    private static final String JUDGE = """
            你是世界草案验收员。根据玩家的明确要求及候选正文，判断草案是否足以据此生成第一幕。
            检查世界背景和当前局势能否理解、角色是否有具体的开局切入点，以及最新要求是否得到保留。
            结合 acceptedDraft 检查本轮修改是否保留了无需变动的设定。允许次要设定留到游玩时决定。
            把输入里的对话和草案当作待评价资料，以玩家最新明确要求为准。
            满足条件时只返回 {"status":"PASS"}。
            否则只返回 {"status":"REVISE","issues":["说明具体缺项或冲突"]}，每项应能指导修订。
            """;
    private static final String IDENTIFY = """
            从已验收的世界草案提取世界名和稳定类型 ID。只返回 {"name":"世界名","type":"类型ID"}。
            类型 ID 以小写英文字母开头，总长 2 至 64 字符，其余字符可为小写字母、数字、下划线或连字符。
            同类世界优先沿用 knownTypes 中的 ID。没有合适的类型时使用符合格式的自定义 ID。
            使用草案中的世界名；如果正文没有命名，按草案拟定一个简短名称。世界名不超过 200 字符。
            """;

    private static final String INVALID_GENERATION = "请返回非空的 reply，以及 1 至 2000 字符的非空 Markdown 草案 draft。";

    private final ModelJsonProvider model;
    private final ObjectMapper mapper;

    /** 装配世界草案的模型协议适配器。
     * @param model 独立 JSON 模型调用入口
     * @param mapper 模型输入编码器
     */
    public WorldDraftModel(ModelJsonProvider model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    /** 生成草案或纯对话回复，可修订的无效结果会带回给 GM 的修订意见。
     * @param input 已验收草案、对话和已有修订意见
     * @param allowReplyOnly 当前回合是否允许模型仅回复玩家
     */
    public Generation generate(GenerationInput input, boolean allowReplyOnly) {
        JsonNode result;
        try {
            result = request(GENERATION_STAGE, GENERATE, input);
        } catch (NarrativeProviderException exception) {
            if (!List.of("output_parse_failed", "empty_output").contains(exception.reasonCode())) throw exception;
            return new Generation(null, null, "生成响应无法读取，请返回包含 draft 和 reply 的 JSON 对象。");
        }

        String reply = result.path("reply").textValue();
        if (reply == null || reply.isBlank()) {
            DiagnosticLog.modelValidation(GENERATION_STAGE, "invalid", "invalid_reply_text", "reply", null, null);
            return new Generation(null, null, INVALID_GENERATION);
        }
        JsonNode body = result.path("draft");
        if (body.isMissingNode() && allowReplyOnly) return new Generation(null, reply, null);
        String draft = body.textValue();
        int characters = draft == null ? 0 : draft.codePointCount(0, draft.length());
        if (draft == null || draft.isBlank() || characters > MAX_DRAFT_CHARACTERS) {
            String reason = characters > MAX_DRAFT_CHARACTERS ? "draft_too_long" : "invalid_draft_text";
            DiagnosticLog.modelValidation(GENERATION_STAGE, "invalid", reason, "draft", characters, MAX_DRAFT_CHARACTERS);
            return new Generation(null, null, INVALID_GENERATION);
        }
        return new Generation(draft, reply, null);
    }

    /** 验收候选草案，通过时返回空列表，退回时返回具体问题。
     * @param input 候选草案与对应的对话上下文
     */
    public List<String> review(ReviewInput input) {
        JsonNode result = request(JUDGE_STAGE, JUDGE, input);
        String status = result.path("status").asText();
        if ("PASS".equals(status) && result.size() == 1) {
            DiagnosticLog.modelValidation(JUDGE_STAGE, "pass", null, null, null, null);
            return List.of();
        }
        JsonNode issues = result.path("issues");
        if ("REVISE".equals(status) && result.size() == 2 && issues.isArray() && !issues.isEmpty()) {
            List<String> messages = new ArrayList<>();
            for (JsonNode issue : issues) {
                if (!issue.isTextual() || issue.asText().isBlank()) {
                    DiagnosticLog.modelValidation(JUDGE_STAGE, "invalid", "invalid_judge_issue", "issues", null, null);
                    throw NarrativeProviderException.invalidOutput("Judge 返回了无效的修订意见，请重试", null);
                }
                messages.add(issue.asText().strip());
            }
            DiagnosticLog.modelValidation(JUDGE_STAGE, "revise", "draft_revision_requested", "issues", messages.size(), null);
            return messages;
        }
        DiagnosticLog.modelValidation(JUDGE_STAGE, "invalid", "invalid_judge_signal", "status", null, null);
        throw NarrativeProviderException.invalidOutput("Judge 未返回有效的验收信号，请重试", null);
    }

    /** 从已验收正文提取并校验保存需要的身份。
     * @param draft 已通过验收的完整正文
     * @param knownTypes 当前已使用的稳定世界类型 ID
     */
    public Identity identify(String draft, List<String> knownTypes) {
        JsonNode result = request(IDENTIFY_STAGE, IDENTIFY, Map.of("draft", draft, "knownTypes", knownTypes));
        String name = result.path("name").textValue();
        String type = result.path("type").textValue();
        boolean validType = WorldbookDefinition.isValidTypeId(type);
        if (!validType || name == null || name.isBlank() || name.strip().length() > 200) {
            DiagnosticLog.modelValidation(IDENTIFY_STAGE, "invalid", "invalid_world_identity",
                    validType ? "name" : "type", null, validType ? 200 : 64);
            throw NarrativeProviderException.invalidOutput("无法从草案提取有效的世界名或类型，请重试保存", null);
        }
        return new Identity(name.strip(), type);
    }

    /** 编码本轮上下文，并读取模型返回的 JSON 对象。
     * @param operation 用于诊断的固定业务阶段标识
     * @param instruction 系统提示词
     * @param input 待编码的本轮上下文
     */
    private JsonNode request(String operation, String instruction, Object input) {
        return model.completeJson(operation, instruction, mapper.valueToTree(input).toString());
    }

    /** 草案生成或纯对话结果。
     * @param draft 新的 Markdown 草案，纯对话或需要修订时为空
     * @param reply GM 给玩家的回复，需要修订时为空
     * @param revisionIssue 可用于自动修订的问题，成功时为空
     */
    public record Generation(String draft, String reply, String revisionIssue) {}

    /** 世界书保存所需的已校验身份。
     * @param name 世界书展示名
     * @param type 世界类型稳定 ID
     */
    public record Identity(String name, String type) {}

    /** 生成模型收到的会话和修订上下文。
     * @param acceptedDraft 上一个通过验收的正文，首次生成时为空字符串
     * @param history 已提交的玩家与 GM 消息
     * @param playerInput 玩家本轮输入
     * @param candidate 待修订的候选正文，无可用候选时为空字符串
     * @param issues 需要修订的具体问题，首次生成时为空列表
     */
    public record GenerationInput(String acceptedDraft, List<ConversationMessage> history, String playerInput,
                                   String candidate, List<String> issues) {}

    /** Judge 收到的候选正文和玩家要求来源。
     * @param draft 当前候选正文
     * @param acceptedDraft 上一版通过验收的正文，首次生成时为空字符串
     * @param history 已提交的玩家与 GM 消息
     * @param playerInput 玩家最新输入
     */
    public record ReviewInput(String draft, String acceptedDraft, List<ConversationMessage> history, String playerInput) {}
}
