package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameSaveService;
import com.gameneko.aigame.application.GameActionService;
import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.engine.GameInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP adapter for save/load of server-owned game instances. */
@RestController
@RequestMapping("/api/games")
public final class GameSaveController {
    private final GameSaveService saveService;
    private final GameInstanceService instanceService;
    private final GameActionService actionService;

    public GameSaveController(GameSaveService saveService, GameInstanceService instanceService,
                              GameActionService actionService) {
        this.saveService = saveService;
        this.instanceService = instanceService;
        this.actionService = actionService;
    }

    @PostMapping("/{gameId}/initialize")
    public GameInstanceResponse initialize(@PathVariable String gameId,
                                           @RequestBody InitializeWorldHttpRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        InitializeWorldRequest applicationRequest = new InitializeWorldRequest(request.worldId(), request.name(), request.description(),
                request.location(), request.region(), request.weather(), request.season(), request.time(),
                request.mainQuest(), request.originScene(), request.characterName(), request.age(), request.gender(),
                request.archetype(), request.difficulty());
        var instance = instanceService.initialize(gameId, applicationRequest);
        saveService.save(instance);
        return GameInstanceResponse.from(instance);
    }

    @PostMapping("/{gameId}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable String gameId, @RequestBody SaveGameRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        saveService.checkpoint(gameId, request.expectedRevision());
    }

    @PostMapping("/{gameId}/actions")
    public GameInstanceResponse action(@PathVariable String gameId, @RequestBody GameActionRequest request) {
        if (request == null || request.type() == null) throw new IllegalArgumentException("Action type is required");
        String actionType = request.type().trim().toLowerCase();
        if ("schedule_timed_event".equals(actionType)) {
            long day = saveService.load(gameId).worldState().day();
            long duration = request.deadlineDays() == null ? 14 : request.deadlineDays();
            if (duration < 0) throw new IllegalArgumentException("Deadline must not be negative");
            var event = new com.gameneko.aigame.engine.TimedEvent(request.eventId(), "foreshadow",
                    request.eventDescription(), request.eventAnswer(), request.eventConsequence(), day,
                    Math.addExact(day, duration), duration, com.gameneko.aigame.engine.TimedEvent.Status.ACTIVE,
                    com.gameneko.aigame.engine.WorldEffects.none());
            return GameInstanceResponse.from(actionService.applyWorldAction(gameId, request.expectedRevision(), new com.gameneko.aigame.engine.WorldAction.ScheduleEvent(event)));
        }
        if ("resolve_timed_event".equals(actionType)) {
            return GameInstanceResponse.from(actionService.applyWorldAction(gameId, request.expectedRevision(), new com.gameneko.aigame.engine.WorldAction.ResolveEvent(request.eventId(), request.outcome())));
        }
        if ("set_price_modifier".equals(actionType)) {
            if (request.priceCategory() == null || request.pricePercent() == null) throw new IllegalArgumentException("Price category and multiplier are required");
            return GameInstanceResponse.from(actionService.applyWorldAction(gameId, request.expectedRevision(),
                    new com.gameneko.aigame.engine.WorldAction.SetPriceModifier(
                            new com.gameneko.aigame.engine.PriceModifier(com.gameneko.aigame.engine.PriceModifier.category(request.priceCategory()),
                                    request.pricePercent(), "action", request.expiresDay() == null ? 0 : request.expiresDay()))));
        }
        if ("remove_price_modifier".equals(actionType)) {
            if (request.priceCategory() == null) throw new IllegalArgumentException("Price category is required");
            return GameInstanceResponse.from(actionService.applyWorldAction(gameId, request.expectedRevision(),
                    new com.gameneko.aigame.engine.WorldAction.RemovePriceModifier(
                            com.gameneko.aigame.engine.PriceModifier.category(request.priceCategory()))));
        }
        if ("offer_quest".equals(actionType)) {
            long day = saveService.load(gameId).worldState().day();
            long deadline = request.deadlineDays() == null ? 0 : request.deadlineDays();
            if (deadline < 0) throw new IllegalArgumentException("Quest deadline must not be negative");
            var objectives = request.objectiveId() == null || request.objectiveId().isBlank()
                    ? java.util.List.<com.gameneko.aigame.rulesets.ap.ApQuest.Objective>of()
                    : java.util.List.of(new com.gameneko.aigame.rulesets.ap.ApQuest.Objective(request.objectiveId(),
                    request.objectiveDescription(), 0, request.objectiveRequired() == null ? 1 : request.objectiveRequired()));
            var quest = new com.gameneko.aigame.rulesets.ap.ApQuest(request.questId(), request.questTitle(),
                    request.questDescription(), request.questRegion(), com.gameneko.aigame.rulesets.ap.ApQuest.Status.AVAILABLE,
                    objectives, new com.gameneko.aigame.rulesets.ap.ApQuest.Reward(value(request.xp()), value(request.copper()),
                    value(request.rations()), value(request.potions())), day, deadline, 0, "");
            return GameInstanceResponse.from(actionService.apply(gameId, request.expectedRevision(),
                    new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.OfferQuest(quest)));
        }
        var action = switch (request.type().trim().toLowerCase()) {
            case "rest" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.Rest();
            case "consume_ration" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ConsumeRation();
            case "use_potion" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.UsePotion();
            case "advance_day" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.AdvanceDay();
            case "grant_experience" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.GrantExperience(
                    request.amount() == null ? 0 : request.amount());
            case "learn_ability" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.LearnAbility(request.abilityId());
            case "equip_item" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.EquipItem(request.itemName(), request.slot());
            case "use_item" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.UseItem(request.itemName());
            case "claim_quest_reward" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ClaimQuestReward(
                    request.questId(), value(request.xp()), value(request.copper()), value(request.rations()), value(request.potions()));
            case "start_battle" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.StartBattle(
                    request.encounterName(), request.region(), request.enemyCount() == null ? 1 : request.enemyCount(),
                    request.seed() == null ? 0 : request.seed());
            case "battle_action" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.BattleAction(
                    battleAction(request.battleAction()), request.targetId(), request.abilityId(), request.itemName());
            case "advance_battle_turn" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.AdvanceBattleTurn();
            case "settle_battle" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.SettleBattle(
                    battleResult(request.battleAction()));
            case "accept_quest" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.AcceptQuest(request.questId());
            case "progress_quest" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ProgressQuest(
                    request.questId(), request.objectiveIndex() == null ? 0 : request.objectiveIndex(),
                    request.amount() == null ? 0 : Math.toIntExact(request.amount()));
            case "complete_quest" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.CompleteQuest(request.questId());
            case "fail_quest" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.FailQuest(request.questId(), request.failureReason());
            case "register_npc" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.RegisterNpc(
                    request.npcId(), request.npcName(), request.npcType(), request.npcLocation(), request.seed() == null ? 0 : request.seed());
            case "interact_npc" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.InteractNpc(
                    request.npcId(), request.interaction(), interactionResult(request.interactionResult()), request.seed() == null ? 0 : request.seed(), saveService.load(gameId).worldState().day());
            case "tick_npcs" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.TickNpcs(request.seed() == null ? 0 : request.seed(), saveService.load(gameId).worldState().day());
            case "acquire_item" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.AcquireItem(
                    request.itemName(), request.itemCategory(), request.itemTier(), request.seed() == null ? 0 : request.seed());
            case "record_npc_life_event" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.RecordNpcLifeEvent(
                    request.npcId(), request.eventDescription(), saveService.load(gameId).worldState().day());
            case "set_npc_status" -> new com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.SetNpcStatus(
                    request.npcId(), npcStatus(request.outcome()));
            default -> throw new IllegalArgumentException("Unsupported AP action: " + request.type());
        };
        return GameInstanceResponse.from(actionService.apply(gameId, request.expectedRevision(), action));
    }

    private static long value(Long value) { return value == null ? 0 : value; }

    private static com.gameneko.aigame.rulesets.ap.ApBattle.ActionKind battleAction(String value) {
        if (value == null) throw new IllegalArgumentException("Battle action is required");
        try { return com.gameneko.aigame.rulesets.ap.ApBattle.ActionKind.valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown battle action: " + value); }
    }

    private static com.gameneko.aigame.rulesets.ap.ApBattle.Result battleResult(String value) {
        if (value == null) throw new IllegalArgumentException("Battle result is required");
        try { return com.gameneko.aigame.rulesets.ap.ApBattle.Result.valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown battle result: " + value); }
    }

    private static com.gameneko.aigame.rulesets.ap.ApNpc.InteractionResult interactionResult(String value) {
        if (value == null) return com.gameneko.aigame.rulesets.ap.ApNpc.InteractionResult.NEUTRAL;
        try { return com.gameneko.aigame.rulesets.ap.ApNpc.InteractionResult.valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown interaction result: " + value); }
    }

    private static com.gameneko.aigame.rulesets.ap.ApNpc.Status npcStatus(String value) {
        if (value == null) throw new IllegalArgumentException("NPC status is required");
        try { return com.gameneko.aigame.rulesets.ap.ApNpc.Status.valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown NPC status: " + value); }
    }

    /** Full read-only game projection used when the client resumes a saved game. */
    @GetMapping("/{gameId}")
    public GameInstanceResponse get(@PathVariable String gameId) {
        return GameInstanceResponse.from(saveService.load(gameId));
    }

    @GetMapping("/{gameId}/save")
    public WorldInitializationResponse load(@PathVariable String gameId) {
        GameInstance<?> instance = saveService.load(gameId);
        return WorldInitializationResponse.from(instance.worldState());
    }
}
