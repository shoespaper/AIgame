package com.gameneko.aigame.content;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.persistence.FileTemplateRepository;
import com.gameneko.aigame.persistence.FileWorldbookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 读取并校验内置及用户世界书，并通过模板目录提供机制骨架。 */
@Component
public final class ClasspathContentCatalog implements ContentCatalog {
    private static final Map<String, String> WORLD_RESOURCES = Map.of(
            "cinder_continent", "classpath:world/worldbooks/cinder_continent.json",
            "isekai_grey", "classpath:world/worldbooks/cinder_continent.json");
    private static final Map<String, String> TEMPLATE_RESOURCES = Map.of(
            "isekai", "classpath:world/templates/isekai.json");
    private final ObjectMapper objectMapper;
    private final ResourceLoader resources;
    private final FileTemplateRepository templateFiles;
    private final FileWorldbookRepository worldbookFiles;
    private final Map<String, WorldDefinition> cache = new ConcurrentHashMap<>();
    private final Map<String, WorldTemplate> templateCache = new ConcurrentHashMap<>();

    /** 创建世界内容目录。
     * @param objectMapper JSON 解析器
     * @param resources classpath 和本地文件资源读取器
     */
    public ClasspathContentCatalog(ObjectMapper objectMapper, ResourceLoader resources) {
        this(objectMapper, resources, new FileWorldbookRepository(objectMapper), new FileTemplateRepository(objectMapper));
    }

    /** 使用可配置仓储创建内容目录。
     * @param objectMapper JSON 解析器
     * @param resources 内置资源读取器
     * @param worldbookFiles 用户世界书仓储
     * @param templateFiles 用户模板仓储
     */
    @Autowired
    public ClasspathContentCatalog(ObjectMapper objectMapper, ResourceLoader resources,
                                    FileWorldbookRepository worldbookFiles, FileTemplateRepository templateFiles) {
        this.objectMapper = objectMapper;
        this.resources = resources;
        this.worldbookFiles = worldbookFiles;
        this.templateFiles = templateFiles;
    }

    /** 返回可复用的 classpath 世界模板。
     * @return 内置模板目录
     */
    @Override
    public List<WorldTemplate> availableTemplates() {
        List<String> ids = new ArrayList<>(TEMPLATE_RESOURCES.keySet());
        templateFiles.available().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        return ids.stream().sorted().map(this::template).toList();
    }

    /** 按精确标识读取一个内置或用户世界模板。
     * @param templateId 模板稳定标识
     * @return 匹配的模板，标识无效或模板不存在时为空
     */
    @Override
    public Optional<WorldTemplate> findTemplate(String templateId) {
        if (templateId == null || !templateId.matches("[A-Za-z0-9_-]+")) return Optional.empty();
        return Optional.ofNullable(templateCache.computeIfAbsent(templateId, this::loadTemplate));
    }

    @Override
    /** 返回当前 classpath 中已注册的世界书。 */
    public List<WorldBook> available() {
        List<String> ids = new ArrayList<>();
        ids.add("cinder_continent");
        worldbookFiles.available().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        return ids.stream().sorted().map(this::worldBook).toList();
    }

    /** 使指定世界书的缓存投影失效。
     * @param worldId 世界书标识
     */
    @Override
    public void invalidate(String worldId) {
        cache.remove(worldId);
    }

    /** 按标识读取组合投影并兼容旧存档的世界书别名。
     * @param worldId 世界书稳定标识或 isekai_grey 旧别名
     * @return 世界书与模板的兼容投影
     */
    @Override
    public WorldDefinition world(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            throw new WorldContentException("worldId must not be blank");
        }
        if (!worldId.matches("[A-Za-z0-9_-]+")) throw new WorldContentException("Invalid worldId: " + worldId);
        if (!WORLD_RESOURCES.containsKey(worldId) && !worldbookFiles.available().contains(worldId)) {
            throw new WorldContentException("Unsupported world: " + worldId);
        }
        return cache.computeIfAbsent(worldId, this::load);
    }

    /** 将叙事世界书和模板组合成兼容旧引擎的世界书包装。
     * @param worldId 世界书标识
     * @return 世界书及其初始化 schema
     */
    @Override
    public WorldBook worldBook(String worldId) {
        WorldDefinition definition = world(worldId);
        String narrativeId = "isekai_grey".equals(worldId) ? "cinder_continent" : worldId;
        WorldbookDefinition narrative = loadWorldbookData(narrativeId);
        return new WorldBook(definition, definition.initializationSchema(), narrative);
    }

    /** 按世界书引用的模板组合引擎兼容投影。
     * @param worldId 世界书标识或旧版别名
     * @return 叙事内容与模板骨架的合并投影
     */
    private WorldDefinition load(String worldId) {
        WorldbookDefinition book = loadWorldbookData("isekai_grey".equals(worldId) ? "cinder_continent" : worldId);
        return new WorldDefinition(worldId, book.contentVersion(), book.name(), book.descriptionHint(),
                7, Map.of(), Map.of(), Map.of());
    }

    /** 严格读取一个 JSON 对象并拒绝重复键和额外根节点。
     * @param location classpath 资源地址
     * @param id 用于错误定位的模板或世界书标识
     * @return JSON 根对象
     */
    private JsonNode readJson(String location, String id) {
        try (InputStream stream = resources.getResource(location).getInputStream();
             JsonParser parser = objectMapper.getFactory().createParser(stream)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) throw invalid(id, "definition must contain one JSON object");
            return root;
        } catch (IOException exception) {
            throw new WorldContentException("Unable to read content: " + id, exception);
        }
    }

    /** 读取完整用户模板或解析 classpath 内置模板。
     * @param templateId 模板稳定标识
     * @return 完整模板骨架，不存在时返回 null
     */
    private WorldTemplate loadTemplate(String templateId) {
        JsonNode root = templateFiles.readDocument(templateId);
        if (root != null) {
            try {
                WorldTemplate result = objectMapper.treeToValue(root, WorldTemplate.class);
                if (!templateId.equals(result.id())) throw invalid(templateId, "template identity mismatch");
                return result;
            } catch (IOException exception) {
                throw new WorldContentException("Invalid template: " + templateId, exception);
            }
        }
        String resource = TEMPLATE_RESOURCES.get(templateId);
        if (resource == null) return null;
        root = readJson(resource, templateId);
        JsonNode materials = root.path("world_template");
        JsonNode registrations = root.path("engine_template_registrations");
        return new WorldTemplate(templateId, materials.path("world_type").asText(), textArray(materials, "tags"),
                materials.path("name").asText(), materials,
                registrations.isMissingNode() ? objectMapper.createObjectNode() : registrations);
    }

    /** 从内置资源或用户仓储读取独立的世界书叙事内容。
     * @param worldbookId 世界书稳定标识，不包含旧版别名
     * @return 叙事世界书
     */
    private WorldbookDefinition loadWorldbookData(String worldbookId) {
        JsonNode root = WORLD_RESOURCES.containsKey(worldbookId)
                ? readJson(WORLD_RESOURCES.get(worldbookId), worldbookId) : worldbookFiles.readDocument(worldbookId);
        if (root == null || !root.isObject()) throw invalid(worldbookId, "worldbook is missing");
        if (!worldbookId.equals(root.path("_meta").path("world_name").asText())) throw invalid(worldbookId, "worldbook identity mismatch");
        JsonNode setting = object(root, "world_setting", worldbookId);
        List<Map<String, String>> factions = new ArrayList<>();
        JsonNode factionRoot = setting.get("factions");
        if (factionRoot != null && factionRoot.isArray()) for (JsonNode faction : factionRoot) {
            Map<String, String> values = new HashMap<>();
            faction.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
            factions.add(values);
        }
        return new WorldbookDefinition(worldbookId, textOr(root.path("_meta"), "version", "1.0"),
                textOr(setting, "world_name_hint", worldbookId), textOr(setting, "type", ""),
                textOr(setting, "template_id", ""), textOr(setting, "tone", "balanced"),
                textOr(setting, "world_desc_hint", ""), factions, textArray(setting, "origin_contexts"),
                textArray(setting, "world_tropes"), objectMap(setting.get("adventurer_guild")));
    }

    /** 保留对象节点中的嵌套字段和数值定义。
     * @param node 可空的 JSON 对象节点
     * @return 完整通用对象映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        return objectMapper.convertValue(node, Map.class);
    }

    private static JsonNode object(JsonNode parent, String field, String worldId) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw invalid(worldId, field + " must be an object");
        }
        return value;
    }

    private static String textOr(JsonNode parent, String field, String fallback) {
        if (parent == null) return fallback;
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static WorldContentException invalid(String worldId, String reason) {
        return new WorldContentException("Invalid world definition " + worldId + ": " + reason);
    }

    private static List<String> textArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) if (item.isTextual()) result.add(item.asText());
        return result;
    }

}
