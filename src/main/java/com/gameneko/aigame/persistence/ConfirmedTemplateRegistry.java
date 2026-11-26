package com.gameneko.aigame.persistence;

import com.gameneko.aigame.application.port.GamePersistenceException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.content.TemplateCatalog;
import com.gameneko.aigame.content.WorldTemplate;
import com.gameneko.aigame.content.WorldbookDefinition;
import com.gameneko.aigame.engine.EngineTemplatePolicy;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.logging.DiagnosticLog;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 按精确标识复用模板，并在开局确认时原子登记世界素材、所选引擎规则和本局绑定。 */
@Component
public final class ConfirmedTemplateRegistry {
    private static final String RULE_PROPOSALS = "engine_template_registrations";
    private static final Set<String> MATERIAL_FIELDS = Set.of("id", "name", "description", "category");
    private static final int PROPOSAL_ATTEMPTS = 3;

    /** 将待提交的世界素材与通过校验的引擎规则索引作为一组返回。
     * @param world 本轮通过校验的世界素材
     * @param rules 本轮通过校验的规则索引，纯叙事时为空对象
     */
    private record TemplateDefinitions(ObjectNode world, ObjectNode rules) {}

    /** 表示所有模板提案均未通过校验，当前目录变更可以丢弃。 */
    private static final class ProposalAttemptsExhausted extends RuntimeException {}

    private final TemplateCatalog catalog;
    private final ObjectMapper mapper;
    private final NarrativeProvider provider;
    private final Map<String, EngineTemplatePolicy> policies;
    private final Path file;
    private final JsonNode reference;
    private ObjectNode registry;

    /** 加载叙事素材示例、已装配引擎的注册策略和已确认模板目录。
     * @param mapper JSON 编解码器
     * @param provider 生成缺失模板的模型提供器
     * @param catalog 内置与用户世界模板目录
     * @param enginePolicies 可参与模板注册的引擎策略
     * @param filename 模板注册目录的持久化文件路径
     */
    public ConfirmedTemplateRegistry(ObjectMapper mapper, NarrativeProvider provider, TemplateCatalog catalog,
            List<EngineTemplatePolicy> enginePolicies,
            @Value("${aigame.templates.registry:${aigame.content.root:./data}/confirmed-templates/registry.json}") String filename)
            throws IOException {
        this.catalog = catalog;
        this.mapper = mapper;
        this.provider = provider;
        this.policies = new HashMap<>();
        for (EngineTemplatePolicy policy : enginePolicies) {
            if (policies.putIfAbsent(policy.id(), policy) != null) {
                throw new IllegalArgumentException("引擎模板策略重复：" + policy.id());
            }
        }
        this.file = Path.of(filename).toAbsolutePath();
        try (InputStream stream = new ClassPathResource("world/templates/ai-template-reference.json").getInputStream()) {
            reference = mapper.readTree(stream).path("world_template");
        }
        registry = Files.exists(file) ? (ObjectNode) mapper.readTree(file.toFile()) : mapper.createObjectNode();
    }

    /** 查找或补齐本局模板，在全部校验成功后一次性提交变更。
     * @param gameId 本次确认的游戏标识
     * @param book 玩家完成构建的世界书，未指定模板时以世界类型作为模板标识
     * @param engineId 玩家选中的引擎标识，纯叙事时可为空
     * @return 已绑定模板时为 true，三次提案校验失败时为 false
     */
    public synchronized boolean confirm(String gameId, WorldbookDefinition book, String engineId) {
        String id = book.templateId().isBlank() ? book.type() : book.templateId();
        String engine = engineId == null ? "" : engineId.trim().toLowerCase(Locale.ROOT);
        JsonNode world = registry.path("worlds").path(id);
        if (!world.isMissingNode()) validateIdentity(world, id, book.type());

        ObjectNode binding = mapper.createObjectNode().put("template_id", id);
        if (!engine.isBlank()) binding.put("engine_id", engine);
        boolean ready = !world.isMissingNode()
                && (engine.isBlank() || world.path("registered_engines").path(engine).asBoolean());
        if (ready && binding.equals(registry.path("bindings").path(gameId))) return true;

        ObjectNode next = mapper.createObjectNode().setAll(registry);
        ObjectNode bindings = copyIndex(registry.path("bindings"));
        bindings.set(gameId, binding);
        next.set("bindings", bindings);
        if (!ready) {
            try {
                completeTemplate(next, book, id, engine, world);
            } catch (ProposalAttemptsExhausted exception) {
                DiagnosticLog.templateRegistrationSkipped(gameId, id, engine);
                return false;
            }
        }
        persist(next, id);
        return true;
    }

    /** 只为缺少世界素材或所选引擎资料的模板查目录和生成提案。
     * @param next 尚未提交的注册目录快照
     * @param book 当前游戏使用的世界书
     * @param id 精确匹配的世界模板标识
     * @param engine 所选引擎标识，纯叙事时为空串
     * @param existing 已登记的世界素材，首次开局时为缺失节点
     */
    private void completeTemplate(ObjectNode next, WorldbookDefinition book, String id, String engine, JsonNode existing) {
        EngineTemplatePolicy policy = policies.get(engine);
        if (!engine.isBlank() && policy == null) throw new IllegalArgumentException("引擎不支持模板注册：" + engine);

        WorldTemplate catalogTemplate = catalog.findTemplate(id).orElse(null);
        if (catalogTemplate != null && !book.type().equals(catalogTemplate.type())) {
            throw new IllegalArgumentException("世界模板类型不匹配：" + id);
        }
        JsonNode source = catalogTemplate == null ? MissingNode.getInstance() : catalogTemplate.materials();
        ObjectNode known = copyIndex(registry.path("rules").path(engine));
        if (policy != null) {
            if (known.isEmpty()) registerRules(policy, policy.defaults(), known);
            if (catalogTemplate != null) registerRules(policy, catalogTemplate.registrations().path(engine), known);
        }

        TemplateDefinitions definitions;
        if (existing.isMissingNode() && !source.has("regions")) {
            String instruction = "生成完整的 world_template，template_id 为 " + id + "，world_type 为 "
                    + book.type() + "，素材池结构参考：" + reference;
            if (policy != null) {
                instruction += "\n为每项需要绑定的素材添加 engines." + engine
                        + "，值为仅含 template_id 的对象，并提供所引用的缺失规则。";
            }
            definitions = generateValidated(book, id, policy, known, instruction,
                    proposal -> prepareGeneratedWorld(book, id, policy, known, proposal));
        } else {
            ObjectNode world = existing.isMissingNode() ? prepareWorld(source, id, book.type()) : existing.deepCopy();
            definitions = policy == null ? new TemplateDefinitions(world, known)
                    : completeEngine(book, id, policy, source, world, known, true);
        }

        ObjectNode result = definitions.world();
        if (policy != null) {
            result.withObject("/registered_engines").put(engine, true);
            ObjectNode rules = copyIndex(registry.path("rules"));
            rules.set(engine, definitions.rules());
            next.set("rules", rules);
        }
        ObjectNode worlds = copyIndex(registry.path("worlds"));
        worlds.set(id, result);
        next.set("worlds", worlds);
    }

    /** 在独立的规则索引中校验模型生成的完整世界和所选引擎资料。
     * @param book 提供预期世界类型和设定的世界书
     * @param id 预期的世界模板标识
     * @param policy 所选引擎的注册策略，纯叙事时为 null
     * @param known 本轮生成前已有且可复用的规则索引
     * @param proposal 模型返回的完整世界模板提案
     */
    private TemplateDefinitions prepareGeneratedWorld(WorldbookDefinition book, String id, EngineTemplatePolicy policy,
                                                      ObjectNode known, JsonNode proposal) {
        JsonNode source = proposal.path("world_template");
        ObjectNode world = prepareWorld(source, id, book.type());
        if (policy == null) return new TemplateDefinitions(world, known);
        ObjectNode proposedRules = copyIndex(known);
        registerProposedRules(policy, proposal, proposedRules);
        TemplateDefinitions definitions = completeEngine(book, id, policy, source, world, proposedRules, false);
        validateNewRuleReferences(policy, definitions.world(), known, definitions.rules());
        return definitions;
    }

    /** 校验待登记世界的身份和素材，并创建可接收所选引擎引用的副本。
     * @param source 模型或目录提供的世界模板
     * @param id 预期的模板标识
     * @param type 预期的世界类型
     */
    private ObjectNode prepareWorld(JsonNode source, String id, String type) {
        validateIdentity(source, id, type);
        ObjectNode world = source.deepCopy();
        world.remove("registered_engines");
        retainEngineReferences(world, "");
        validateMaterials(world);
        return world;
    }

    /** 沿用目录引用并生成仍缺少的引用或规则定义。
     * @param book 当前游戏使用的世界书
     * @param id 需要补齐引擎资料的世界模板标识
     * @param policy 所选引擎的注册策略
     * @param source 目录或首次生成的世界素材，可为缺失节点
     * @param world 待提交的世界素材
     * @param known 按精确标识索引的待提交规则
     * @param allowSupplement 目录或已登记的世界可请求补充，完整模型提案须当轮提供全部引用
     */
    private TemplateDefinitions completeEngine(WorldbookDefinition book, String id, EngineTemplatePolicy policy, JsonNode source,
                                               ObjectNode world, ObjectNode known, boolean allowSupplement) {
        String engine = policy.id();
        ObjectNode missingReferences = mapper.createObjectNode();
        Set<String> missingRules = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> group : source.properties()) {
            if (!group.getValue().isArray() || policy.referenceTypes().containsKey(group.getKey())) continue;
            for (JsonNode item : group.getValue()) {
                if (item.path("engines").has(engine)) {
                    throw new IllegalArgumentException("引擎不支持该素材池的引用：" + group.getKey());
                }
            }
        }
        for (String group : policy.referenceTypes().keySet()) {
            Map<String, JsonNode> sourceReferences = new HashMap<>();
            for (JsonNode entry : source.path(group)) {
                JsonNode engines = entry.path("engines");
                if (!engines.isMissingNode() && !engines.isObject()) {
                    throw new IllegalArgumentException(group + "." + entry.path("id").asText() + ".engines 必须是对象");
                }
                JsonNode ref = engines.path(engine);
                if (!ref.isMissingNode()) sourceReferences.put(entry.path("id").asText(), ref);
            }
            for (JsonNode entry : world.path(group)) {
                ObjectNode item = (ObjectNode) entry;
                String itemId = item.path("id").asText();
                JsonNode ref = sourceReferences.get(itemId);
                if (ref == null) {
                    missingReferences.withObjectProperty(group).put(itemId, item.path("name").asText());
                } else {
                    String ruleId = referenceId(ref, group, itemId);
                    if (known.has(ruleId)) policy.validateReference(group, itemId, known.path(ruleId));
                    else missingRules.add(ruleId);
                    item.withObject("/engines").set(engine, ref);
                }
            }
        }

        if (allowSupplement && (!missingReferences.isEmpty() || !missingRules.isEmpty())) {
            String instruction = "补齐现有世界素材的引擎资料。缺失引用（按素材池列出素材 ID 和名称）："
                    + missingReferences + "。在 engine_references 中按素材池和素材 ID 返回对应的 {template_id} 对象。"
                    + "\n缺少定义的规则 ID：" + mapper.valueToTree(missingRules)
                    + "。以原 ID 提供这些定义，并提供新引用所需的缺失规则。"
                    + "\n已有世界素材：" + world;
            return generateValidated(book, id, policy, known, instruction,
                    proposal -> applySupplement(policy, world, known, missingReferences, proposal));
        }
        validateEngineReferences(policy, world, known);
        return new TemplateDefinitions(world, known);
    }

    /** 在独立的素材和规则副本上应用并校验本轮引擎补充提案。
     * @param policy 所选引擎的注册策略
     * @param world 已保留目录引用的基础素材
     * @param known 本轮补充前已有且可复用的规则索引
     * @param missingReferences 按素材池和标识索引的待补齐引用
     * @param proposal 模型返回的引擎补充提案
     */
    private TemplateDefinitions applySupplement(EngineTemplatePolicy policy, ObjectNode world, ObjectNode known,
                                                ObjectNode missingReferences, JsonNode proposal) {
        ObjectNode proposedWorld = world.deepCopy();
        ObjectNode proposedRules = copyIndex(known);
        registerProposedRules(policy, proposal, proposedRules);
        JsonNode references = proposal.path("engine_references");
        validateSupplement(references, missingReferences);
        for (String group : policy.referenceTypes().keySet()) {
            for (JsonNode entry : proposedWorld.path(group)) {
                String itemId = entry.path("id").asText();
                if (missingReferences.path(group).has(itemId)) {
                    ((ObjectNode) entry).withObject("/engines").set(policy.id(), references.path(group).path(itemId));
                }
            }
        }
        validateEngineReferences(policy, proposedWorld, proposedRules);
        validateNewRuleReferences(policy, proposedWorld, known, proposedRules);
        return new TemplateDefinitions(proposedWorld, proposedRules);
    }

    /** 拒绝模型为当前世界新增未被素材引用的规则，避免占用其他模板的规则标识。
     * @param policy 所选引擎的注册策略
     * @param world 已通过引擎引用校验的待登记世界素材
     * @param known 本轮提案前已可用的规则索引，包含预置和目录规则
     * @param proposedRules 合入本轮模型提案后的规则索引
     */
    private static void validateNewRuleReferences(EngineTemplatePolicy policy, ObjectNode world, ObjectNode known,
                                                 ObjectNode proposedRules) {
        Set<String> referenced = new HashSet<>();
        for (String group : policy.referenceTypes().keySet()) {
            for (JsonNode item : world.path(group)) {
                referenced.add(item.path("engines").path(policy.id()).path("template_id").asText());
            }
        }
        for (Map.Entry<String, JsonNode> rule : proposedRules.properties()) {
            String ruleId = rule.getKey();
            if (!known.has(ruleId) && !referenced.contains(ruleId)) {
                throw new IllegalArgumentException("本轮新增的引擎规则未被世界素材引用：" + ruleId);
            }
        }
    }

    /** 校验素材引用均指向所选引擎中类别正确的规则。
     * @param policy 所选引擎的注册策略
     * @param world 本轮待校验的世界素材
     * @param known 当前可引用的规则索引
     */
    private static void validateEngineReferences(EngineTemplatePolicy policy, ObjectNode world, ObjectNode known) {
        for (String group : policy.referenceTypes().keySet()) {
            for (JsonNode entry : world.path(group)) {
                String itemId = entry.path("id").asText();
                String ruleId = referenceId(entry.path("engines").path(policy.id()), group, itemId);
                if (!known.has(ruleId)) {
                    throw new IllegalArgumentException(group + "." + itemId + " 引用了未定义的引擎规则 " + ruleId);
                }
                policy.validateReference(group, itemId, known.path(ruleId));
            }
        }
    }

    /** 确认引擎提案仅包含待补齐的素材引用。
     * @param references 模型返回的引用补充对象，未提供时为缺失节点
     * @param missingReferences 按素材池和标识索引的待补齐素材
     */
    private static void validateSupplement(JsonNode references, ObjectNode missingReferences) {
        if (references.isMissingNode() && missingReferences.isEmpty()) return;
        if (!references.isObject() || references.size() != missingReferences.size()) {
            throw new IllegalArgumentException("引擎引用提案与待补齐的素材池不匹配");
        }
        for (Map.Entry<String, JsonNode> group : missingReferences.properties()) {
            JsonNode proposed = references.path(group.getKey());
            if (!proposed.isObject() || proposed.size() != group.getValue().size()) {
                throw new IllegalArgumentException("引擎引用提案与待补齐的素材不匹配：" + group.getKey());
            }
        }
    }

    /** 生成并校验完整提案，最多尝试三次并将上一轮的提案错误反馈给模型。
     * @param book 提供世界类型及叙事设定的世界书
     * @param id 当前世界模板稳定标识
     * @param policy 所选引擎的注册策略，纯叙事时为 null
     * @param known 可供模型按标识复用的引擎规则索引
     * @param task 生成世界模板或引擎补充的原始任务指令
     * @param validate 在独立的临时数据上校验整份提案并返回结果的函数
     */
    private TemplateDefinitions generateValidated(WorldbookDefinition book, String id, EngineTemplatePolicy policy,
                                                  ObjectNode known, String task,
                                                  Function<JsonNode, TemplateDefinitions> validate) {
        String feedback = "";
        int remainingAttempts = PROPOSAL_ATTEMPTS;
        while (true) {
            NarrativeResponse response = requestProposal(book, id, policy, known, task + feedback);
            String stage = "parse_proposal";
            try {
                JsonNode proposal = readProposal(response);
                stage = "validate_proposal";
                return validate.apply(proposal);
            } catch (IllegalArgumentException exception) {
                --remainingAttempts;
                DiagnosticLog.templateProposalRejected(id, PROPOSAL_ATTEMPTS - remainingAttempts, stage, exception);
                if (remainingAttempts == 0) throw new ProposalAttemptsExhausted();
                feedback = "\n上一次提案未通过校验。原因：" + exception.getMessage() + "。请修正后重新返回完整提案。";
            }
        }
    }

    /** 检查世界模板的标识和类型是否与当前世界书匹配。
     * @param template 提案或已登记的世界模板 JSON 对象
     * @param id 预期的模板标识
     * @param type 预期的世界类型标识
     */
    private static void validateIdentity(JsonNode template, String id, String type) {
        if (!template.isObject()) {
            throw new IllegalArgumentException("world_template 缺失或不是 JSON 对象，期望模板标识为 " + id);
        }
        JsonNode actualId = template.path("template_id");
        JsonNode actualType = template.path("world_type");
        if (!actualId.isTextual() || !id.equals(actualId.asText())
                || !actualType.isTextual() || !type.equals(actualType.asText())) {
            throw new IllegalArgumentException("世界模板标识或类型不匹配，期望 template_id=" + id + "、world_type=" + type
                    + "，实际 template_id=" + identityValue(actualId) + "、world_type=" + identityValue(actualType));
        }
    }

    /** 将模型返回的身份字段转为有限长度的错误说明。
     * @param value 返回的身份字段节点，字段缺失时为缺失节点
     */
    private static String identityValue(JsonNode value) {
        if (value.isMissingNode()) return "缺失";
        if (!value.isTextual()) return "非字符串";
        String text = value.asText();
        return text.length() <= 80 ? value.toString() : text.substring(0, 80) + "…";
    }

    /** 检查各叙事素材集合及其必填标识。
     * @param world 待注册的世界模板
     */
    private void validateMaterials(ObjectNode world) {
        validateOptionalText(world, "name", "world_template");
        validateOptionalText(world, "description", "world_template");
        JsonNode tags = world.path("tags");
        if (!tags.isMissingNode()) {
            if (!tags.isArray()) throw new IllegalArgumentException("world_template.tags 必须是字符串数组");
            for (int index = 0; index < tags.size(); index++) {
                if (!tags.get(index).isTextual()) {
                    throw new IllegalArgumentException("world_template.tags[" + index + "] 必须是字符串");
                }
            }
        }
        for (Map.Entry<String, JsonNode> field : reference.properties()) {
            if (!field.getValue().isArray()) continue;
            JsonNode entries = world.path(field.getKey());
            if (!entries.isArray()) throw new IllegalArgumentException("world_template." + field.getKey() + " 必须是数组");
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < entries.size(); index++) {
                JsonNode entry = entries.get(index);
                String path = "world_template." + field.getKey() + "[" + index + "]";
                String id = entry.path("id").asText();
                if (!validId(entry.path("id"))) {
                    throw new IllegalArgumentException(path + ".id 必须是由字母、数字、下划线或连字符组成的 1 至 80 字符字符串");
                }
                if (!ids.add(id)) throw new IllegalArgumentException(path + ".id 重复：" + id);
                JsonNode name = entry.path("name");
                if (!name.isTextual() || name.asText().isBlank()) {
                    throw new IllegalArgumentException(path + ".name 必须是非空字符串");
                }
                for (Map.Entry<String, JsonNode> property : entry.properties()) {
                    if (!MATERIAL_FIELDS.contains(property.getKey())) {
                        throw new IllegalArgumentException(path + "." + property.getKey() + " 不属于世界叙事素材字段");
                    }
                }
                validateOptionalText(entry, "description", path);
                validateOptionalText(entry, "category", path);
            }
        }
    }

    /** 校验提案中已经声明的可选叙事字段使用字符串。
     * @param object 包含叙事字段的世界或素材对象
     * @param field 待校验的字段名
     * @param path 包含该字段的对象路径，用于定位错误
     */
    private static void validateOptionalText(JsonNode object, String field, String path) {
        JsonNode value = object.path(field);
        if (!value.isMissingNode() && !value.isTextual()) {
            throw new IllegalArgumentException(path + "." + field + " 必须是字符串");
        }
    }

    /** 请求模型返回包含世界模板或引擎补充提案的响应。
     * @param book 提供最终世界设定的世界书
     * @param id 当前世界模板标识
     * @param policy 所选引擎策略，纯叙事时为 null
     * @param known 所选引擎已可用的规则索引，纯叙事时为空对象
     * @param task 当前缺失内容及预期返回字段
     */
    private NarrativeResponse requestProposal(WorldbookDefinition book, String id, EngineTemplatePolicy policy,
                                              ObjectNode known, String task) {
        String instruction = "在 updates.template 中以 text 类型返回 JSON 对象字符串。当前世界模板标识为 "
                + id + "。\n" + task;
        if (policy == null) {
            instruction += "\n仅生成叙事素材。";
        } else {
            instruction += "\n所选引擎：" + policy.id() + "。素材池对应的实体类型：" + policy.referenceTypes()
                    + "。优先按 template_id 复用已有规则。只在 " + RULE_PROPOSALS + "." + policy.id()
                    + " 数组中提交当前世界素材引用但尚未定义的规则，规则类型和参数字段须与该类型的预置规则一致。"
                    + "\n可复用的规则：" + known;
        }
        instruction += "\n各素材项只提供 id、name、description、category 中适用的字段；引擎引用放在 engines，规则数值放在引擎提案中。";
        NarrativeSnapshot snapshot = new NarrativeSnapshot(new NarrativeSnapshot.WorldView(1, "", "", "",
                List.of(), List.of(), List.of()), List.of()).withWorldbook(book);
        return provider.respond(new NarrativePrompt(instruction, book.toString(), snapshot, "zh-CN"));
    }

    /** 解析模型声明的模板 JSON，拒绝缺失提案、重复键和额外的根值。
     * @param response 叙事模型响应，未收到响应时为 null
     */
    private JsonNode readProposal(NarrativeResponse response) {
        if (response == null || !(response.updates().get("template") instanceof InitializationValue.Text text)) {
            throw new IllegalArgumentException("updates.template 必须包含文本类型的模板 JSON");
        }
        try (JsonParser parser = mapper.getFactory().createParser(text.value())) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode proposal = mapper.readTree(parser);
            if (proposal == null || !proposal.isObject() || parser.nextToken() != null) {
                throw new IllegalArgumentException("模型必须返回单个模板 JSON 对象");
            }
            return proposal;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("updates.template 的 JSON 无效：" + exception.getOriginalMessage(), exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("模型返回的模板 JSON 无效", exception);
        }
    }

    /** 校验模型的引擎注册容器并登记所选引擎的规则。
     * @param policy 所选引擎的注册策略
     * @param proposal 模型返回的完整模板或引擎补充提案
     * @param known 本轮提案使用的临时规则索引
     */
    private void registerProposedRules(EngineTemplatePolicy policy, JsonNode proposal, ObjectNode known) {
        JsonNode registrations = proposal.path(RULE_PROPOSALS);
        if (!registrations.isMissingNode() && !registrations.isObject()) {
            throw new IllegalArgumentException(RULE_PROPOSALS + " 必须是按引擎标识索引的 JSON 对象");
        }
        registerRules(policy, registrations.path(policy.id()), known);
    }

    /** 按精确标识索引新规则，并交给引擎校验未登记的定义。
     * @param policy 所选引擎的模板注册策略
     * @param proposals 可选的新增规则数组，未提供时为缺失节点
     * @param known 本次提交的规则索引，同名已登记规则保留原定义
     */
    private void registerRules(EngineTemplatePolicy policy, JsonNode proposals, ObjectNode known) {
        if (proposals.isMissingNode()) return;
        String path = RULE_PROPOSALS + "." + policy.id();
        if (!proposals.isArray()) throw new IllegalArgumentException(path + " 必须是规则数组");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < proposals.size(); index++) {
            JsonNode rule = proposals.get(index);
            JsonNode id = rule.path("template_id");
            String idPath = path + "[" + index + "].template_id";
            if (!validId(id)) throw new IllegalArgumentException(idPath + " 必须是有效的规则标识");
            String ruleId = id.asText();
            if (!ids.add(ruleId)) throw new IllegalArgumentException(idPath + " 重复：" + ruleId);
            if (known.has(ruleId)) continue;
            policy.validateRule(rule);
            known.set(ruleId, rule);
        }
    }

    /** 从结构化素材引用中读取目标规则标识。
     * @param reference 只包含 template_id 的引用对象
     * @param group 素材池标识
     * @param itemId 提供错误定位的素材标识
     */
    private static String referenceId(JsonNode reference, String group, String itemId) {
        JsonNode ruleId = reference.path("template_id");
        if (!reference.isObject() || reference.size() != 1 || !validId(ruleId)) {
            throw new IllegalArgumentException(group + "." + itemId + " 缺少有效的引擎引用，须为仅含 template_id 的对象");
        }
        return ruleId.asText();
    }

    /** 检查 JSON 标识是否为允许的稳定文本值。
     * @param id 待检查的素材或规则标识节点
     */
    private static boolean validId(JsonNode id) {
        return id.isTextual() && id.asText().matches("[A-Za-z0-9_-]{1,80}");
    }

    /** 在独立的素材副本中保留本局所选引擎的引用。
     * @param world 可修改的世界素材副本
     * @param engine 本局选中的引擎标识，空串表示纯叙事
     */
    private static void retainEngineReferences(JsonNode world, String engine) {
        for (JsonNode field : world) {
            if (!field.isArray()) continue;
            for (JsonNode entry : field) {
                if (!(entry instanceof ObjectNode item)) continue;
                JsonNode ref = item.path("engines").path(engine);
                item.remove("engines");
                if (!engine.isBlank() && !ref.isMissingNode()) item.putObject("engines").set(engine, ref);
            }
        }
    }

    /** 浅拷贝 JSON 索引以添加键值，已存在的条目按不可变信息共享。
     * @param source 可为缺失节点的 JSON 索引
     */
    private ObjectNode copyIndex(JsonNode source) {
        ObjectNode copy = mapper.createObjectNode();
        if (source instanceof ObjectNode index) copy.setAll(index);
        return copy;
    }

    /** 原子替换磁盘目录并发布内存快照。
     * @param next 已通过全部校验的注册目录
     * @param templateId 本次写入的模板标识
     */
    private void persist(ObjectNode next, String templateId) {
        Path temporary = null;
        String stage = "create_directory";
        try {
            Files.createDirectories(file.getParent());
            stage = "write_temp";
            temporary = Files.createTempFile(file.getParent(), "register-", ".tmp");
            mapper.writeValue(temporary.toFile(), next);
            stage = "replace_file";
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            registry = next;
        } catch (IOException exception) {
            TemporaryFiles.deleteAfterFailure(temporary, exception);
            throw new GamePersistenceException("无法保存模板注册目录", exception,
                    "template_registry", templateId, "write", stage);
        }
    }

    /** 返回本局确认的叙事素材和所选引擎引用。
     * @param gameId 游戏存档标识
     */
    public synchronized JsonNode world(String gameId) {
        JsonNode binding = registry.path("bindings").path(gameId);
        JsonNode world = registry.path("worlds").path(binding.path("template_id").asText());
        if (!(world instanceof ObjectNode registered)) return world;
        String engine = binding.path("engine_id").asText();
        ObjectNode view = registered.deepCopy();
        retainEngineReferences(view, engine);
        view.remove("registered_engines");
        if (!engine.isBlank()) view.putObject("registered_engines").put(engine, true);
        return view;
    }

    /** 返回指定引擎可复用的规则目录副本。
     * @param engine 引擎稳定标识
     */
    public synchronized JsonNode rules(String engine) {
        return registry.path("rules").path(engine).deepCopy();
    }
}
