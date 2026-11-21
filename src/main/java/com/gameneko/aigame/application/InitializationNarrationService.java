package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.GameEngineCatalog;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.NarrativeInitializationQuestion;
import com.gameneko.aigame.narrative.NarrativeParseCandidate;
import com.gameneko.aigame.narrative.NarrativePrompt;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 驱动初始化问答，负责调用叙事模型、校验候选答案并维护字段游标。 */
@Service
public final class InitializationNarrationService {
    private static final String SUPPLEMENT_FIELD = "__world_supplement";
    private static final Set<String> WORLD_FIELDS = Set.of("name", "description", "location", "region", "weather", "season", "time", "mainQuest", "originScene");
    private static final String SYSTEM = """
            你是文字冒险游戏的主持人（GM），当前处于「开局确认」环节。
            先用简短引导语开始，然后一次只询问一个字段。枚举字段只能使用上下文给出的合法值；开放字段可以给出 2-3 个建议值。
            玩家输入是意图而不是事实，必须用 parseCandidates 返回结构化候选，最终以服务端 schema 校验为准。
            所有 mediaRequests、proposals、options 必须返回空数组。只输出响应 JSON。
            """;

    private final NarrativeProvider provider;
    private final GameInitializationSchemaService schemas;
    private final ContentCatalog content;
    private final GameEngineCatalog engines;

    /** 创建初始化叙事服务。
     * @param provider 生成初始化话术和结构化候选的叙事提供器
     * @param schemas 组合世界书与引擎字段的 schema 服务
     * @param content 读取世界书背景和世界字段约束的目录
     */
    @Autowired
    public InitializationNarrationService(NarrativeProvider provider,
                                          GameInitializationSchemaService schemas,
                                          ContentCatalog content,
                                          GameEngineCatalog engines) {
        this.provider = provider;
        this.schemas = schemas;
        this.content = content;
        this.engines = engines;
    }

    /** 创建不依赖引擎目录的初始化服务，供纯单元测试使用。
     * @param provider 叙事提供器
     * @param schemas 组合 schema 服务
     * @param content 世界书目录
     */
    public InitializationNarrationService(NarrativeProvider provider,
                                          GameInitializationSchemaService schemas,
                                          ContentCatalog content) {
        this(provider, schemas, content, null);
    }

    /** 创建尚未持久化的初始化会话。
     * @param id 会话标识
     * @param selection 世界书与可选引擎
     * @return 新建的内存会话
     */
    public Session session(String id, GameSelection selection) {
        return new Session(id, selection, schemas.schema(selection), content.world(selection.worldId()));
    }

    /** 打开会话并生成 GM 开场白和第一问。
     * @param session 待打开的初始化会话
     * @return 初始化回合
     */
    public Turn open(Session session) {
        return respond(session, "", true);
    }

    /** 接收玩家回答，解析候选并推进到下一字段或补充设定阶段。
     * @param session 当前初始化会话
     * @param input 玩家自然语言回答
     * @return 更新后的初始化回合
     */
    public Turn answer(Session session, String input) {
        if (session == null) throw new IllegalArgumentException("Initialization session is required");
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Initialization answer is required");
        String answer = input.trim();
        if (session.stage == Stage.READY) return session.turn;
        if (session.stage == Stage.SUPPLEMENT) return answerSupplement(session, answer);
        InitializationField current = nextField(session);
        NarrativeResponse response = ask(session, answer);
        List<NarrativeParseCandidate> accepted = applyCandidates(session, response.parseCandidates(), false);
        if (current != null && !session.values.containsKey(current.id())) applyDirectAnswer(session, current, answer);
        if (missingRequired(session).isEmpty()) {
            session.stage = Stage.SUPPLEMENT;
            String narration = response.narration().isBlank() ? "基本资料已经确认。还有什么想补充的吗？" : response.narration();
            session.turn = new Turn(narration + "\n还有什么想补充的吗？", new NarrativeInitializationQuestion(
                    SUPPLEMENT_FIELD, "还有什么想补充的吗？可以描述开场地点、天气、季节或主线方向；没有的话请输入“没有”。", List.of()), accepted, collected(session), false);
            return session.turn;
        }
        return nextQuestion(session, response, accepted, false);
    }

    /** 判断会话是否已完成补充并可 finalize。
     * @param session 当前初始化会话
     * @return 已完成全部初始化阶段时为 true
     */
    public boolean ready(Session session) { return session != null && session.stage == Stage.READY; }

    /** 将会话答案转换为一次性创建游戏所需的世界请求。
     * @param session 已完成的初始化会话
     * @return 含开放世界字段和玩家难度的初始化请求
     */
    public InitializeWorldRequest request(Session session) {
        if (!ready(session)) throw new IllegalArgumentException("Initialization confirmation is required");
        String difficulty = text(session.values.get("difficulty"), "adventure");
        return new InitializeWorldRequest(session.selection.worldId(), text(session.worldValues.get("name"), null),
                text(session.worldValues.get("description"), null), text(session.worldValues.get("location"), null),
                text(session.worldValues.get("region"), null), text(session.worldValues.get("weather"), null),
                text(session.worldValues.get("season"), null), text(session.worldValues.get("time"), null),
                text(session.worldValues.get("mainQuest"), null), text(session.worldValues.get("originScene"), null),
                text(session.values.get("characterName"), "旅人"), integer(session.values.get("age"), 16),
                text(session.values.get("gender"), "未定"), text(session.values.get("archetype"), "swordsman"), difficulty);
    }

    /** 返回已通过服务端候选校验的引擎初始化答案。
     * @param session 已完成的初始化会话
     * @return 只包含 schema 字段的类型化答案
     */
    public Map<String, InitializationValue> answers(Session session) {
        if (!ready(session)) throw new IllegalArgumentException("Initialization confirmation is required");
        return Map.copyOf(session.values);
    }

    /** 处理必填字段完成后的开放式世界设定补充。
     * @param session 当前初始化会话
     * @param answer 玩家补充或确认文本
     * @return 补充阶段后的回合
     */
    private Turn answerSupplement(Session session, String answer) {
        if (answer.matches("(?i)没有|没有了|无|不用|no|none|确认|确定|就这样")) {
            session.stage = Stage.READY;
            session.turn = new Turn("好的，开局资料已经确认。现在就开始你的故事。", null, List.of(), collected(session), true);
            return session.turn;
        }
        NarrativeResponse response = ask(session, answer);
        List<NarrativeParseCandidate> accepted = applyCandidates(session, response.parseCandidates(), true);
        if (accepted.isEmpty()) {
            session.worldValues.merge("originScene", new InitializationValue.Text(answer), (oldValue, newValue) -> new InitializationValue.Text(text(oldValue, "") + "\n" + text(newValue, "")));
        }
        session.stage = Stage.READY;
        session.turn = new Turn(response.narration().isBlank() ? "已记录你的补充。好的，开局资料已经确认。" : response.narration() + "\n好的，开局资料已经确认。", null, accepted, collected(session), true);
        return session.turn;
    }

    /** 调用模型并将其输出规整为当前字段问题。
     * @param session 当前初始化会话
     * @param input 当前玩家输入
     * @param opening 是否为开场回合
     * @return 规整后的初始化回合
     */
    private Turn respond(Session session, String input, boolean opening) {
        NarrativeResponse response = ask(session, input);
        return nextQuestion(session, response, List.of(), opening);
    }

    /** 从模型响应中选择合法字段并生成服务端权威选项。
     * @param session 当前初始化会话
     * @param response 模型响应
     * @param candidates 本轮已接受候选
     * @param opening 是否为首轮
     * @return 下一初始化回合
     */
    private Turn nextQuestion(Session session, NarrativeResponse response, List<NarrativeParseCandidate> candidates, boolean opening) {
        InitializationField field = nextField(session);
        if (field == null) {
            session.stage = Stage.SUPPLEMENT;
            session.turn = new Turn(response.narration().isBlank() ? "基本资料已经确认。还有什么想补充的吗？" : response.narration() + "\n还有什么想补充的吗？",
                    new NarrativeInitializationQuestion(SUPPLEMENT_FIELD, "还有什么想补充的吗？", List.of()), candidates, collected(session), false);
            return session.turn;
        }
        NarrativeInitializationQuestion question = questionFor(session, response, field);
        String narration = response.narration();
        if (narration.isBlank()) narration = opening ? "开始游戏前，需要确认一些基本信息。" : field.prompt();
        session.turn = new Turn(narration, question, candidates, collected(session), false);
        return session.turn;
    }

    /** 调用叙事提供器，失败时向上抛出异常交由上层提示用户。
     * @param session 当前初始化会话
     * @param input 当前玩家输入
     * @return 模型响应
     */
    private NarrativeResponse ask(Session session, String input) {
        NarrativeResponse response = provider.respond(new NarrativePrompt(system(session, input), input, emptySnapshot(), "zh-CN"));
        validate(response);
        return response;
    }

    /** 将模型候选逐个放入部分答案并执行字段级 schema 校验。
     * @param session 当前初始化会话
     * @param candidates 模型返回的候选
     * @param worldOnly 是否只接受开放世界字段
     * @return 实际接受的候选
     */
    private List<NarrativeParseCandidate> applyCandidates(Session session, List<NarrativeParseCandidate> candidates, boolean worldOnly) {
        List<NarrativeParseCandidate> accepted = new ArrayList<>();
        if (candidates == null) return accepted;
        for (NarrativeParseCandidate candidate : candidates.stream().limit(16).toList()) {
            if (worldOnly) {
                if (!WORLD_FIELDS.contains(candidate.fieldId()) || !validWorldValue(session, candidate)) continue;
                session.worldValues.put(candidate.fieldId(), candidate.value()); accepted.add(candidate); continue;
            }
            InitializationField field = session.schema.fields().stream().filter(item -> item.id().equals(candidate.fieldId())).findFirst().orElse(null);
            if (field == null || session.values.containsKey(field.id())) continue;
            InitializationValue value = normalizeValue(session, field, candidate.value());
            if (value == null) continue;
            var probe = new LinkedHashMap<>(session.values); probe.put(field.id(), value);
            if (session.schema.validate(probe).errors().containsKey(field.id())) continue;
            session.values.put(field.id(), value);
            accepted.add(new NarrativeParseCandidate(field.id(), value, candidate.confidence()));
        }
        return accepted;
    }

    /** 将选项点击或简单数值文本作为模型解析失败时的确定性兜底。
     * @param session 当前初始化会话
     * @param field 当前字段
     * @param answer 玩家文本
     */
    private void applyDirectAnswer(Session session, InitializationField field, String answer) {
        InitializationValue value = directValue(session, field, answer);
        if (value == null) return;
        var probe = new LinkedHashMap<>(session.values); probe.put(field.id(), value);
        if (!session.schema.validate(probe).errors().containsKey(field.id())) session.values.put(field.id(), value);
    }

    private InitializationValue directValue(Session session, InitializationField field, String answer) {
        if (field.type() == InitializationField.ValueType.TEXT) {
            String normalized = normalizeOption(session, field, answer);
            if (!field.options().isEmpty() && !field.options().contains(normalized)) return null;
            return new InitializationValue.Text(normalized);
        }
        try {
            return switch (field.type()) {
                case INTEGER -> new InitializationValue.IntegerNumber(Long.parseLong(answer));
                case DECIMAL -> new InitializationValue.DecimalNumber(Double.parseDouble(answer));
                case BOOLEAN -> new InitializationValue.Flag(Boolean.parseBoolean(answer));
                case TEXT -> new InitializationValue.Text(answer);
            };
        } catch (NumberFormatException exception) { return null; }
    }

    private InitializationValue normalizeValue(Session session, InitializationField field, InitializationValue value) {
        if (field.type() == InitializationField.ValueType.TEXT && value instanceof InitializationValue.Text text) {
            String normalized = normalizeOption(session, field, text.value());
            if (!field.options().isEmpty() && !field.options().contains(normalized)) return null;
            return new InitializationValue.Text(normalized);
        }
        return value;
    }

    private String normalizeOption(Session session, InitializationField field, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (field.options().contains(value)) return value;
        if ("difficulty".equals(field.id())) {
            for (var entry : session.world.difficulties().entrySet()) if (entry.getValue().label().equals(value)) return entry.getKey();
        }
        if ("archetype".equals(field.id())) {
            Map<String, String> labels = Map.of("swordsman", "剑士", "mage", "法师", "ranger", "游侠", "alchemist", "炼金术师");
            for (var entry : labels.entrySet()) if (entry.getValue().equals(value)) return entry.getKey();
        }
        return value;
    }

    private NarrativeInitializationQuestion questionFor(Session session, NarrativeResponse response, InitializationField field) {
        NarrativeInitializationQuestion modelQuestion = response.initializationQuestions().stream()
                .filter(question -> question.fieldId().equals(field.id())).findFirst().orElse(null);
        List<String> options;
        if (!field.options().isEmpty()) {
            options = field.options().stream().map(option -> optionLabel(session, field, option)).toList();
        } else {
            options = modelQuestion == null ? List.of() : modelQuestion.options().stream().filter(option -> option != null && !option.isBlank()).limit(3).toList();
        }
        String prompt = modelQuestion == null || modelQuestion.prompt().isBlank() ? field.prompt() : modelQuestion.prompt();
        return new NarrativeInitializationQuestion(field.id(), prompt, options);
    }

    private String optionLabel(Session session, InitializationField field, String option) {
        if ("difficulty".equals(field.id()) && session.world.difficulties().containsKey(option)) return session.world.difficulties().get(option).label();
        return Map.of("swordsman", "剑士", "mage", "法师", "ranger", "游侠", "alchemist", "炼金术师").getOrDefault(option, option);
    }

    private InitializationField nextField(Session session) {
        return session.schema.fields().stream().filter(field -> field.required() && !session.values.containsKey(field.id())).findFirst().orElse(null);
    }

    private List<InitializationField> missingRequired(Session session) {
        return session.schema.fields().stream().filter(field -> field.required() && !session.values.containsKey(field.id())).toList();
    }

    private boolean validWorldValue(Session session, NarrativeParseCandidate candidate) {
        if (!(candidate.value() instanceof InitializationValue.Text text)) return false;
        return !"region".equals(candidate.fieldId()) || session.world.regionMonsterTypes().containsKey(text.value());
    }

    private String system(Session session, String input) {
        String engineName = "纯叙事";
        if (session.selection.engineId() != null) {
            engineName = engines == null ? session.selection.engineId() : engines.engine(session.selection.engineId()).displayName();
        }
        StringBuilder builder = new StringBuilder(SYSTEM).append("\n## 世界背景\n世界名：").append(session.world.nameHint())
                .append("\n描述：").append(session.world.descriptionHint()).append("\n引擎：").append(engineName)
                .append("\n## 已收集答案\n");
        if (session.values.isEmpty()) builder.append("（暂无）\n"); else session.values.forEach((key, value) -> builder.append(key).append(": ").append(value).append('\n'));
        builder.append("## 剩余字段\n");
        for (InitializationField field : session.schema.fields()) if (!session.values.containsKey(field.id())) builder.append(field.id()).append("（").append(field.label()).append("，类型 ").append(field.type()).append(field.required() ? "，必填" : "，可选").append("，选项 ").append(field.options().isEmpty() ? "开放" : field.options()).append("）\n");
        builder.append("## 当前玩家输入\n").append(input == null ? "" : input);
        return builder.toString();
    }

    private static NarrativeSnapshot emptySnapshot() {
        return new NarrativeSnapshot(new NarrativeSnapshot.WorldView(1, null, null, null, null, null, "Dormant", List.of(), List.of(), List.of()), null, List.of());
    }

    private static Map<String, Object> collected(Session session) {
        Map<String, Object> result = new LinkedHashMap<>();
        session.values.forEach((key, value) -> result.put(key, primitive(value)));
        session.worldValues.forEach((key, value) -> result.put(key, primitive(value)));
        return Map.copyOf(result);
    }

    private static Object primitive(InitializationValue value) {
        return switch (value) {
            case InitializationValue.Text text -> text.value();
            case InitializationValue.IntegerNumber number -> number.value();
            case InitializationValue.DecimalNumber number -> number.value();
            case InitializationValue.Flag flag -> flag.value();
        };
    }

    private static String text(InitializationValue value, String fallback) { return value instanceof InitializationValue.Text text && !text.value().isBlank() ? text.value() : fallback; }
    private static String text(Object value, String fallback) { return value instanceof InitializationValue.Text text && !text.value().isBlank() ? text.value() : fallback; }
    private static int integer(InitializationValue value, int fallback) { return value instanceof InitializationValue.IntegerNumber number ? (int) number.value() : fallback; }

    private static void validate(NarrativeResponse response) {
        if (response == null || response.narration().length() > 6000 || response.initializationQuestions().size() > 1 || response.parseCandidates().size() > 16) throw new IllegalArgumentException("Invalid initialization narrative response");
    }

    /** 初始化会话的可变内存状态。
     * @param id 会话标识
     * @param selection 世界书和引擎选择
     * @param schema 组合后的初始化字段 schema
     * @param world 当前世界书定义
     */
    public static final class Session {
        private final String id;
        private final GameSelection selection;
        private final InitializationSchema schema;
        private final WorldDefinition world;
        private final Map<String, InitializationValue> values = new LinkedHashMap<>();
        private final Map<String, InitializationValue> worldValues = new LinkedHashMap<>();
        private Stage stage = Stage.QUESTIONS;
        private Turn turn;

        private Session(String id, GameSelection selection, InitializationSchema schema, WorldDefinition world) {
            this.id = id; this.selection = selection; this.schema = schema; this.world = world;
        }
        public String id() { return id; }
        public GameSelection selection() { return selection; }
    }

    private enum Stage { QUESTIONS, SUPPLEMENT, READY }

    /** 初始化接口返回的 GM 回合。
     * @param narration GM 话术
     * @param question 当前字段问题，完成后为空
     * @param parseCandidates 本轮经服务端接受的解析候选
     * @param collected 已收集的可回显原始值
     * @param done 是否可以调用 finalize
     */
    public record Turn(String narration, NarrativeInitializationQuestion question,
                       List<NarrativeParseCandidate> parseCandidates, Map<String, Object> collected, boolean done) {
        public Turn {
            narration = narration == null ? "" : narration;
            parseCandidates = parseCandidates == null ? List.of() : List.copyOf(parseCandidates);
            collected = collected == null ? Map.of() : Map.copyOf(collected);
        }
    }
}
