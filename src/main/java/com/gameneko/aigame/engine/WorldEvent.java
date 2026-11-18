package com.gameneko.aigame.engine;

/** A confirmed world occurrence. Effects are descriptive keys resolved by the world rules. */
public record WorldEvent(String id, long day, Type type, Category category,
                         String description, WorldEffects effects) {
    public enum Type {
        MARKET, DROUGHT, SUPPLY_SALE, BEAST_MIGRATION, RUMOR, SUPPLY_DISRUPTION,
        THREAT_ESCALATION, PILGRIM, OMEN, DEADLINE, WILDLIFE, STRANGER, STORM,
        FUGITIVE, FESTIVAL, MIGRATION, CRISIS, OTHER
    }
    public enum Category { ECONOMIC, SOCIAL, ENVIRONMENTAL, SUPERNATURAL, WORLD_THREAT, PLOT, OTHER }

    public WorldEvent {
        id = id == null ? "" : id;
        type = type == null ? Type.OTHER : type;
        category = category == null ? Category.OTHER : category;
        description = description == null ? "" : description;
        effects = effects == null ? WorldEffects.none() : effects;
    }

    public WorldEvent(long day, Type type, Category category, String description) {
        this(type + "-" + day, day, type, category, description, WorldEffects.none());
    }

    public WorldEvent(String id, long day, String type, String category, String description) {
        this(id, day, parseType(type), parseCategory(category), description, WorldEffects.none());
    }

    private static Type parseType(String value) {
        try { return Type.valueOf(value == null ? "OTHER" : value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return Type.OTHER; }
    }

    private static Category parseCategory(String value) {
        try { return Category.valueOf(value == null ? "OTHER" : value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return Category.OTHER; }
    }
}
