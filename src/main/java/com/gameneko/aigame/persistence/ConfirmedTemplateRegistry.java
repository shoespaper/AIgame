package com.gameneko.aigame.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.content.WorldbookDefinition;
import com.gameneko.aigame.content.TemplateCatalog;
import com.gameneko.aigame.content.WorldTemplate;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.NarrativePrompt;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** 在开局确认时复用世界素材并一次性登记新素材、规则引用与世界绑定。 */
@Component
public final class ConfirmedTemplateRegistry {
    private final TemplateCatalog catalog;
    private final ObjectMapper mapper;
    private final NarrativeProvider provider;
    private final Path file;
    private final JsonNode reference;
    private ObjectNode registry;

    /** 加载生成示例和已确认的模板目录。
     * @param mapper JSON 编解码器
     * @param provider 生成符合模板格式的模型提供器
     * @param catalog 已有内置与用户世界模板目录
     * @param filename 模板目录持久化文件路径
     */
    public ConfirmedTemplateRegistry(ObjectMapper mapper, NarrativeProvider provider, TemplateCatalog catalog,
            @Value("${aigame.templates.registry:${aigame.content.root:./data}/confirmed-templates/registry.json}") String filename) throws IOException {
        this.catalog = catalog;
        this.mapper = mapper;
        this.provider = provider;
        this.file = Path.of(filename).toAbsolutePath();
        try (InputStream stream = new ClassPathResource("world/templates/ai-template-reference.json").getInputStream()) {
            reference = mapper.readTree(stream);
        }
        registry = Files.exists(file) ? (ObjectNode) mapper.readTree(file.toFile()) : mapper.createObjectNode();
    }

    /** 查找或生成模板，并在所有引用校验成功后提交。
     * @param gameId 本次确认的游戏标识
     * @param book 玩家完成构建的世界书
     * @param engineId 玩家选中的引擎标识，纯叙事时可为空
     */
    public synchronized void confirm(String gameId, WorldbookDefinition book, String engineId) {
        String id = book.templateId().isBlank() ? book.type() : book.templateId();
        List<WorldTemplate> candidates = catalog.templatesForType(book.type());
        if (book.templateId().isBlank() && !registry.path("worlds").has(id) && !candidates.isEmpty()) {
            String typeId = id;
            id = candidates.stream().filter(t -> t.id().equals(typeId)).findFirst().orElse(candidates.getFirst()).id();
        }
        String selectedId = id;
        WorldTemplate bundled = candidates.stream().filter(t -> t.id().equals(selectedId)).findFirst().orElse(null);
        String engine = engineId == null ? "" : engineId.trim().toLowerCase(Locale.ROOT);
        ObjectNode next = registry.deepCopy();
        ObjectNode worlds = next.withObject("/worlds");
        ObjectNode rules = next.withObject("/rules");
        ObjectNode bindings = next.withObject("/bindings");
        if (!engine.isBlank()) {
            ObjectNode engineRules = rules.withObject("/" + engine);
            registerRules(engine, reference.path("engine_template_registrations").path(engine), engineRules);
        }
        JsonNode world = worlds.path(id);
        boolean needsRules = !engine.isBlank() && !world.path("registered_engines").has(engine);
        if (world.isMissingNode() || needsRules) {
            JsonNode proposal;
            if (bundled != null && bundled.materials().has("regions")
                    && (engine.isBlank() || bundled.registrations().has(engine))) {
                ObjectNode envelope = mapper.createObjectNode();
                envelope.set("world_template", bundled.materials());
                envelope.set("engine_template_registrations", bundled.registrations());
                proposal = envelope;
            } else {
                proposal = generate(book, id, engine, world, rules.path(engine));
            }
            JsonNode candidate = proposal.path("world_template");
            if (!candidate.isObject() || !id.equals(candidate.path("template_id").asText())
                    || !book.type().equals(candidate.path("world_type").asText())) {
                throw new IllegalArgumentException("生成的世界模板标识或类型不匹配");
            }
            ObjectNode result = world.isMissingNode() ? ((ObjectNode) candidate).deepCopy() : ((ObjectNode) world).deepCopy();
            result.remove("difficulty_presets");
            if (world.isMissingNode()) {
                result.remove("registered_engines");
                for (JsonNode field : result) {
                    if (field.isArray()) for (JsonNode entry : field) {
                        if (entry instanceof ObjectNode object) object.remove("engines");
                    }
                }
                validateMaterials(result);
            }
            if (!engine.isBlank()) {
                ObjectNode known = rules.withObject("/" + engine);
                registerRules(engine, proposal.path("engine_template_registrations").path(engine), known);
                bindRules(engine, candidate, result, known);
                result.withObject("/registered_engines").put(engine, true);
            }
            worlds.set(id, result);
        }
        bindings.put(gameId, id);
        if (!next.equals(registry)) persist(next);
    }

    /** 检查素材集合、必填标识及区域引用的生物是否存在。
     * @param world 待注册的世界模板
     */
    private void validateMaterials(ObjectNode world) {
        Iterator<Map.Entry<String, JsonNode>> fields = reference.path("world_template").fields();
        Set<String> monsters = new HashSet<>();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isArray()) continue;
            JsonNode entries = world.path(field.getKey());
            if (!entries.isArray()) throw new IllegalArgumentException("模板缺少素材池：" + field.getKey());
            Set<String> ids = new HashSet<>();
            for (JsonNode entry : entries) {
                String id = entry.path("id").asText();
                if (!id.matches("[A-Za-z0-9_-]{1,80}") || !ids.add(id) || entry.path("name").asText().isBlank()) {
                    throw new IllegalArgumentException("素材名称或标识无效：" + id);
                }
            }
            if ("monster_types".equals(field.getKey())) monsters.addAll(ids);
        }
        for (JsonNode region : world.path("regions")) {
            for (JsonNode monster : region.path("monster_ids")) {
                if (!monsters.contains(monster.asText())) throw new IllegalArgumentException("区域引用了未知生物：" + monster);
            }
        }
    }

    /** 请求模型按参考结构生成缺失的模板。
     * @param book 最终世界设定
     * @param id 待注册的世界模板标识
     * @param engine 所选引擎，空串表示纯叙事
     * @param existing 已有世界模板，缺失时为 MissingNode
     * @param known 该引擎已经注册的规则
     */
    private JsonNode generate(WorldbookDefinition book, String id, String engine, JsonNode existing, JsonNode known) {
        String instruction = "在 updates.template 中以 text 类型返回完整 JSON 字符串。世界模板 template_id 必须为 "
                + id + "，world_type 必须为 " + book.type() + "。仅为所选引擎生成引用。无引擎时只生成叙事素材。"
                + "以如下示例作为字段结构和可用规则类型，只调整名称、描述和合理的小整数。优先复用已有规则 ID。"
                + "已有世界条目的 ID 和内容保留，仅补充所选引擎引用。所选引擎：" + engine
                + "\n结构示例：" + reference + "\n已有世界：" + existing + "\n已有规则：" + known;
        NarrativeSnapshot snapshot = new NarrativeSnapshot(new NarrativeSnapshot.WorldView(1, "", "", "", "",
                List.of(), List.of(), List.of()), null, List.of()).withWorldbook(book);
        NarrativeResponse response = provider.respond(new NarrativePrompt(instruction, book.toString(), snapshot, "zh-CN"));
        if (response == null || !(response.updates().get("template") instanceof InitializationValue.Text text)) {
            throw new IllegalArgumentException("模型未返回结构化模板，请重试确认");
        }
        try { return mapper.readTree(text.value()); }
        catch (IOException e) { throw new IllegalArgumentException("模型返回的模板 JSON 无效", e); }
    }

    /** 按引擎示例校验注册条目的类型及整数参数。
     * @param engine 引擎标识
     * @param proposals 模型提出的新增规则数组
     * @param known 当前事务内的规则索引
     */
    private void registerRules(String engine, JsonNode proposals, ObjectNode known) {
        for (JsonNode rule : proposals) {
            String id = rule.path("template_id").asText();
            if (!id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("规则标识无效");
            if (known.has(id)) continue;
            JsonNode schema = null;
            for (JsonNode example : reference.path("engine_template_registrations").path(engine)) {
                if (example.path("entity_type").equals(rule.path("entity_type"))
                        && example.path("rule_type").equals(rule.path("rule_type"))) schema = example.path("parameters");
            }
            JsonNode params = rule.path("parameters");
            if (schema == null || !params.isObject() || params.size() != schema.size()) throw new IllegalArgumentException("引擎规则或参数无效：" + id);
            Iterator<String> fields = schema.fieldNames();
            while (fields.hasNext()) {
                JsonNode value = params.path(fields.next());
                if (!value.isInt() || value.intValue() < 0 || value.intValue() > 1000) throw new IllegalArgumentException("规则数值越界：" + id);
            }
            known.set(id, rule);
        }
    }

    /** 对照示例类别和素材标识绑定已校验的规则。
     * @param engine 当前引擎
     * @param candidate 模型输出的素材及引用
     * @param result 本次待提交的世界模板
     * @param known 已登记规则
     */
    private void bindRules(String engine, JsonNode candidate, ObjectNode result, ObjectNode known) {
        Map<String,String> kinds = new LinkedHashMap<>();
        reference.path("world_template").fields().forEachRemaining(field -> {
            for (JsonNode entry : field.getValue()) {
                JsonNode ref = entry.path("engines").path(engine);
                if (ref.isMissingNode()) continue;
                for (JsonNode sample : reference.path("engine_template_registrations").path(engine)) {
                    if (sample.path("template_id").equals(ref.path("template_id"))) {
                        kinds.put(field.getKey(), sample.path("entity_type").asText());
                        break;
                    }
                }
            }
        });
        for (Map.Entry<String,String> kind : kinds.entrySet()) {
            Set<String> seen = new HashSet<>();
            for (JsonNode entry : result.path(kind.getKey())) {
                String id = entry.path("id").asText();
                if (!(entry instanceof ObjectNode item) || id.isBlank() || !seen.add(id)) throw new IllegalArgumentException("素材标识无效");
                JsonNode ref = null;
                for (JsonNode proposal : candidate.path(kind.getKey())) {
                    if (id.equals(proposal.path("id").asText())) ref = proposal.path("engines").path(engine);
                }
                if (ref == null || ref.size() != 1 || !kind.getValue().equals(known.path(ref.path("template_id").asText()).path("entity_type").asText())) {
                    throw new IllegalArgumentException("素材缺少有效的引擎引用：" + id);
                }
                item.withObject("/engines").set(engine, ref);
            }
        }
    }

    /** 原子写入完整注册目录。
     * @param next 已通过全部校验的注册目录
     */
    private void persist(ObjectNode next) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "register-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), next);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                registry = next;
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) { throw new IllegalStateException("无法保存模板注册目录", e); }
    }

    /** 返回世界书确认过的叙事素材。
     * @param gameId 游戏存档标识
     */
    public synchronized JsonNode world(String gameId) {
        return registry.path("worlds").path(registry.path("bindings").path(gameId).asText()).deepCopy();
    }

    /** 返回引擎可复用的规则目录。
     * @param engine 引擎标识
     */
    public synchronized JsonNode rules(String engine) { return registry.path("rules").path(engine).deepCopy(); }
}
