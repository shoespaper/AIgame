package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Loads, resolves, versions, and persists one authoritative action. */
@Service
public final class GameActionService {
    private final GameRepository repository;
    private final ContentCatalog contentCatalog;
    private final GameEngineFactory gameEngineFactory;

    public GameActionService(GameRepository repository, ContentCatalog contentCatalog) {
        this(repository, contentCatalog, GameEngineFactory.unsupported());
    }

    @Autowired
    public GameActionService(GameRepository repository, ContentCatalog contentCatalog, GameEngineFactory gameEngineFactory) {
        this.repository = repository;
        this.contentCatalog = contentCatalog;
        this.gameEngineFactory = gameEngineFactory;
    }

    public ActionValidation validateIntent(String gameId, long expectedRevision, ActionIntent intent) {
        GameInstance<?> loaded = checkedLoad(gameId, expectedRevision);
        if (loaded.rulesetId() == null) return ActionValidation.reject("engine_required");
        return validateRuntime(gameEngineFactory.create(loaded.rulesetId(), contentCatalog.world(loaded.worldId())), loaded, intent);
    }

    /** Validates and resolves an intent without writing the save. */
    public EngineResolution<?> resolveIntent(String gameId, long expectedRevision, ActionIntent intent) {
        GameInstance<?> loaded = checkedLoad(gameId, expectedRevision);
        return resolveRuntime(runtimeFor(loaded, contentCatalog.world(loaded.worldId())), loaded, intent);
    }

    public AppliedAction applyIntent(String gameId, long expectedRevision, ActionIntent intent) {
        GameInstance<?> loaded = checkedLoad(gameId, expectedRevision);
        var definition = contentCatalog.world(loaded.worldId());
        var runtime = runtimeFor(loaded, definition);
        var resolution = resolveRuntime(runtime, loaded, intent);
        long nextRevision = loaded.revision() + 1;
        var world = loaded.worldState();
        var nextWorld = resolution.advancesDay()
                ? advanceWorld(world, nextRevision, definition) : world.withRevision(nextRevision);
        var newEvents = nextWorld.worldEvents().subList(world.worldEvents().size(), nextWorld.worldEvents().size());
        GameState nextState = resolution.advancesDay()
                ? afterWorldAdvance(runtime, resolution.nextState(), nextWorld.day(), gameId.hashCode() ^ nextRevision, newEvents)
                : resolution.nextState();
        var next = new GameInstance<>(loaded.gameId(), loaded.worldId(), loaded.contentVersion(), loaded.rulesetId(),
                loaded.rulesetVersion(), GameInstance.Lifecycle.ACTIVE, nextRevision, nextWorld, nextState);
        repository.save(next);
        return new AppliedAction(next, resolution.events(), newEvents);
    }

    private GameEngineRuntime<?, ?> runtimeFor(GameInstance<?> instance, WorldDefinition definition) {
        if (instance.rulesetId() == null) throw new IllegalArgumentException("engine_required");
        return gameEngineFactory.create(instance.rulesetId(), definition);
    }

    /** Read-only runtime access for narrative projection; no state mutation occurs here. */
    public GameEngineRuntime<?, ?> runtimeForNarration(GameInstance<?> instance, WorldDefinition definition) {
        return runtimeFor(instance, definition);
    }

    private GameInstance<?> checkedLoad(String gameId, long expectedRevision) {
        GameInstance<?> loaded = repository.load(gameId).orElseThrow(() -> new IllegalArgumentException("Save not found: " + gameId));
        if (expectedRevision != loaded.revision()) throw new IllegalArgumentException("Revision mismatch: expected " + expectedRevision + ", current " + loaded.revision());
        return loaded;
    }

    // The engine factory selects the runtime for the state stored with this engine ID.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ActionValidation validateRuntime(GameEngineRuntime runtime, GameInstance<?> instance, ActionIntent intent) {
        return runtime.validate(instance.rulesetState(), intent, instance.worldState().day());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EngineResolution<?> resolveRuntime(GameEngineRuntime runtime, GameInstance<?> instance, ActionIntent intent) {
        var validation = validateRuntime(runtime, instance, intent);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.reason());
        return runtime.resolve(instance.rulesetState(), intent, instance.worldState().day());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static GameState afterWorldAdvance(GameEngineRuntime runtime, GameState state, long day, long seed, List<WorldEvent> events) {
        return runtime.afterWorldAdvance(state, day, seed, events);
    }

    public record AppliedAction(GameInstance<?> instance, List<ConfirmedEvent> events, List<WorldEvent> worldEvents) {
        public AppliedAction { events = List.copyOf(events); worldEvents = List.copyOf(worldEvents); }
    }

    public GameInstance<?> scheduleEvent(String gameId, long expectedRevision, TimedEvent event) {
        return updateWorld(gameId, expectedRevision, world -> world.schedule(event));
    }

    public GameInstance<?> applyWorldAction(String gameId, long expectedRevision, WorldAction action) {
        Objects.requireNonNull(action, "action");
        if (action instanceof WorldAction.ScheduleEvent schedule) return scheduleEvent(gameId, expectedRevision, schedule.event());
        if (action instanceof WorldAction.ResolveEvent resolve) return resolveEvent(gameId, expectedRevision, resolve.eventId(), resolve.outcome());
        if (action instanceof WorldAction.SetPriceModifier set) return setPriceModifier(gameId, expectedRevision, set.modifier());
        if (action instanceof WorldAction.RemovePriceModifier remove) return updateWorld(gameId, expectedRevision, world -> {
            var next = new java.util.EnumMap<PriceModifier.Category, PriceModifier>(PriceModifier.Category.class);
            next.putAll(world.activePriceModifiers());
            next.remove(remove.category());
            return new WorldState(world.worldId(), world.contentVersion(), world.revision(), world.setting(),
                    world.settingDescription(), world.location(), world.currentRegion(), world.mainQuest(),
                    world.day(), world.time(), world.season(), world.weather(), world.flags(), world.threatLevel(),
                    world.threatCounter(), world.threatInterval(), world.threatHistory(), world.timedEvents(),
                    world.incidentalEvents(), world.lastTickDay(), world.crisisTriggered(), world.mainPlotStage(),
                    world.mainPlotStages(), world.priceModifiers(), world.originScene(), world.deadlineEvents(),
                    world.worldEvents(), next, world.activeEffects());
        });
        throw new IllegalArgumentException("Unsupported world action");
    }

    public GameInstance<?> resolveEvent(String gameId, long expectedRevision, String eventId, String outcome) {
        return updateWorld(gameId, expectedRevision, world -> world.resolveTimedEvent(eventId, outcome));
    }

    public GameInstance<?> setPriceModifier(String gameId, long expectedRevision, PriceModifier modifier) {
        return updateWorld(gameId, expectedRevision, world -> {
            Map<PriceModifier.Category, PriceModifier> next = new java.util.EnumMap<>(PriceModifier.Category.class);
            next.putAll(world.activePriceModifiers());
            next.put(modifier.category(), modifier);
            return new WorldState(world.worldId(), world.contentVersion(), world.revision(), world.setting(),
                    world.settingDescription(), world.location(), world.currentRegion(), world.mainQuest(),
                    world.day(), world.time(), world.season(), world.weather(), world.flags(), world.threatLevel(),
                    world.threatCounter(), world.threatInterval(), world.threatHistory(), world.timedEvents(),
                    world.incidentalEvents(), world.lastTickDay(), world.crisisTriggered(), world.mainPlotStage(),
                    world.mainPlotStages(), world.priceModifiers(), world.originScene(), world.deadlineEvents(),
                    world.worldEvents(), next, world.activeEffects());
        });
    }

    private GameInstance<?> updateWorld(String gameId, long expectedRevision,
                                        java.util.function.UnaryOperator<WorldState> update) {
        GameInstance<?> loaded = repository.load(gameId).orElseThrow(() -> new IllegalArgumentException("Save not found: " + gameId));
        if (expectedRevision != loaded.revision()) throw new IllegalArgumentException("Revision mismatch: expected " + expectedRevision + ", current " + loaded.revision());
        WorldState changed = update.apply(loaded.worldState()).withRevision(loaded.revision() + 1);
        GameInstance<?> next = new GameInstance<>(loaded.gameId(), loaded.worldId(), loaded.contentVersion(), loaded.rulesetId(), loaded.rulesetVersion(), GameInstance.Lifecycle.ACTIVE, loaded.revision() + 1, changed, loaded.rulesetState());
        repository.save(next);
        return next;
    }

    private WorldState advanceWorld(WorldState world, long revision, WorldDefinition definition) {
        long day = world.day() + 1;
        long counter = world.threatCounter() + 1;
        String level = world.threatLevel();
        List<String> history = new ArrayList<>(world.threatHistory());
        Map<PriceModifier.Category, PriceModifier> activePrices = new java.util.EnumMap<>(PriceModifier.Category.class);
        activePrices.putAll(world.activePriceModifiers());
        List<TimedEvent> deadlines = new ArrayList<>(world.deadlineEvents());
        List<WorldEvent> events = new ArrayList<>(world.worldEvents());
        var activeEffects = world.activeEffects();
        if (counter >= Math.max(1, world.threatInterval())) {
            counter = 0;
            int index = threatIndex(level);
            if (index < THREAT_LEVELS.length - 1 && world.mainPlotStage() <= index) {
                level = THREAT_LEVELS[index + 1];
                history.add("第" + day + "天：威胁升级至 " + level);
                int nextLevel = index + 1;
                var template = definition.eventTemplates().timed().stream().filter(e -> "threat_escalation".equals(e.type()) && e.level() == nextLevel).findFirst().orElse(null);
                if (template != null) {
                    applyPrices(activePrices, template.effects().priceModifiers());
                    events.add(new WorldEvent("threat-" + day, day, WorldEvent.Type.THREAT_ESCALATION, WorldEvent.Category.WORLD_THREAT, template.description(), template.effects()));
                    activeEffects = merge(activeEffects, template.effects());
                }
            }
        }
        List<TimedEvent> advanced = deadlines.stream().map(event -> event.tick(day)).toList();
        for (int i = 0; i < advanced.size(); i++) if (deadlines.get(i).status() == TimedEvent.Status.ACTIVE && advanced.get(i).status() == TimedEvent.Status.EXPIRED) {
            TimedEvent event = advanced.get(i);
            events.add(new WorldEvent(event.id() + "-expired", day, WorldEvent.Type.DEADLINE, WorldEvent.Category.PLOT, event.consequence(), event.effects()));
            activeEffects = merge(activeEffects, event.effects());
            int index = threatIndex(level);
            if (index < THREAT_LEVELS.length - 1) { level = THREAT_LEVELS[index + 1]; history.add("第" + day + "天：期限事件未解决，威胁升至 " + level); }
        }
        if (day % 3 == 0 && !definition.eventTemplates().incidental().isEmpty()) {
            var templates = definition.eventTemplates().incidental();
            var template = chooseWeighted(templates, new java.util.SplittableRandom(day ^ revision));
            applyPrices(activePrices, template.effects().priceModifiers());
            events.add(new WorldEvent(template.type() + "-" + day, day, parseType(template.type()), parseCategory(template.category()), template.description(), template.effects()));
            activeEffects = merge(activeEffects, template.effects());
        }
        activePrices.values().removeIf(modifier -> modifier.expiresDay() > 0 && day >= modifier.expiresDay());
        boolean crisis = "Crisis".equals(level) || world.crisisTriggered();
        return copyWorld(world, revision, day, counter, level, history, world.timedEvents(), world.incidentalEvents(), day, crisis, activePrices, activeEffects, advanced, events);
    }

    private WorldState copyWorld(WorldState world, long revision, long day, long counter, String level,
                                 List<String> history, List<String> timed, List<String> incidental,
                                 long lastTick, boolean crisis, Map<PriceModifier.Category, PriceModifier> activePrices,
                                 WorldEffects activeEffects,
                                 List<TimedEvent> deadlines, List<WorldEvent> events) {
        return new WorldState(world.worldId(), world.contentVersion(), revision, world.setting(), world.settingDescription(), world.location(), world.currentRegion(), world.mainQuest(), day, world.time(), world.season(), world.weather(), world.flags(), level, counter, world.threatInterval(), history, timed, incidental, lastTick, crisis, world.mainPlotStage(), world.mainPlotStages(), world.priceModifiers(), world.originScene(), deadlines, events, activePrices, activeEffects);
    }

    private void applyPrices(Map<PriceModifier.Category, PriceModifier> target, List<PriceModifier> modifiers) { for (PriceModifier modifier : modifiers) target.put(modifier.category(), modifier); }
    private WorldDefinition.EventTemplate chooseWeighted(List<WorldDefinition.EventTemplate> templates, RandomGenerator random) {
        int totalWeight = templates.stream().mapToInt(WorldDefinition.EventTemplate::weight).sum();
        int draw = random.nextInt(totalWeight);
        for (WorldDefinition.EventTemplate template : templates) {
            draw -= template.weight();
            if (draw < 0) return template;
        }
        return templates.getLast();
    }

    private WorldEffects merge(WorldEffects current,
                                                           WorldEffects addition) {
        var prices = new java.util.ArrayList<>(current.priceModifiers()); prices.addAll(addition.priceModifiers());
        var npcPool = new java.util.ArrayList<>(current.npcPoolAdditions()); npcPool.addAll(addition.npcPoolAdditions());
        return new WorldEffects(prices,
                Math.addExact(current.threatCounterDelta(), addition.threatCounterDelta()),
                Math.addExact(current.npcMoodShift(), addition.npcMoodShift()),
                current.travelPenalty() || addition.travelPenalty(),
                addition.outdoorSpeedModifier() == 0 ? current.outdoorSpeedModifier() : addition.outdoorSpeedModifier(),
                npcPool, current.shopRefresh() || addition.shopRefresh(), current.spawnThreat() || addition.spawnThreat(),
                Math.addExact(current.rationsBonus(), addition.rationsBonus()),
                Math.max(current.questHookChancePercent(), addition.questHookChancePercent()),
                Math.max(current.rumorChancePercent(), addition.rumorChancePercent()),
                current.gameOverRisk() || addition.gameOverRisk(), current.custom() || addition.custom());
    }
    private static WorldEvent.Type parseType(String value) { try { return WorldEvent.Type.valueOf(value.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException exception) { return WorldEvent.Type.OTHER; } }
    private static WorldEvent.Category parseCategory(String value) { try { return WorldEvent.Category.valueOf(value.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException exception) { return WorldEvent.Category.OTHER; } }
    private int threatIndex(String level) { for (int i = 0; i < THREAT_LEVELS.length; i++) if (THREAT_LEVELS[i].equals(level)) return i; return 0; }
    private static final String[] THREAT_LEVELS = {"Dormant", "Low", "Moderate", "High", "Severe", "Crisis"};
}
