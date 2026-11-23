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
import com.gameneko.aigame.narrative.NarrativePrompt;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 驱动初始化问答：由服务端 schema 决定问题与顺序，模型声明确定字段值，服务端执行类型和枚举校验。 */
@Service
public final class InitializationNarrationService {
    private static final String SUPPLEMENT_FIELD = "__world_supplement";
    private static final Set<String> WORLD_FIELDS = Set.of("name", "description", "location", "region", "weather", "season", "time", "mainQuest", "originScene");
    private static final Map<String, String> ARCHETYPE_LABELS = Map.of(
            "swordsman", "剑士", "mage", "法师", "ranger", "游侠", "alchemist", "炼金术师");
    private static final Map<String, String> ARCHETYPE_DESCRIPTIONS = Map.of(
            "swordsman", "近战输出，攻防均衡，擅长剑术与格挡",
            "mage", "远程法术，高伤害低生存，擅长元素魔法",
            "ranger", "远程射手，机动灵活，擅长弓箭与野外侦察",
            "alchemist", "辅助定位，能调配药剂与投掷物支援队友");
    private static final String SYSTEM = """
            你是文字冒险游戏的 GM（主持人），正在与玩家对话做「开局确认」，而不是开始游戏叙事。
            问题、顺序及选项由服务端 schema 决定，narration 仅自然承接本轮回答，不生成问题结构，不推进剧情。
            把本轮玩家确认或修改的字段放进 updates，值使用 {"type":"text"|"integer"|"decimal"|"flag","value":...}。
            已确认字段也允许修改，标量新值覆盖旧值；不要把尚未确认的建议当成玩家答案。
            枚举字段（职业、难度）必须使用字段表里的稳定 id，例如 swordsman、adventure，不能用中文 label。
            region 必须为 forest/plains/mountain/dungeon/swamp/ruins/cavern/town/corrupted_land/volcanic 之一，location 是具体地点的自由文本。
            补充设定可声明 name/description/location/region/weather/season/time/mainQuest/originScene。
            action 返回 null，options 返回空数组，无字段声明时 updates 输出 {}；只输出响应 JSON。
            narration 不超过 60 个汉字，updates 最多 16 项。
            """;

    private final NarrativeProvider provider;
    private final GameInitializationSchemaService schemas;
    private final ContentCatalog content;
    private final GameEngineCatalog engines;

    /** 创建初始化叙事服务。
     * @param provider 生成初始化话术和确定字段值的叙事提供器
     * @param schemas 组合世界书与引擎字段的 schema 服务
     * @param content 读取世界书背景和世界字段约束的目录
     * @param engines 读取引擎显示名的目录
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

    /** 创建不依赖引擎目录的初始化服务，供纯单元测试使用。 */
    /** @param provider 叙事提供器 @param schemas 组合 schema 服务 @param content 世界书目录 */
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

    /** 打开会话并生成 GM 开场白和第一问。 */
    /** @param session 待打开的初始化会话 */
    public Turn open(Session session) {
        return nextQuestion(session, ask(session, ""));
    }

    /** 接收玩家回答，采纳字段声明并推进到下一问或补充设定阶段。 */
    /** @param session 当前初始化会话 @param input 玩家自然语言回答 */
    public Turn answer(Session session, String input) {
        if (session == null) throw new IllegalArgumentException("Initialization session is required");
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Initialization answer is required");
        String answer = input.trim();
        if (session.stage == Stage.READY) return session.turn;
        if (session.stage == Stage.SUPPLEMENT) return answerSupplement(session, answer);
        NarrativeResponse response = ask(session, answer);
        applyUpdates(session, response.updates());
        return nextQuestion(session, response);
    }

    /** 判断会话是否已完成补充并可 finalize。 */
    /** @param session 当前初始化会话 */
    public boolean ready(Session session) { return session != null && session.stage == Stage.READY; }

    /** 将会话答案转换为一次性创建游戏所需的世界请求。 */
    /** @param session 已完成的初始化会话 */
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

    /** 返回已通过服务端字段校验的引擎初始化答案。 */
    /** @param session 已完成的初始化会话 */
    public Map<String, InitializationValue> answers(Session session) {
        if (!ready(session)) throw new IllegalArgumentException("Initialization confirmation is required");
        return Map.copyOf(session.values);
    }

    /** 处理必填字段完成后的开放式世界设定补充。 */
    /** @param session 当前初始化会话 @param answer 玩家补充或确认文本 */
    private Turn answerSupplement(Session session, String answer) {
        if (answer.matches("(?i)没有|没有了|无|不用|no|none|确认|确定|就这样")) {
            session.stage = Stage.READY;
            session.turn = new Turn("好的，开局资料已经确认。现在就开始你的故事。", null, collected(session), true);
            return session.turn;
        }
        NarrativeResponse response = ask(session, answer);
        boolean applied = applyUpdates(session, response.updates());
        if (!applied) {
            session.worldValues.merge("originScene", new InitializationValue.Text(answer), (oldValue, newValue) -> new InitializationValue.Text(text(oldValue, "") + "\n" + text(newValue, "")));
        }
        session.stage = Stage.READY;
        session.turn = new Turn(response.narration().isBlank() ? "已记录你的补充。好的，开局资料已经确认。" : response.narration() + "\n好的，开局资料已经确认。", null, collected(session), true);
        return session.turn;
    }

    /** 按 schema 必填字段顺序生成问题，字段齐备后进入开放补充阶段。
     * @param session 当前初始化会话
     * @param response 本轮模型话术及字段声明
     * @return 服务端生成问题的回合
     */
    private Turn nextQuestion(Session session, NarrativeResponse response) {
        List<InitializationField> missing = missingRequired(session);
        if (missing.isEmpty()) {
            session.stage = Stage.SUPPLEMENT;
            session.turn = new Turn(response.narration().isBlank() ? "基本资料已经确认。" : response.narration(),
                    new NarrativeInitializationQuestion(SUPPLEMENT_FIELD,
                            "还有什么想补充的吗？可以描述开场地点、天气、季节或主线方向；没有的话请输入“没有”。", List.of()),
                    collected(session), false);
        } else {
            InitializationField field = missing.getFirst();
            session.turn = new Turn(response.narration().isBlank() ? field.prompt() : response.narration(),
                    questionFor(session, field), collected(session), false);
        }
        return session.turn;
    }

    /** 调用叙事提供器，失败时向上抛出异常交由上层提示用户。
     * @param session 当前初始化会话
     * @param input 本轮玩家文本，开场为空字符串
     */
    private NarrativeResponse ask(Session session, String input) {
        NarrativeResponse response = provider.respond(new NarrativePrompt(system(session, input), input, emptySnapshot(), "zh-CN"));
        validate(response);
        return response;
    }

    /** 校验并覆盖已声明的世界和 schema 字段，非法字段独立忽略。
     * @param session 当前初始化会话
     * @param updates GM 声明的类型化确定值
     * @return 是否至少采纳一个字段
     */
    private boolean applyUpdates(Session session, Map<String, InitializationValue> updates) {
        boolean applied = false;
        for (var entry : updates.entrySet()) {
            String id = entry.getKey();
            InitializationValue value = entry.getValue();
            if (WORLD_FIELDS.contains(id)) {
                if (!(value instanceof InitializationValue.Text text)) continue;
                if ("region".equals(id) && !session.world.regionMonsterTypes().containsKey(text.value())) continue;
                session.worldValues.put(id, value);
                applied = true;
                continue;
            }
            InitializationField field = fieldById(session, id);
            if (field == null) continue;
            value = normalizeValue(session, field, value);
            if (value == null) continue;
            var probe = new LinkedHashMap<>(session.values);
            probe.put(id, value);
            if (session.schema.validate(probe).errors().containsKey(id)) continue;
            session.values.put(id, value);
            applied = true;
        }
        return applied;
    }

    /** 按 schema 规范化字段声明并拒绝非法枚举。
     * @param session 当前初始化会话
     * @param field 当前 schema 字段定义
     * @param value 待转换或验证的字段值
     */
    private InitializationValue normalizeValue(Session session, InitializationField field, InitializationValue value) {
        if (field.type() == InitializationField.ValueType.TEXT && value instanceof InitializationValue.Text text) {
            String normalized = normalizeOption(session, field, text.value());
            if (!field.options().isEmpty() && !field.options().contains(normalized)) return null;
            return new InitializationValue.Text(normalized);
        }
        return value;
    }

    /** 将稳定 id 或受支持的展示标签规范化为选项值。
     * @param session 当前初始化会话
     * @param field 当前 schema 字段定义
     * @param raw 原始文本，可空
     */
    private String normalizeOption(Session session, InitializationField field, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (field.options().contains(value)) return value;
        if ("difficulty".equals(field.id())) {
            for (var entry : session.world.difficulties().entrySet()) if (entry.getValue().label().equals(value)) return entry.getKey();
        }
        if ("archetype".equals(field.id())) {
            for (var entry : ARCHETYPE_LABELS.entrySet()) if (entry.getValue().equals(value)) return entry.getKey();
        }
        return value;
    }

    /** 使用 schema 提问文本与服务端枚举标签生成前端问题。
     * @param session 当前初始化会话
     * @param field 待确认的 schema 字段
     * @return 完整问题与合法选项
     */
    private NarrativeInitializationQuestion questionFor(Session session, InitializationField field) {
        List<String> options = field.options().stream().map(option -> optionLabel(session, field, option)).toList();
        return new NarrativeInitializationQuestion(field.id(), field.prompt(), options);
    }

    /** 读取字段选项的中文展示标签。
     * @param session 当前初始化会话
     * @param field 当前 schema 字段定义
     * @param option schema 中的稳定选项 id
     */
    private String optionLabel(Session session, InitializationField field, String option) {
        if ("difficulty".equals(field.id()) && session.world.difficulties().containsKey(option)) return session.world.difficulties().get(option).label();
        return ARCHETYPE_LABELS.getOrDefault(option, option);
    }

    /** 读取合法选项的说明供模型参考。
     * @param session 当前初始化会话
     * @param field 当前 schema 字段定义
     * @param option schema 中的稳定选项 id
     */
    private String optionDescription(Session session, InitializationField field, String option) {
        if ("difficulty".equals(field.id()) && session.world.difficulties().containsKey(option)) return session.world.difficulties().get(option).description();
        return ARCHETYPE_DESCRIPTIONS.getOrDefault(option, "");
    }

    /** 在组合 schema 中查找字段，未知标识返回 null。
     * @param session 当前初始化会话
     * @param fieldId 待查字段稳定标识，可空
     */
    private InitializationField fieldById(Session session, String fieldId) {
        if (fieldId == null) return null;
        return session.schema.fields().stream().filter(field -> field.id().equals(fieldId)).findFirst().orElse(null);
    }

    /** 按 schema 顺序返回尚未确认的必填字段。
     * @param session 当前初始化会话
     */
    private List<InitializationField> missingRequired(Session session) {
        return session.schema.fields().stream().filter(field -> field.required() && !session.values.containsKey(field.id())).toList();
    }

    /** 将已确认字段与完整世界书叙事设定组合成模型上下文。
     * @param session 当前角色初始化会话
     * @param input 当前玩家输入，可空
     * @return 系统提示文本
     */
    private String system(Session session, String input) {
        String engineName = "纯叙事";
        if (session.selection.engineId() != null) {
            engineName = engines == null ? session.selection.engineId() : engines.engine(session.selection.engineId()).displayName();
        }
        StringBuilder builder = new StringBuilder(SYSTEM).append("\n## 世界背景\n世界名：").append(session.world.nameHint())
                .append("\n引擎：").append(engineName)
                .append("\n## 已收集答案\n");
        if (session.values.isEmpty()) builder.append("（暂无）\n"); else session.values.forEach((key, value) -> builder.append(key).append(": ").append(value).append('\n'));
        var narrative = content.worldBook(session.selection.worldId()).narrative();
        builder.append("\n世界背景：").append(narrative.descriptionHint())
                .append("\n故事基调：").append(narrative.tone())
                .append("\n世界规则：").append(narrative.worldTropes())
                .append("\n主要势力：").append(narrative.factions())
                .append("\n出身起点：").append(narrative.originContexts()).append('\n');
        builder.append("## 待确认字段\n");
        for (InitializationField field : session.schema.fields()) {
            if (session.values.containsKey(field.id())) continue;
            builder.append(field.id()).append("（").append(field.label()).append("，").append(field.type())
                    .append(field.required() ? "，必填" : "，可选").append("）\n");
            for (String option : field.options()) {
                builder.append("  - ").append(option).append("（").append(optionLabel(session, field, option)).append("）：")
                        .append(optionDescription(session, field, option)).append("\n");
            }
        }
        builder.append("## 已确认世界字段\n").append(session.worldValues)
                .append("\n## 当前服务端问题\n").append(session.turn == null ? missingRequired(session) : session.turn.question()).append('\n');
        builder.append("## 当前玩家输入\n").append(input == null ? "" : input);
        return builder.toString();
    }

    /** 创建初始化阶段不含引擎状态的中立快照。
     */
    private static NarrativeSnapshot emptySnapshot() {
        return new NarrativeSnapshot(new NarrativeSnapshot.WorldView(1, null, null, null, null, null, "Dormant", List.of(), List.of(), List.of()), null, List.of());
    }

    /** 汇总类型化答案为前端可展示的普通值。
     * @param session 当前初始化会话
     */
    private static Map<String, Object> collected(Session session) {
        Map<String, Object> result = new LinkedHashMap<>();
        session.values.forEach((key, value) -> result.put(key, primitive(value)));
        session.worldValues.forEach((key, value) -> result.put(key, primitive(value)));
        return Map.copyOf(result);
    }

    /** 将有限类型协议值展开为普通 JSON 值。
     * @param value 待转换或验证的字段值
     */
    private static Object primitive(InitializationValue value) {
        return switch (value) {
            case InitializationValue.Text text -> text.value();
            case InitializationValue.IntegerNumber number -> number.value();
            case InitializationValue.DecimalNumber number -> number.value();
            case InitializationValue.Flag flag -> flag.value();
        };
    }

    /** 提取非空白文本，否则返回默认值。
     * @param value 待转换或验证的字段值
     * @param fallback 字段缺失或类型不符时的默认值
     */
    private static String text(InitializationValue value, String fallback) { return value instanceof InitializationValue.Text text && !text.value().isBlank() ? text.value() : fallback; }
    /** 提取经过 schema 范围校验的整数答案。
     * @param value 待转换或验证的字段值
     * @param fallback 字段缺失或类型不符时的默认值
     */
    private static int integer(InitializationValue value, int fallback) { return value instanceof InitializationValue.IntegerNumber number ? (int) number.value() : fallback; }

    /** 检查响应结构或动作与当前引擎约束的一致性。
     * @param response 待检查的模型响应
     */
    private static void validate(NarrativeResponse response) {
        if (response == null || response.narration().length() > 6000) throw new IllegalArgumentException("Invalid initialization narrative response");
        response.validateUpdates();
    }

    /** 初始化会话的可变内存状态。 */
    public static final class Session {
        private final String id;
        private final GameSelection selection;
        private final InitializationSchema schema;
        private final WorldDefinition world;
        private final Map<String, InitializationValue> values = new LinkedHashMap<>();
        private final Map<String, InitializationValue> worldValues = new LinkedHashMap<>();
        private Stage stage = Stage.QUESTIONS;
        private Turn turn;

        /** 创建尚未确认答案的初始化会话。
         * @param id 会话唯一标识
         * @param selection 世界书与可选引擎选择
         * @param schema 组合后的初始化字段定义
         * @param world 当前权威世界状态
         */
        private Session(String id, GameSelection selection, InitializationSchema schema, WorldDefinition world) {
            this.id = id; this.selection = selection; this.schema = schema; this.world = world;
        }
        /** 返回会话标识。
         */
        public String id() { return id; }
        /** 返回世界书与可选引擎选择。
         */
        public GameSelection selection() { return selection; }
    }

    private enum Stage { QUESTIONS, SUPPLEMENT, READY }

    /** 初始化接口返回的 GM 回合。
     * @param narration narration 对应的协议值
     * @param question question 对应的协议值
     * @param collected collected 对应的协议值
     * @param done done 对应的协议值
     */
    public record Turn(String narration, NarrativeInitializationQuestion question,
                       Map<String, Object> collected, boolean done) {
        /** 复制已确认答案并规范化缺失话术。 */
        public Turn {
            narration = narration == null ? "" : narration;
            collected = collected == null ? Map.of() : Map.copyOf(collected);
        }
    }
}
