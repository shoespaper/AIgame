package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.GameState;
import com.gameneko.aigame.engine.RulesetResolution;
import com.gameneko.aigame.engine.RulesetRuntime;

import java.util.List;
import java.util.Map;

/** AP baseline runtime; initialization is migrated before action resolution. */
public final class ApRulesetRuntime implements RulesetRuntime<ApRulesetRuntime.ApState, ApRulesetRuntime.ApAction,
        ApRulesetRuntime.ApEvent, ApRulesetRuntime.ApProjection> {
    private static final Map<String, Archetype> ARCHETYPES = Map.of(
            "swordsman", new Archetype(14, 12, 8, 12, 9, 9, "短剑", "+2", "皮背心"),
            "mage", new Archetype(7, 10, 16, 8, 14, 10, "学徒法杖", "+1", "法袍"),
            "ranger", new Archetype(11, 15, 10, 10, 12, 9, "短弓", "+1", "轻便布衣"),
            "alchemist", new Archetype(9, 10, 15, 9, 11, 13, "投石索", "+1", "工作围裙"));
    private static final Archetype DEFAULT = new Archetype(10, 10, 10, 10, 10, 10, "铁匕首", "+2", "旅人衣");

    @Override public String id() { return "ap"; }
    @Override public String version() { return "baseline-1"; }

    public ApState initialize(ApInitializationRequest request, WorldDefinition world) {
        if (request == null || world == null) throw new IllegalArgumentException("AP initialization input is required");
        String archetype = request.archetype() == null ? "swordsman" : request.archetype().toLowerCase().trim();
        Archetype preset = ARCHETYPES.getOrDefault(archetype, DEFAULT);
        String difficulty = request.difficulty() == null ? "adventure" : request.difficulty().toLowerCase().trim();
        WorldDefinition.DifficultyDefinition diff = world.difficulties().get(difficulty);
        if (diff == null) throw new IllegalArgumentException("Unknown difficulty: " + difficulty);
        int hp = 20 + preset.constitution();
        int sp = 6 + preset.intelligence() / 2;
        Talent talent = world.talents().entrySet().stream().findFirst()
                .map(entry -> new Talent(entry.getKey(), entry.getValue().name(), entry.getValue().description(), false))
                .orElse(null);
        return new ApState(request.characterName(), request.age(), request.gender(), archetype, difficulty,
                1, 0, 100, preset.strength(), preset.dexterity(), preset.constitution(), preset.intelligence(),
                preset.wisdom(), preset.charisma(), hp, hp, sp, sp,
                List.of(new Item(preset.weapon(), "weapon", preset.damage(), 1), new Item(preset.armor(), "armor", "", 1)),
                new Equipment(preset.weapon(), preset.armor()),
                new Resources(diff.startingCopper(), diff.startingRations(), diff.startingPotions()), talent);
    }

    @Override public ActionValidation validate(ApState state, ApAction action) { return ActionValidation.reject("AP actions are not migrated yet"); }
    @Override public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action) { throw new UnsupportedOperationException("AP actions are not migrated yet"); }
    @Override public ApProjection project(ApState state) { return new ApProjection(state); }

    private record Archetype(int strength, int dexterity, int intelligence, int constitution, int wisdom, int charisma,
                             String weapon, String damage, String armor) {}
    public record ApInitializationRequest(String characterName, int age, String gender, String archetype, String difficulty) {}
    public record ApState(String characterName, int age, String gender, String archetype, String difficulty,
                          int level, long xp, long xpToNext, int strength, int dexterity, int constitution,
                          int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent,
                          int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent)
            implements GameState {
        public ApState { inventory = List.copyOf(inventory); }
    }
    public record Item(String name, String type, String damage, int quantity) {}
    public record Equipment(String weapon, String armor) {}
    public record Resources(long copper, long rations, long potions) {}
    public record Talent(String type, String name, String description, boolean awakened) {}
    public record ApAction(String type) {}
    public record ApEvent(String type) {}
    public record ApProjection(ApState state) {}
}
