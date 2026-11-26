package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.NarrativePrompt;
import com.gameneko.aigame.narrative.NarrativeRequest;
import com.gameneko.aigame.narrative.NarrativeProviderException;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 使用世界书与所选引擎 schema 驱动 AI 多轮初始化，并在服务端校验必填状态。 */
@Service
public final class InitializationNarrationService {
    private static final String PURE_NARRATIVE_PROMPT = """
            你是文字冒险的 GM，正在与玩家进行自然的多轮开局访谈，而不是开始正式剧情。
            结合世界背景、已收集信息和缺失必填字段，逐步向玩家提问；玩家一轮可能同时确认多个字段。
            缺失必填字段清单由服务端根据 schema 计算，请继续收集这些字段，不要声称尚未满足的开局条件已经完成。
            字段清单是唯一可声明的字段集合；枚举值使用清单中的稳定 id。根据玩家表达把本轮可确定的值放入 updates。
            可使用 updates 回应玩家明确提出的字段修改。字段校验错误会在后续上下文中说明，请重新澄清相关内容。
            初始化阶段 action 固定为 null。只输出 narration、action、options、updates 四字段 JSON；无字段更新时 updates 为 {}。
            """;
    private static final String COMMON_PROTOCOL = """
协议边界：只使用 schema 里声明的字段及其类型、范围和枚举值。narration 最多 60 个汉字，options 最多 6 项且每项不超过 40 个汉字，updates 最多 16 项。问题和建议通过 narration/options 表达，不输出额外问题结构。
narration 自然回应玩家。需要继续收集资料时，每轮只问一个需要玩家回答的问题，每次只询问一个字段。玩家主动提供多个字段时，把本轮已确认的值都写入 updates。
options 中每个建议都应能直接作为 narration 中唯一问题的回答。没有提问或没有合适的建议时，返回空数组。
必填字段齐备后，玩家可以进入下一步，也可以继续访谈。继续回应玩家的问题；根据玩家的明确意图补充可选字段或修改已填字段。玩家只提问时，用 narration 回答并将 updates 留空。
            """;

    private final NarrativeProvider provider;
    private final GameInitializationSchemaService schemas;
    private final ContentCatalog content;

    /** 创建初始化叙事服务。
     * @param provider 生成访谈话术和确定字段值的叙事提供器
     * @param schemas 提供共享字段、引擎 schema 与引擎访谈模板的服务
     * @param content 读取世界书背景与世界字段约束的目录
     */
    @Autowired
    public InitializationNarrationService(NarrativeProvider provider,
                                          GameInitializationSchemaService schemas,
                                          ContentCatalog content) {
        this.provider = provider;
        this.schemas = schemas;
        this.content = content;
    }

    /** 创建尚未持久化的初始化会话。
     * @param id 会话标识
     * @param selection 世界书与可选引擎选择
     * @return 新建的内存会话
     */
    public Session session(String id, GameSelection selection) {
        var profile = schemas.profile(selection);
        return new Session(id, selection, profile, content.world(selection.worldId()));
    }

    /** 通过所选初始化模板生成 AI 访谈开场回合。
     * @param session 待打开的初始化会话
     * @return AI 提问及已确认字段组成的首个回合
     */
    public Turn open(Session session) {
        return respond(session, "");
    }

    /** 接受一轮自由输入，采纳合法字段值并反馈最新缺失项。
     * @param session 当前初始化会话
     * @param input 玩家自然语言回答
     * @return AI 下一轮访谈回合；必填字段齐备时标记为可继续
     */
    public Turn answer(Session session, String input) {
        if (session == null) throw new IllegalArgumentException("Initialization session is required");
        if (input == null || input.isBlank()) throw GameRequestException.invalid("Initialization answer is required");
        return respond(session, input.trim());
    }

    /** 判断必填字段是否已经满足，可以进入下一步。
     * @param session 当前初始化会话
     * @return 会话存在且全部必填字段有效时为 true
     */
    public boolean ready(Session session) {
        return session != null && session.done;
    }

    /** 将会话中确认的世界字段转换为世界创建请求。
     * @param session 已满足必填字段的初始化会话
     * @return 只包含世界书所属世界字段的请求
     */
    public InitializeWorldRequest request(Session session) {
        if (!ready(session)) throw GameRequestException.invalid("Initialization confirmation is required");
        return new InitializeWorldRequest(session.selection.worldId(), text(session.worldValues, "name"),
                text(session.worldValues, "description"), text(session.worldValues, "location"),
                text(session.worldValues, "region"), text(session.worldValues, "time"),
                text(session.worldValues, "mainQuest"), text(session.worldValues, "originScene"));
    }

    /** 返回满足世界书与引擎 schema 的类型化答案。
     * @param session 已满足必填字段的初始化会话
     * @return 世界书和可选引擎共享的类型化初始化字段
     */
    public Map<String, InitializationValue> answers(Session session) {
        if (!ready(session)) throw GameRequestException.invalid("Initialization confirmation is required");
        return Map.copyOf(allAnswers(session));
    }

    /** 调用模型、采纳字段声明并用 schema 缺失状态组成下一轮。
     * @param session 当前初始化会话
     * @param input 当前玩家输入，首次调用为空字符串
     * @return 完成字段校验后的 AI 回合
     */
    private Turn respond(Session session, String input) {
        NarrativeResponse response = ask(session, input);
        session.validationErrors = applyUpdates(session, response.updates());
        session.done = missingRequired(session).isEmpty();
        String narration = response.narration().isBlank()
                ? session.done ? "开局必填资料已齐备。你可以继续补充，也可以进入下一步。" : "我还需要了解一些开局信息。"
                : response.narration();
        return new Turn(narration, response.options(), collected(session), session.done);
    }

    /** 根据世界书、引擎访谈模板、已收集字段和上轮对话请求模型。
     * @param session 当前初始化会话
     * @param input 当前玩家输入
     * @return 四字段协议响应
     */
    private NarrativeResponse ask(Session session, String input) {
        List<NarrativeRequest.ConversationMessage> history = List.copyOf(session.history);
        NarrativePrompt prompt = new NarrativePrompt(system(session), input, emptySnapshot(), "zh-CN", history);
        NarrativeResponse response = provider.respond(prompt);
        validate(response);
        if (!input.isBlank()) session.history.add(new NarrativeRequest.ConversationMessage("player", input));
        session.history.add(new NarrativeRequest.ConversationMessage("gm", response.narration()));
        return response;
    }

    /** 组合通用访谈规则、引擎专属提示和服务端 schema 事实。
     * @param session 当前初始化会话
     * @return 发给 AI 的完整系统提示
     */
    private String system(Session session) {
        var narrative = content.worldBook(session.selection.worldId()).narrative();
        String template = session.enginePrompt.isBlank() ? PURE_NARRATIVE_PROMPT : session.enginePrompt;
        var builder = new StringBuilder(template).append(COMMON_PROTOCOL)
                .append("\n## 世界书背景\n世界名：").append(session.world.nameHint())
                .append("\n世界描述：").append(narrative.descriptionHint())
                .append("\n故事基调：").append(narrative.tone())
                .append("\n世界规则：").append(narrative.worldTropes())
                .append("\n主要势力：").append(narrative.factions())
                .append("\n出身起点：").append(narrative.originContexts())
                .append("\n## 已收集字段\n").append(allAnswers(session))
                .append("\n## 缺少的必填字段\n");
        List<InitializationField> missing = session.schema.fields().stream()
                .filter(field -> field.required() && !allAnswers(session).containsKey(field.id())).toList();
        if (missing.isEmpty()) builder.append("无\n");
        else missing.forEach(field -> builder.append(field.id()).append("（").append(field.label()).append("）\n"));
        builder.append("## 当前可采集字段 schema\n");
        for (InitializationField field : session.schema.fields()) {
            builder.append(field.id()).append("（").append(field.label()).append("，类型=").append(field.type())
                    .append(field.required() ? "，必填" : "，可选");
            if (field.minimum() != null || field.maximum() != null) {
                builder.append("，范围=").append(field.minimum() == null ? "无下限" : field.minimum())
                        .append(".. ").append(field.maximum() == null ? "无上限" : field.maximum());
            }
            builder.append("）");
            if (!field.options().isEmpty()) builder.append("，稳定选项 id=").append(field.options());
            if (!field.prompt().isBlank()) builder.append("，提示=").append(field.prompt());
            builder.append('\n');
        }
        builder.append("## 上轮字段校验结果\n");
        if (session.validationErrors.isEmpty()) builder.append("无\n");
        else session.validationErrors.forEach((field, error) -> builder.append(field).append("：").append(error).append('\n'));
        if (missing.isEmpty() && !session.validationErrors.isEmpty()) {
            builder.append("必填字段已齐备，未通过校验的更新未采纳。若玩家仍想修改相关字段，请协助澄清。\n");
        }
        return builder.toString();
    }

    /** 校验声明字段并分类写入世界书答案或引擎答案，错误按字段回传给 AI。
     * @param session 当前初始化会话
     * @param updates 模型输出的类型化字段声明
     * @return 字段标识到拒绝原因的映射
     */
    private Map<String, String> applyUpdates(Session session, Map<String, InitializationValue> updates) {
        var errors = new LinkedHashMap<String, String>();
        Set<String> worldFields = session.worldSchema.fields().stream().map(InitializationField::id).collect(Collectors.toSet());
        for (var entry : updates.entrySet()) {
            String id = entry.getKey();
            InitializationField field = fieldById(session.schema, id);
            if (field == null) {
                errors.put(id, "field_not_supported");
                continue;
            }
            var probe = new LinkedHashMap<>(allAnswers(session));
            probe.put(id, entry.getValue());
            String error = session.schema.validate(probe).errors().get(id);
            if (error != null) {
                errors.put(id, error);
                continue;
            }
            if (worldFields.contains(id)) session.worldValues.put(id, entry.getValue());
            else session.values.put(id, entry.getValue());
        }
        return Map.copyOf(errors);
    }

    /** 组合当前两个字段归属下已确认的所有答案。
     * @param session 当前初始化会话
     * @return 世界书和可选引擎共享的当前答案
     */
    private Map<String, InitializationValue> allAnswers(Session session) {
        var values = new LinkedHashMap<>(session.worldValues);
        values.putAll(session.values);
        return values;
    }

    /** 按当前 schema 顺序返回仍缺失的必填字段 id。
     * @param session 当前初始化会话
     * @return 尚未确认的必填字段标识
     */
    private List<String> missingRequired(Session session) {
        Set<String> supplied = allAnswers(session).keySet();
        return session.schema.fields().stream().filter(InitializationField::required)
                .map(InitializationField::id).filter(id -> !supplied.contains(id)).toList();
    }

    /** 在组合 schema 中查找字段定义。
     * @param schema 当前组合 schema
     * @param fieldId 待查字段的稳定标识
     * @return 字段定义，不存在时为空
     */
    private static InitializationField fieldById(InitializationSchema schema, String fieldId) {
        return schema.fields().stream().filter(field -> field.id().equals(fieldId)).findFirst().orElse(null);
    }

    /** 汇总类型化答案为前端可展示的普通 JSON 值。
     * @param session 当前初始化会话
     * @return 字段值映射
     */
    private static Map<String, Object> collected(Session session) {
        var result = new LinkedHashMap<String, Object>();
        session.worldValues.forEach((key, value) -> result.put(key, primitive(value)));
        session.values.forEach((key, value) -> result.put(key, primitive(value)));
        return Map.copyOf(result);
    }

    /** 将有限初始化值转换为普通 JSON 值。
     * @param value 已通过 schema 校验的值
     * @return 字符串、数字或布尔值
     */
    private static Object primitive(InitializationValue value) {
        return switch (value) {
            case InitializationValue.Text text -> text.value();
            case InitializationValue.IntegerNumber number -> number.value();
            case InitializationValue.DecimalNumber number -> number.value();
            case InitializationValue.Flag flag -> flag.value();
        };
    }

    /** 提取世界字段的文本值，缺失时返回 null。
     * @param values 世界书字段映射
     * @param field 世界书拥有的世界状态字段标识
     * @return 世界字段文本，可空
     */
    private static String text(Map<String, InitializationValue> values, String field) {
        return values.get(field) instanceof InitializationValue.Text text ? text.value() : null;
    }

    /** 校验初始化模型响应的 JSON 字段、长度和声明边界。
     * @param response 待校验的模型响应
     */
    private static void validate(NarrativeResponse response) {
        if (response == null || response.action() != null || response.narration().length() > 6000 || response.options().size() > 6
                || response.options().stream().anyMatch(option -> option == null || option.isBlank() || option.length() > 40)) {
            throw NarrativeProviderException.invalidOutput("Invalid initialization narrative response", null);
        }
        response.validateUpdates();
    }

    /** 创建初始化阶段不含引擎状态的中立快照。 */
    private static NarrativeSnapshot emptySnapshot() {
        return new NarrativeSnapshot(new NarrativeSnapshot.WorldView(1, null, null, null,
                List.of(), List.of(), List.of()), List.of());
    }

    /** 初始化会话的可变内存状态。 */
    public static final class Session {
        private final String id;
        private final GameSelection selection;
        private final InitializationSchema schema;
        private final InitializationSchema worldSchema;
        private final String enginePrompt;
        private final WorldDefinition world;
        private final Map<String, InitializationValue> values = new LinkedHashMap<>();
        private final Map<String, InitializationValue> worldValues = new LinkedHashMap<>();
        private final List<NarrativeRequest.ConversationMessage> history = new ArrayList<>();
        private Map<String, String> validationErrors = Map.of();
        private boolean done;

        /** 创建尚未确认字段值的初始化会话。
         * @param id 会话标识
         * @param selection 世界书与可选引擎选择
         * @param profile 世界书和引擎提供的初始化配置
         * @param world 当前世界定义与背景约束
         */
        private Session(String id, GameSelection selection,
                        GameInitializationSchemaService.InitializationProfile profile, WorldDefinition world) {
            this.id = id;
            this.selection = selection;
            this.schema = profile.schema();
            this.worldSchema = profile.worldSchema();
            this.enginePrompt = profile.enginePrompt();
            this.world = world;
        }

        /** 返回初始化会话标识。 */
        public String id() { return id; }

        /** 返回玩家选择的世界书和可选引擎。 */
        public GameSelection selection() { return selection; }
    }

    /** 初始化访谈回合；问题和建议由 AI 的 narration 与 options 表达。
     * @param narration AI 访谈内容，可包含下一问题
     * @param options 可以直接回答本轮唯一问题的建议，无合适建议时为空
     * @param collected 已采纳并通过 schema 校验的字段值
     * @param done 是否满足进入下一步的必填字段
     */
    public record Turn(String narration, List<String> options, Map<String, Object> collected, boolean done) {
        /** 复制选项和已采集字段，保证回合不可变。 */
        public Turn {
            narration = narration == null ? "" : narration;
            options = options == null ? List.of() : List.copyOf(options);
            collected = collected == null ? Map.of() : Map.copyOf(collected);
        }
    }
}
