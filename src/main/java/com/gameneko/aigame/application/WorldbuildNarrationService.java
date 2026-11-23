package com.gameneko.aigame.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.content.TemplateCatalog;
import com.gameneko.aigame.content.WorldTemplate;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.*;
import org.springframework.stereotype.Service;

import java.util.*;

/** 使用与角色创建相同的模型协议引导世界构建，服务端负责枚举、列表和完成条件校验。 */
@Service
public final class WorldbuildNarrationService {
    private static final Map<String, String> TYPES = new LinkedHashMap<>();
    private static final Map<String, String> TONES = Map.of("light", "轻松", "balanced", "平衡", "hardcore", "硬核");
    private static final List<String> REQUIRED = List.of("type", "name", "tone");
    private static final List<String> ORDER = List.of("type", "description_hint", "name", "world_tropes", "factions", "tone", "origin_contexts");
    private static final String SYSTEM = """
            你是文字冒险的世界构建 GM，正在和玩家一起设计世界，尚未开始游戏。
            问题、顺序与选项由服务端预制 schema 决定；narration 写自然简短的回应。
            必填字段为 type/name/tone，上下文会给出「必填字段还缺」清单，只有集齐这些字段服务端才会结束。
            把本轮玩家确认或修改的全部字段放进 updates；标量覆盖，列表去重新增，不可把建议当成确认值。
            updates 的键为字段名，值使用 {"type":"text","value":"文本"}，无声明时输出 {}。
            world_tropes 和 origin_contexts 的文本必须是 JSON 字符串数组；factions 的文本必须是 [{"type":"势力名","desc_hint":"描述"}] 的 JSON 数组。
            type 合法值：isekai(异世界)、cyberpunk(赛博朋克)、fantasy(奇幻)、cosmic_horror(克苏鲁恐怖)、noir(黑色电影)、wasteland(末日废土)。
            tone 合法值：light(轻松)、balanced(平衡)、hardcore(硬核)。updates 中枚举使用稳定 id，展示选项由服务端生成。
            模板默认值可供玩家采纳或修改，但不强迫玩家采用现成背景。
            action 返回 null（世界构建阶段不调用引擎）、options 返回空数组，仅输出响应 JSON。
            控制输出体量：narration 不超过 60 个汉字；updates 最多 16 项；options 最多 3 个且每个不超过 20 个汉字。
            """;
    static {
        TYPES.put("isekai", "异世界"); TYPES.put("cyberpunk", "赛博朋克"); TYPES.put("fantasy", "奇幻");
        TYPES.put("cosmic_horror", "克苏鲁恐怖"); TYPES.put("noir", "黑色电影"); TYPES.put("wasteland", "末日废土");
    }
    private final NarrativeProvider provider;
    private final ObjectMapper mapper;
    private final TemplateCatalog templates;

    /** 创建世界构建服务。
     * @param provider 角色初始化共用的结构化叙事提供器
     * @param mapper 列表声明值 JSON 解析器
     * @param templates 可复用模板目录
     */
    public WorldbuildNarrationService(NarrativeProvider provider, ObjectMapper mapper, TemplateCatalog templates) {
        this.provider = provider; this.mapper = mapper; this.templates = templates;
    }

    /** 创建会话并生成第一问，模型失败时不创建半完成会话。
     * @param id 会话标识
     * @param preferredTemplateId 可空的优先复用模板标识
     * @return 包含第一回合的会话
     */
    public Session session(String id, String preferredTemplateId) {
        if (preferredTemplateId != null && !preferredTemplateId.isBlank()) templates.template(preferredTemplateId);
        Session session = new Session(id, preferredTemplateId);
        session.turn = respond(session, "", true);
        return session;
    }

    /** 接受回答并生成下一回合；模型调用失败时保留原会话供重试。
     * @param session 当前会话
     * @param input 玩家本轮自然语言输入，最多 8000 字符
     * @return 下一回合或已完成回合
     */
    public Turn answer(Session session, String input) {
        if (session == null) throw new IllegalArgumentException("Worldbuild session is required");
        if (input == null || input.isBlank() || input.length() > 8000) throw new IllegalArgumentException("Worldbuild answer must contain 1 to 8000 characters");
        synchronized (session) {
            if (session.turn.done()) return session.turn;
            Session next = session.copy();
            Turn turn = respond(next, input.trim(), false);
            session.values.clear(); session.values.putAll(next.values);
            session.history.clear(); session.history.addAll(next.history);
            session.template = next.template; session.turn = turn;
            return turn;
        }
    }

    /** 调用模型并将声明值校验、服务端问题选择组合为一个回合。
     * @param session 待推进的会话副本
     * @param input 当前玩家输入
     * @param opening 是否为开场回合
     * @return 经过服务端校验的回合
     */
    private Turn respond(Session session, String input, boolean opening) {
        List<String> pendingMissing = REQUIRED.stream().filter(field -> !session.values.containsKey(field)).toList();
        String context = SYSTEM + "\n已确认配置：" + json(session.values)
                + "\n必填字段还缺：" + (pendingMissing.isEmpty() ? "无" : String.join("、", pendingMissing))
                + "\n当前问题：" + (session.turn == null ? "type" : session.turn.question())
                + "\n复用模板：" + (session.template == null ? "无，空白设定" : session.template.name())
                + "\n模板默认配置：" + (session.template == null ? "{}" : json(session.template.creationDefaults()))
                + "\n对话历史：" + json(session.history);
        NarrativeResponse response = provider.respond(new NarrativePrompt(context, input,
                new NarrativeSnapshot(new NarrativeSnapshot.WorldView(1, null, null, null, null, null,
                        "Dormant", List.of(), List.of(), List.of()), null, List.of()), "zh-CN"));
        if (response == null || response.narration().length() > 6000) throw new IllegalArgumentException("Invalid worldbuild response");
        response.validateUpdates();
        if (!opening) {
            response.updates().forEach((field, value) -> {
                if (value instanceof InitializationValue.Text text) apply(session, field, text.value());
            });
            if (session.values.containsKey("type")) selectTemplate(session);
        }
        List<String> missing = REQUIRED.stream().filter(field -> !session.values.containsKey(field)).toList();
        boolean done = !opening && missing.isEmpty();
        String field = done ? null : ORDER.stream().filter(id -> !session.values.containsKey(id)).findFirst().orElse(null);
        Turn turn = turn(session, response.narration(), field, done);
        session.history.add(Map.of("role", "player", "text", input));
        session.history.add(Map.of("role", "gm", "text", turn.narration(), "question", turn.question() == null ? "" : turn.question().prompt()));
        session.turn = turn;
        return turn;
    }

    /** 选择与世界类型匹配的模板，优先使用玩家指定的模板。
     * @param session 已确认世界类型的会话
     */
    private void selectTemplate(Session session) {
        String type = String.valueOf(session.values.get("type"));
        if (session.template != null && session.template.type().equals(type)) return;
        WorldTemplate preferred = session.preferredTemplateId == null || session.preferredTemplateId.isBlank()
                ? null : templates.template(session.preferredTemplateId);
        session.template = preferred != null && preferred.type().equals(type) ? preferred
                : templates.templatesForType(type).stream().findFirst().orElse(null);
        if (session.template != null) {
            session.template.creationDefaults().forEach((field, value) -> {
                if (!REQUIRED.contains(field) && ORDER.contains(field)) session.values.putIfAbsent(field, value);
            });
        }
    }

    /** 校验模型声明值并合并到配置，数组支持多次补充且拒绝错误类型。
     * @param session 当前会话
     * @param field 声明值字段稳定标识
     * @param raw 模型声明值文本
     * @return 是否接受声明值
     */
    private boolean apply(Session session, String field, String raw) {
        if (!ORDER.contains(field) || raw == null || raw.length() > 8000) return false;
        if ("type".equals(field) || "tone".equals(field)) {
            var options = "type".equals(field) ? TYPES : TONES;
            String value = normalize(raw, options);
            if (!options.containsKey(value)) return false;
            session.values.put(field, value); return true;
        }
        boolean skip = finished(raw) || raw.matches("跳过|没有|没有了|无|不用|不需要");
        if ("name".equals(field) || "description_hint".equals(field)) {
            if (raw.isBlank() || ("name".equals(field) && (skip || raw.length() > 200))) return false;
            session.values.put(field, skip ? "" : raw.trim()); return true;
        }
        List<Object> parsed = new ArrayList<>();
        if (!skip) {
            try {
                JsonNode node = mapper.readTree(raw);
                if (!node.isArray() || node.size() > 50) return false;
                for (JsonNode item : node) {
                    if ("factions".equals(field)) {
                        if (!item.isObject() || !item.path("type").isTextual() || item.path("type").asText().isBlank()
                                || !item.path("desc_hint").isTextual()) return false;
                        parsed.add(Map.of("type", item.path("type").asText(), "desc_hint", item.path("desc_hint").asText()));
                    } else {
                        if (!item.isTextual() || item.asText().isBlank()) return false;
                        parsed.add(item.asText().trim());
                    }
                }
            } catch (java.io.IOException exception) {
                return false;
            }
        }
        List<Object> merged = new ArrayList<>();
        if (session.values.get(field) instanceof List<?> previous) merged.addAll(previous);
        for (Object value : parsed) if (!merged.contains(value)) merged.add(value);
        if (merged.size() > 50) return false;
        session.values.put(field, List.copyOf(merged)); return true;
    }

    /** 创建问题、可复用模板说明和完整配置组成的响应。
     * @param session 当前会话
     * @param narration 模型话术
     * @param field 当前问题字段，完成时为空
     * @param done 是否完成
     * @return 回合响应
     */
    private Turn turn(Session session, String narration, String field, boolean done) {
        String prompt = field == null ? "" : switch (field) {
                    case "type" -> "你想创建哪一类世界？";
                    case "name" -> "这个世界叫什么名字？";
                    case "tone" -> "故事希望是轻松、平衡还是硬核？";
                    case "description_hint" -> "请描述世界背景，也可以说跳过。";
                    case "world_tropes" -> "世界有哪些规则或常识？可以逐条补充。";
                    case "factions" -> "有哪些主要势力？请描述它们的名称和特点。";
                    default -> "角色可以从哪些出身起点开始？也可以跳过。";
                };
        List<String> options = "type".equals(field) ? List.copyOf(TYPES.values()) : "tone".equals(field)
                ? List.of("轻松", "平衡", "硬核") : "name".equals(field) ? List.of() : List.of("跳过");
        var question = field == null ? null : new NarrativeInitializationQuestion(field, prompt, options);
        return new Turn(narration == null || narration.isBlank() ? done ? "世界设定已确认，可以保存为模板或进入规则选择。" : prompt : narration,
                question, Map.copyOf(session.values), done,
                session.template == null ? "" : session.template.id(), session.template == null ? "" : session.template.name());
    }

    /** 将中文枚举展示值规范化为稳定标识。
     * @param raw 玩家或模型文本
     * @param choices 标识与中文展示值映射
     * @return 规范化标识，非法值原样返回
     */
    private static String normalize(String raw, Map<String, String> choices) {
        String value = raw.trim();
        return choices.entrySet().stream().filter(entry -> entry.getValue().equals(value)).map(Map.Entry::getKey)
                .findFirst().orElse(value.toLowerCase(Locale.ROOT));
    }

    /** 判断玩家是否明确结束补充。
     * @param input 玩家输入
     * @return 是否为结束用语
     */
    private static boolean finished(String input) { return input.matches("(?i)完成|好了|就这样|确认|结束|没有了|done|finish"); }

    /** 将会话数据编码为模型上下文。
     * @param value 引擎中立配置或历史消息
     * @return JSON 文本
     */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException exception) { throw new IllegalArgumentException("Invalid worldbuild context", exception); }
    }

    /** 保存尚未落盘的世界构建会话。 */
    public static final class Session {
        private final String id;
        private final String preferredTemplateId;
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final List<Map<String, String>> history = new ArrayList<>();
        private WorldTemplate template;
        private Turn turn;
        /** 创建会话状态。
         * @param id 会话标识
         * @param preferredTemplateId 可空的优先模板标识
         */
        private Session(String id, String preferredTemplateId) { this.id = id; this.preferredTemplateId = preferredTemplateId; }
        /** 复制状态以保证请求失败不会部分更新。
         * @return 可独立修改的副本
         */
        private Session copy() {
            Session copy = new Session(id, preferredTemplateId); copy.values.putAll(values);
            copy.history.addAll(history); copy.template = template; copy.turn = turn; return copy;
        }
        /** 返回最新会话回合。
         * @return 当前回合
         */
        public Turn turn() { return turn; }
    }

    /** 世界构建对话回合。
     * @param narration GM 回应
     * @param question 当前问题，完成时为空
     * @param collected 已收集世界配置，列表保持结构化
     * @param done 是否可进入后续步骤
     * @param templateId 复用模板标识，无模板为空字符串
     * @param templateName 复用模板展示名称
     */
    public record Turn(String narration, NarrativeInitializationQuestion question,
                       Map<String, Object> collected,
                       boolean done, String templateId, String templateName) {}
}
