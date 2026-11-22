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
import java.util.concurrent.ConcurrentHashMap;
import com.gameneko.aigame.engine.PriceModifier;
import com.gameneko.aigame.engine.WorldEffects;

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

    /** 读取一个 classpath 世界模板。
     * @param templateId 模板稳定标识
     * @return 模板骨架
     */
    @Override
    public WorldTemplate template(String templateId) {
        if (templateId == null || !templateId.matches("[A-Za-z0-9_-]+") ||
                (!TEMPLATE_RESOURCES.containsKey(templateId) && !templateFiles.available().contains(templateId))) {
            throw new WorldContentException("Unsupported template: " + templateId);
        }
        return templateCache.computeIfAbsent(templateId, this::loadTemplate);
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
        var book = loadWorldbookData("isekai_grey".equals(worldId) ? "cinder_continent" : worldId);
        WorldTemplate skeleton = book.templateId().isBlank() ? WorldTemplate.empty(book.type(), book.name()) : template(book.templateId());
        Map<String, List<String>> pools = new HashMap<>();
        skeleton.regions().forEach((id, region) -> pools.put(id, region.monsterTypePool()));
        return new WorldDefinition(worldId, book.contentVersion(), book.name(), book.descriptionHint(),
                skeleton.defaultThreatInterval(), pools, skeleton.difficulties(), skeleton.talents(), skeleton.abilities(),
                skeleton.monsters(), skeleton.npcTypes(), skeleton.eventTemplates(), skeleton.itemTemplates());
    }

    /** 解析内置模板的类型化机制内容，不读取任何世界书。
     * @param root 模板 JSON 根节点
     * @param worldId 模板标识，用于错误定位
     * @param version 模板内容版本
     * @return 类型化机制投影
     */
    private WorldDefinition parseMechanics(JsonNode root, String worldId, String version) {
        JsonNode adventure = object(object(root, "difficulty_presets", worldId), "adventure", worldId);
        JsonNode interval = adventure.get("threat_interval");
        if (interval == null || !interval.isIntegralNumber() || !interval.canConvertToLong()
                || interval.longValue() <= 0) {
            throw invalid(worldId, "adventure.threat_interval must be a positive integer");
        }
        JsonNode monsterTypes = object(root, "monster_types", worldId);
        Map<String, WorldDefinition.MonsterDefinition> monsters = parseMonsters(monsterTypes, worldId);
        Map<String, WorldDefinition.ItemTemplate> itemTemplates = parseItemTemplates(root.get("loot_types"), worldId);
        Map<String, WorldDefinition.DifficultyDefinition> difficulties = new HashMap<>();
        var difficultyFields = object(root, "difficulty_presets", worldId).fields();
        while (difficultyFields.hasNext()) {
            var field = difficultyFields.next();
            JsonNode value = field.getValue();
            difficulties.put(field.getKey(), new WorldDefinition.DifficultyDefinition(
                    positive(value, "threat_interval", worldId),
                    nonNegative(value, "starting_copper", worldId),
                    nonNegative(value, "starting_rations", worldId),
                    nonNegative(value, "starting_potions", worldId),
                    Math.toIntExact(integer(value, "hp_recover_bonus", worldId)),
                    text(value, "label", worldId), text(value, "desc_hint", worldId),
                    percent(value, "enemy_hp_mult"), percent(value, "enemy_power_mult"),
                    percent(value, "enemy_ap_mult"), percent(value, "enemy_mit_mult"),
                    percent(value, "xp_mult"), percent(value, "gold_mult"), intOr(value, "loot_lucky_bonus", 0)));
        }
        Map<String, WorldDefinition.TalentDefinition> talents = new HashMap<>();
        JsonNode talentRoot = object(root, "talent_types", worldId);
        var talentFields = talentRoot.fields();
        while (talentFields.hasNext()) {
            var field = talentFields.next();
            talents.put(field.getKey(), new WorldDefinition.TalentDefinition(
                    text(field.getValue(), "name", worldId), text(field.getValue(), "description", worldId)));
        }
        Map<String, WorldDefinition.AbilityDefinition> abilities = new HashMap<>();
        JsonNode abilitySlots = object(root, "ability_slots", worldId);
        var abilityKinds = abilitySlots.fields();
        while (abilityKinds.hasNext()) {
            var kindEntry = abilityKinds.next();
            if (!kindEntry.getValue().isArray()) continue;
            for (JsonNode ability : kindEntry.getValue()) {
                if (!ability.isObject() || !ability.hasNonNull("id") || !ability.hasNonNull("name")) continue;
                Map<String, Integer> prerequisites = new HashMap<>();
                JsonNode prereq = ability.get("prereq");
                if (prereq != null && prereq.isObject()) {
                    var prereqFields = prereq.fields();
                    while (prereqFields.hasNext()) {
                        var entry = prereqFields.next();
                        if (!entry.getValue().isIntegralNumber()) {
                            throw invalid(worldId, "ability prerequisite must be an integer: " + entry.getKey());
                        }
                        prerequisites.put(entry.getKey(), entry.getValue().intValue());
                    }
                }
                String kind = textOr(ability, "kind", kindEntry.getKey());
                abilities.put(ability.get("id").asText(), new WorldDefinition.AbilityDefinition(
                        ability.get("id").asText(), kind, intOr(ability, "tier", 1),
                        intOr(ability, "ap_cost", 0), intOr(ability, "sp_cost", 0),
                        intOr(ability, "power", 0), prerequisites, ability.get("name").asText(),
                        textOr(ability, "effect_hint", ""), textOr(ability, "description", ""),
                        parseAbilityMechanic(ability.get("mechanic"))));
            }
        }
        Map<String, WorldDefinition.NpcTemplate> npcTypes = parseNpcTypes(root.get("npc_types"));
        WorldDefinition.EventTemplates eventTemplates = parseEvents(root.get("event_templates"));
        JsonNode regions = object(root, "regions", worldId);
        if (regions.isEmpty()) {
            throw invalid(worldId, "regions must not be empty");
        }
        Map<String, List<String>> pools = new HashMap<>();
        var fields = regions.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().isBlank() || !field.getValue().isObject()) {
                throw invalid(worldId, "invalid region: " + field.getKey());
            }
            JsonNode pool = field.getValue().get("monster_type_pool");
            if (pool == null || !pool.isArray()) {
                throw invalid(worldId, "region monster_type_pool must be an array: " + field.getKey());
            }
            List<String> types = new ArrayList<>();
            for (JsonNode type : pool) {
                if (!type.isTextual() || !monsterTypes.path(type.asText()).isObject()) {
                    throw invalid(worldId, "unknown monster type in region: " + field.getKey());
                }
                types.add(type.asText());
            }
            // 城镇的原生怪物池允许为空。
            pools.put(field.getKey(), types);
        }
        return new WorldDefinition(worldId, version, worldId,
                "", interval.longValue(), pools, difficulties, talents,
                abilities, monsters, npcTypes, eventTemplates, itemTemplates);
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
     * @return 完整模板骨架
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
        root = readJson(TEMPLATE_RESOURCES.get(templateId), templateId);
        WorldDefinition definition = parseMechanics(root, templateId, text(root.path("_meta"), "version", templateId));
        Map<String, WorldTemplate.RegionDefinition> regions = new HashMap<>();
        object(root, "regions", templateId).fields().forEachRemaining(entry -> regions.put(entry.getKey(),
                new WorldTemplate.RegionDefinition(textOr(entry.getValue(), "desc_hint", ""), textArray(entry.getValue(), "monster_type_pool"))));
        return new WorldTemplate(templateId, text(root.path("_meta"), "type", templateId),
                textArray(root.path("_meta"), "tags"), text(root.path("_meta"), "name", templateId),
                definition.contentVersion(), definition.defaultThreatInterval(), regions,
                definition.difficulties(), definition.talents(), definition.abilities(), definition.monsters(),
                definition.npcTypes(), definition.eventTemplates(), definition.itemTemplates(),
                objectMap(root.get("skill_types")), objectMap(root.get("spell_types")), objectList(root.get("quest_templates")),
                objectList(root.get("quest_features")), objectMap(root.get("modifier_pool")), objectMap(root.get("ability_slots")), Map.of());
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

    /** 保留数组节点中的结构化条目。
     * @param node 可空的 JSON 数组节点
     * @return 完整通用条目列表
     */
    @SuppressWarnings("unchecked")
    private List<Object> objectList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        return objectMapper.convertValue(node, List.class);
    }

    private static JsonNode object(JsonNode parent, String field, String worldId) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw invalid(worldId, field + " must be an object");
        }
        return value;
    }

    private static String text(JsonNode parent, String field, String worldId) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(worldId, field + " must be non-blank text");
        }
        return value.asText();
    }

    private static String textOr(JsonNode parent, String field, String fallback) {
        if (parent == null) return fallback;
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static int intOr(JsonNode parent, String field, int fallback) {
        if (parent == null) return fallback;
        JsonNode value = parent.get(field);
        return value != null && value.isIntegralNumber() ? value.intValue() : fallback;
    }

    private static long positive(JsonNode parent, String field, String worldId) {
        long value = integer(parent, field, worldId);
        if (value <= 0) throw invalid(worldId, field + " must be positive");
        return value;
    }

    private static long nonNegative(JsonNode parent, String field, String worldId) {
        long value = integer(parent, field, worldId);
        if (value < 0) throw invalid(worldId, field + " must not be negative");
        return value;
    }

    private static long integer(JsonNode parent, String field, String worldId) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(worldId, field + " must be an integer");
        }
        return value.longValue();
    }

    private static WorldContentException invalid(String worldId, String reason) {
        return new WorldContentException("Invalid world definition " + worldId + ": " + reason);
    }

    private static Map<String, WorldDefinition.MonsterDefinition> parseMonsters(JsonNode root, String worldId) {
        Map<String, WorldDefinition.MonsterDefinition> result = new HashMap<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            result.put(entry.getKey(), new WorldDefinition.MonsterDefinition(entry.getKey(),
                    range(value, "cr_range", worldId), range(value, "hp_range", worldId),
                    range(value, "power_range", worldId), range(value, "mit_range", worldId),
                    range(value, "speed_range", worldId), integer(value, "ap", worldId) > Integer.MAX_VALUE
                            ? Integer.MAX_VALUE : Math.toIntExact(integer(value, "ap", worldId)),
                    range(value, "xp_range", worldId), range(value, "loot_gold_range", worldId),
                    textOr(value, "behavior", "aggressive"), textArray(value, "ability_pool"),
                    value.path("speed_mult").isNumber() ? value.path("speed_mult").doubleValue() : 1.0));
        }
        return result;
    }

    private static WorldDefinition.IntRange range(JsonNode parent, String field, String worldId) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray() || value.size() != 2
                || !value.get(0).isIntegralNumber() || !value.get(1).isIntegralNumber()) {
            throw invalid(worldId, field + " must contain two integers");
        }
        return new WorldDefinition.IntRange(Math.toIntExact(value.get(0).longValue()),
                Math.toIntExact(value.get(1).longValue()));
    }

    private static List<String> textArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) if (item.isTextual()) result.add(item.asText());
        return result;
    }

    /** 将已经定位到的 JSON 数组转换为文本列表。
     * @param value JSON 数组节点
     * @return 文本值列表
     */
    private static List<String> textArray(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) if (item.isTextual()) result.add(item.asText());
        return result;
    }

    private static Map<String, WorldDefinition.NpcTemplate> parseNpcTypes(JsonNode root) {
        if (root == null || !root.isObject()) return Map.of();
        Map<String, WorldDefinition.NpcTemplate> result = new HashMap<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            List<WorldDefinition.BondDefinition> bonds = new ArrayList<>();
            JsonNode bondRoot = value.get("bond_pool");
            if (bondRoot != null && bondRoot.isArray()) {
                for (JsonNode bond : bondRoot) {
                    if (bond.isObject()) bonds.add(new WorldDefinition.BondDefinition(
                            textOr(bond, "label", ""), intOr(bond, "min", 0), textOr(bond, "desc", "")));
                }
            }
            result.put(entry.getKey(), new WorldDefinition.NpcTemplate(entry.getKey(),
                    textArray(value, "personality_pool"), bonds, textOr(value, "schedule_template", "self_directed"),
                    intOr(value, "relationship_base", 0), value.path("can_train").asBoolean(false),
                    value.path("can_combat").asBoolean(false), value.path("sells_items").asBoolean(false),
                    value.path("gives_quests").asBoolean(false), value.path("is_enemy").asBoolean(false)));
        }
        return result;
    }

    private static WorldDefinition.EventTemplates parseEvents(JsonNode root) {
        if (root == null || !root.isObject()) return WorldDefinition.EventTemplates.empty();
        return new WorldDefinition.EventTemplates(parseEventList(root.get("timed")),
                parseEventList(root.get("incidental")));
    }

    private static List<WorldDefinition.EventTemplate> parseEventList(JsonNode root) {
        if (root == null || !root.isArray()) return List.of();
        List<WorldDefinition.EventTemplate> result = new ArrayList<>();
        for (JsonNode value : root) {
            if (!value.isObject()) continue;
            result.add(new WorldDefinition.EventTemplate(textOr(value, "type", "other"),
                    textOr(value, "type", "other"), textOr(value, "category", "other"),
                    intOr(value, "weight", 1), value.path("trigger").path("repeating").asBoolean(false), intOr(value, "level", 0),
                    parseEffects(value.get("effects")), textOr(value, "desc_hint", "")));
        }
        return result;
    }

    private static WorldEffects parseEffects(JsonNode node) {
        if (node == null || !node.isObject()) return WorldEffects.none();
        List<PriceModifier> prices = new ArrayList<>();
        JsonNode price = node.get("price_mod");
        if (price != null && price.isObject()) {
            price.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNumber()) {
                    prices.add(new PriceModifier(PriceModifier.category(entry.getKey()),
                            Math.toIntExact(Math.round(entry.getValue().doubleValue() * 100))));
                }
            });
        }
        int mood = node.path("npc_mood_shift").isIntegralNumber() ? node.path("npc_mood_shift").intValue() : 0;
        int rations = node.path("rations_bonus").isIntegralNumber() ? node.path("rations_bonus").intValue() : 0;
        int questChance = node.path("quest_hook_chance").isNumber()
                ? Math.toIntExact(Math.round(node.path("quest_hook_chance").doubleValue() * 100)) : 0;
        int rumorChance = node.path("rumor_chance").isNumber()
                ? Math.toIntExact(Math.round(node.path("rumor_chance").doubleValue() * 100)) : 0;
        JsonNode outdoor = node.get("outdoor_combat_mod");
        int speed = outdoor == null ? 0 : outdoor.path("speed").asInt(0);
        List<String> additions = new ArrayList<>();
        if (node.path("npc_pool_add").isTextual()) additions.add(node.path("npc_pool_add").asText());
        return new WorldEffects(prices, node.path("threat_counter").asInt(0), mood,
                node.path("travel_penalty").asBoolean(false), speed, additions,
                node.path("shop_refresh").asBoolean(false), node.path("spawn_threat").asBoolean(false),
                rations, questChance, rumorChance, node.path("game_over_risk").asBoolean(false),
                node.path("custom").asBoolean(false));
    }

    private static Map<String, WorldDefinition.ItemTemplate> parseItemTemplates(JsonNode root, String worldId) {
        if (root == null || !root.isObject()) return Map.of();
        Map<String, WorldDefinition.ItemTemplate> result = new HashMap<>();
        var categories = root.fields();
        while (categories.hasNext()) {
            var category = categories.next();
            if (!category.getValue().isObject()) continue;
            var tiers = category.getValue().fields();
            while (tiers.hasNext()) {
                var tier = tiers.next();
                JsonNode value = tier.getValue();
                JsonNode effect = value.path("effect");
                result.put(category.getKey() + ":" + tier.getKey(), new WorldDefinition.ItemTemplate(
                        category.getKey(), tier.getKey(), range(value, "value_range", worldId),
                        Math.max(0, effect.path("heal").asInt(0)), Math.max(0, effect.path("sp_restore").asInt(0)),
                        effect.path("clear_statuses").asBoolean(false), Math.max(0, effect.path("damage_bonus").asInt(0)),
                        Math.max(0, effect.path("mitigation_bonus").asInt(0))));
            }
        }
        return result;
    }

    private static int percent(JsonNode parent, String field) {
        if (parent == null) return 100;
        JsonNode value = parent.get(field);
        return value != null && value.isNumber()
                ? Math.toIntExact(Math.round(value.doubleValue() * 100)) : 100;
    }

    private static WorldDefinition.AbilityMechanic parseAbilityMechanic(JsonNode node) {
        if (node == null || !node.isObject()) return WorldDefinition.AbilityMechanic.none();
        WorldDefinition.AbilityMechanic.Category category;
        try {
            category = WorldDefinition.AbilityMechanic.Category.valueOf(
                    textOr(node, "category", "none").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            category = WorldDefinition.AbilityMechanic.Category.NONE;
        }
        JsonNode debuff = node.get("debuff");
        return new WorldDefinition.AbilityMechanic(category,
                textOr(node, "effect", textOr(node, "dot_effect", textOr(debuff, "effect", ""))),
                intOr(node, "amount", intOr(node, "dot_amount", intOr(debuff, "amount", 0))),
                intOr(node, "duration", intOr(debuff, "duration", intOr(node, "dot_duration", 0))),
                intOr(node, "power", 0), percent(node, "power_mult"),
                intOr(node, "heal_amount", 0), textOr(node, "buff_stat", ""),
                intOr(node, "buff_amount", 0), Math.max(0, intOr(node, "targets", 0)),
                intOr(node, "extra_per_turn", 0), intOr(node, "cost_penalty", 0),
                percent(node, "counter_power_mult"),
                node.path("once_per_combat").asBoolean(false),
                node.path("clear_statuses").asBoolean(false), textOr(node, "kind", ""));
    }
}
