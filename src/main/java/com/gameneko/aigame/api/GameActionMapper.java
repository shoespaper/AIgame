package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameSaveService;
import com.gameneko.aigame.engine.PriceModifier;
import com.gameneko.aigame.engine.TimedEvent;
import com.gameneko.aigame.engine.WorldAction;
import com.gameneko.aigame.engine.WorldEffects;
import com.gameneko.aigame.rulesets.ap.ApBattle;
import com.gameneko.aigame.rulesets.ap.ApNpc;
import com.gameneko.aigame.rulesets.ap.ApQuest;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Converts HTTP request data into closed typed commands. */
@Component
public final class GameActionMapper {
    private final GameSaveService saveService;

    public GameActionMapper(GameSaveService saveService) {
        this.saveService = saveService;
    }

    public MappedGameAction map(String gameId, GameActionRequest request) {
        if (request == null || request.type() == null) throw new IllegalArgumentException("Action type is required");
        long day = saveService.load(gameId).worldState().day();
        return switch (normalize(request.type())) {
            case "schedule_timed_event" -> scheduleTimedEvent(request, day);
            case "resolve_timed_event" -> MappedGameAction.world(
                    new WorldAction.ResolveEvent(request.eventId(), request.outcome()));
            case "set_price_modifier" -> setPriceModifier(request);
            case "remove_price_modifier" -> removePriceModifier(request);
            case "rest" -> ap(new ApRulesetRuntime.Rest());
            case "consume_ration" -> ap(new ApRulesetRuntime.ConsumeRation());
            case "use_potion" -> ap(new ApRulesetRuntime.UsePotion());
            case "advance_day" -> ap(new ApRulesetRuntime.AdvanceDay());
            case "grant_experience" -> ap(new ApRulesetRuntime.GrantExperience(value(request.amount())));
            case "learn_ability" -> ap(new ApRulesetRuntime.LearnAbility(request.abilityId()));
            case "equip_item" -> ap(new ApRulesetRuntime.EquipItem(request.itemName(), request.slot()));
            case "use_item" -> ap(new ApRulesetRuntime.UseItem(request.itemName()));
            case "claim_quest_reward" -> ap(new ApRulesetRuntime.ClaimQuestReward(request.questId(),
                    value(request.xp()), value(request.copper()), value(request.rations()), value(request.potions())));
            case "offer_quest" -> offerQuest(request, day);
            case "start_battle" -> ap(new ApRulesetRuntime.StartBattle(request.encounterName(), request.region(),
                    request.enemyCount() == null ? 1 : request.enemyCount(), value(request.seed())));
            case "battle_action" -> ap(new ApRulesetRuntime.BattleAction(battleAction(request.battleAction()),
                    request.targetId(), request.abilityId(), request.itemName()));
            case "advance_battle_turn" -> ap(new ApRulesetRuntime.AdvanceBattleTurn());
            case "settle_battle" -> ap(new ApRulesetRuntime.SettleBattle(battleResult(request.battleAction())));
            case "accept_quest" -> ap(new ApRulesetRuntime.AcceptQuest(request.questId()));
            case "progress_quest" -> ap(new ApRulesetRuntime.ProgressQuest(request.questId(),
                    request.objectiveIndex() == null ? 0 : request.objectiveIndex(),
                    request.amount() == null ? 0 : Math.toIntExact(request.amount())));
            case "complete_quest" -> ap(new ApRulesetRuntime.CompleteQuest(request.questId()));
            case "fail_quest" -> ap(new ApRulesetRuntime.FailQuest(request.questId(), request.failureReason()));
            case "register_npc" -> ap(new ApRulesetRuntime.RegisterNpc(request.npcId(), request.npcName(),
                    request.npcType(), request.npcLocation(), value(request.seed())));
            case "interact_npc" -> ap(new ApRulesetRuntime.InteractNpc(request.npcId(), request.interaction(),
                    interactionResult(request.interactionResult()), value(request.seed()), day));
            case "tick_npcs" -> ap(new ApRulesetRuntime.TickNpcs(value(request.seed()), day));
            case "acquire_item" -> ap(new ApRulesetRuntime.AcquireItem(request.itemName(), request.itemCategory(),
                    request.itemTier(), value(request.seed())));
            case "record_npc_life_event" -> ap(new ApRulesetRuntime.RecordNpcLifeEvent(request.npcId(),
                    request.eventDescription(), day));
            case "set_npc_status" -> ap(new ApRulesetRuntime.SetNpcStatus(request.npcId(), npcStatus(request.outcome())));
            default -> throw new IllegalArgumentException("Unsupported AP action: " + request.type());
        };
    }

    private MappedGameAction scheduleTimedEvent(GameActionRequest request, long day) {
        long duration = request.deadlineDays() == null ? 14 : request.deadlineDays();
        if (duration <= 0) throw new IllegalArgumentException("Deadline must be positive");
        var event = new TimedEvent(request.eventId(), "foreshadow", request.eventDescription(),
                request.eventAnswer(), request.eventConsequence(), day, Math.addExact(day, duration),
                duration, TimedEvent.Status.ACTIVE, WorldEffects.none());
        return MappedGameAction.world(new WorldAction.ScheduleEvent(event));
    }

    private MappedGameAction setPriceModifier(GameActionRequest request) {
        if (request.priceCategory() == null || request.pricePercent() == null) {
            throw new IllegalArgumentException("Price category and multiplier are required");
        }
        return MappedGameAction.world(new WorldAction.SetPriceModifier(new PriceModifier(
                PriceModifier.category(request.priceCategory()), request.pricePercent(), "action",
                request.expiresDay() == null ? 0 : request.expiresDay())));
    }

    private MappedGameAction removePriceModifier(GameActionRequest request) {
        if (request.priceCategory() == null) throw new IllegalArgumentException("Price category is required");
        return MappedGameAction.world(new WorldAction.RemovePriceModifier(PriceModifier.category(request.priceCategory())));
    }

    private MappedGameAction offerQuest(GameActionRequest request, long day) {
        long deadline = request.deadlineDays() == null ? 0 : request.deadlineDays();
        if (deadline < 0) throw new IllegalArgumentException("Quest deadline must not be negative");
        List<ApQuest.Objective> objectives = request.objectiveId() == null || request.objectiveId().isBlank()
                ? List.of() : List.of(new ApQuest.Objective(request.objectiveId(), request.objectiveDescription(), 0,
                request.objectiveRequired() == null ? 1 : request.objectiveRequired()));
        var quest = new ApQuest(request.questId(), request.questTitle(), request.questDescription(), request.questRegion(),
                ApQuest.Status.AVAILABLE, objectives,
                new ApQuest.Reward(value(request.xp()), value(request.copper()), value(request.rations()), value(request.potions())),
                day, deadline, 0, "");
        return ap(new ApRulesetRuntime.OfferQuest(quest));
    }

    public ApRulesetRuntime.ApAction mapApAction(String gameId, GameActionRequest request) {
        MappedGameAction mapped = map(gameId, request);
        if (mapped.apAction() == null) throw new IllegalArgumentException("World actions cannot be narrated as AP actions");
        return mapped.apAction();
    }

    private MappedGameAction ap(ApRulesetRuntime.ApAction action) { return MappedGameAction.ap(action); }
    private long value(Long number) { return number == null ? 0 : number; }
    private String normalize(String action) { return action.trim().toLowerCase(Locale.ROOT); }

    private ApBattle.ActionKind battleAction(String value) {
        if (value == null) throw new IllegalArgumentException("Battle action is required");
        try { return ApBattle.ActionKind.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown battle action: " + value); }
    }

    private ApBattle.Result battleResult(String value) {
        if (value == null) throw new IllegalArgumentException("Battle result is required");
        try { return ApBattle.Result.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown battle result: " + value); }
    }

    private ApNpc.InteractionResult interactionResult(String value) {
        if (value == null) return ApNpc.InteractionResult.NEUTRAL;
        try { return ApNpc.InteractionResult.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown interaction result: " + value); }
    }

    private ApNpc.Status npcStatus(String value) {
        if (value == null) throw new IllegalArgumentException("NPC status is required");
        try { return ApNpc.Status.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown NPC status: " + value); }
    }
}
