package com.gameneko.aigame.engine;

/** Integer price multiplier in percent. 100 means the base price. */
public record PriceModifier(Category category, int percent, String source, long expiresDay) {
    public enum Category {
        ALL, FOOD, HEALING, WEAPON, ARMOR, LODGING, HOUSING, MATERIAL, POTION, WATER, RARE_GOODS, OTHER
    }

    public PriceModifier {
        if (category == null) throw new IllegalArgumentException("Price category is required");
        if (percent < 0) throw new IllegalArgumentException("Price multiplier must not be negative");
        source = source == null ? "world" : source;
    }

    public PriceModifier(Category category, int percent) {
        this(category, percent, "world", 0);
    }

    public PriceModifier(String category, int percent) {
        this(category(category), percent, "world", 0);
    }

    public long apply(long basePrice) {
        if (basePrice < 0) throw new IllegalArgumentException("Base price must not be negative");
        return Math.floorDiv(Math.multiplyExact(basePrice, percent), 100);
    }

    public static Category category(String key) {
        if (key == null) return Category.OTHER;
        try {
            return Category.valueOf(key.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Category.OTHER;
        }
    }
}
