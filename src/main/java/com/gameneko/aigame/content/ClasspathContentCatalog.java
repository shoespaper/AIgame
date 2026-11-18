package com.gameneko.aigame.content;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Validates only the portion of static world content needed by initialization. */
@Component
public final class ClasspathContentCatalog implements ContentCatalog {
    private static final Map<String, String> WORLD_RESOURCES = Map.of(
            "isekai_grey", "classpath:world/isekai_grey/world_lore.json");
    private final ObjectMapper objectMapper;
    private final ResourceLoader resources;
    private final Map<String, WorldDefinition> cache = new ConcurrentHashMap<>();

    public ClasspathContentCatalog(ObjectMapper objectMapper, ResourceLoader resources) {
        this.objectMapper = objectMapper;
        this.resources = resources;
    }

    @Override
    public WorldDefinition world(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            throw new WorldContentException("worldId must not be blank");
        }
        if (!WORLD_RESOURCES.containsKey(worldId)) {
            throw new WorldContentException("Unsupported world: " + worldId);
        }
        return cache.computeIfAbsent(worldId, this::load);
    }

    private WorldDefinition load(String worldId) {
        try (InputStream stream = resources.getResource(WORLD_RESOURCES.get(worldId)).getInputStream();
             JsonParser parser = objectMapper.getFactory().createParser(stream)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalid(worldId, "definition must contain one JSON object");
            }
            JsonNode metadata = object(root, "_meta", worldId);
            if (!worldId.equals(text(metadata, "world_name", worldId))) {
                throw invalid(worldId, "_meta.world_name does not match requested world");
            }
            String version = text(metadata, "version", worldId);
            if (!"1.0".equals(version)) {
                throw invalid(worldId, "unsupported content version: " + version);
            }
            JsonNode setting = object(root, "world_setting", worldId);
            JsonNode adventure = object(object(root, "difficulty_presets", worldId), "adventure", worldId);
            JsonNode interval = adventure.get("threat_interval");
            if (interval == null || !interval.isIntegralNumber() || !interval.canConvertToLong()
                    || interval.longValue() <= 0) {
                throw invalid(worldId, "adventure.threat_interval must be a positive integer");
            }
            JsonNode monsterTypes = object(root, "monster_types", worldId);
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
                        text(value, "label", worldId), text(value, "desc_hint", worldId)));
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
                            textOr(ability, "effect_hint", ""), textOr(ability, "description", "")));
                }
            }
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
                // An empty town pool is valid in the Python template.
                pools.put(field.getKey(), types);
            }
            return new WorldDefinition(worldId, version, text(setting, "world_name_hint", worldId),
                    text(setting, "world_desc_hint", worldId), interval.longValue(), pools, difficulties, talents, abilities);
        } catch (IOException exception) {
            throw new WorldContentException("Unable to read world definition: " + worldId, exception);
        }
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
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static int intOr(JsonNode parent, String field, int fallback) {
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
}
