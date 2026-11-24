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

/** 结算引擎动作和世界字段声明，统一推进权威修订号并持久化。 */
@Service
public final class GameActionService {
    private final GameRepository repository;
    private final ContentCatalog contentCatalog;
    private final GameEngineFactory gameEngineFactory;

    /** 装配权威动作与世界状态的结算依赖。
     * @param repository 游戏存档端口
     * @param contentCatalog 世界书和模板目录
     */
    public GameActionService(GameRepository repository, ContentCatalog contentCatalog) {
        this(repository, contentCatalog, GameEngineFactory.unsupported());
    }

    /** 装配权威动作与世界状态的结算依赖。
     * @param repository 游戏存档端口
     * @param contentCatalog 世界书和模板目录
     * @param gameEngineFactory 按引擎标识创建运行时的工厂
     */
    @Autowired
    public GameActionService(GameRepository repository, ContentCatalog contentCatalog, GameEngineFactory gameEngineFactory) {
        this.repository = repository;
        this.contentCatalog = contentCatalog;
        this.gameEngineFactory = gameEngineFactory;
    }

    /** 结算一次引擎动作并保存新版本。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的修订号
     * @param intent 由选中引擎验证的结构化动作
     * @return 包含确认事件的新实例
     */
    public AppliedAction applyIntent(String gameId, long expectedRevision, ActionIntent intent) {
        return applyNarrativeTurn(gameId, expectedRevision, intent, Map.of());
    }

    /** 将可空动作与世界声明合并为一次结算，统一推进版本并仅保存一次。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的修订号
     * @param intent 可空的引擎调用意图，无引擎时非空意图会被拒绝
     * @param updates 叙事模型声明的世界字段，非法类型和区域独立忽略
     * @return 合并字段后的权威实例及本次确认事件
     */
    public AppliedAction applyNarrativeTurn(String gameId, long expectedRevision, ActionIntent intent,
                                            Map<String, InitializationValue> updates) {
        GameInstance<?> loaded = checkedLoad(gameId, expectedRevision);
        var definition = contentCatalog.world(loaded.worldId());
        var accepted = WorldUpdatePolicy.accepted(updates, definition);
        if (intent == null) {
            if (accepted.isEmpty()) return new AppliedAction(loaded, List.of(), List.of());
            long revision = loaded.revision() + 1;
            var world = loaded.worldState().applyWorldUpdates(accepted).withRevision(revision);
            var next = new GameInstance<>(loaded.gameId(), loaded.worldId(), loaded.contentVersion(), loaded.rulesetId(),
                    loaded.rulesetVersion(), GameInstance.Lifecycle.ACTIVE, revision, world, loaded.rulesetState());
            repository.save(next);
            return new AppliedAction(next, List.of(), List.of());
        }
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
        nextWorld = nextWorld.applyWorldUpdates(accepted);
        var next = new GameInstance<>(loaded.gameId(), loaded.worldId(), loaded.contentVersion(), loaded.rulesetId(),
                loaded.rulesetVersion(), GameInstance.Lifecycle.ACTIVE, nextRevision, nextWorld, nextState);
        repository.save(next);
        return new AppliedAction(next, resolution.events(), newEvents);
    }

    /** 获取实例选中的引擎运行时。
     * @param instance 当前权威游戏实例
     * @param definition 当前世界模板定义
     */
    private GameEngineRuntime<?, ?> runtimeFor(GameInstance<?> instance, WorldDefinition definition) {
        if (instance.rulesetId() == null) throw new IllegalArgumentException("engine_required");
        return gameEngineFactory.createForGame(instance.rulesetId(), definition, instance.gameId());
    }

    /** 为叙事提供只读引擎运行时，不修改权威状态。
     * @param instance 当前权威游戏实例
     * @param definition 当前世界模板定义
     */
    public GameEngineRuntime<?, ?> runtimeForNarration(GameInstance<?> instance, WorldDefinition definition) {
        return runtimeFor(instance, definition);
    }

    /** 读取存档并校验客户端观察到的修订号。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的权威修订号
     */
    private GameInstance<?> checkedLoad(String gameId, long expectedRevision) {
        GameInstance<?> loaded = repository.load(gameId).orElseThrow(() -> new IllegalArgumentException("Save not found: " + gameId));
        if (expectedRevision != loaded.revision()) throw new IllegalArgumentException("Revision mismatch: expected " + expectedRevision + ", current " + loaded.revision());
        return loaded;
    }

    // The engine factory selects the runtime for the state stored with this engine ID.
    /** 将动作校验委托给选中引擎。
     * @param runtime 选中引擎的运行时
     * @param instance 当前权威游戏实例
     * @param intent 交由选中引擎解释的结构化动作
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ActionValidation validateRuntime(GameEngineRuntime runtime, GameInstance<?> instance, ActionIntent intent) {
        return runtime.validate(instance.rulesetState(), intent, instance.worldState().day());
    }

    /** 验证动作后委托选中引擎进行规则结算。
     * @param runtime 选中引擎的运行时
     * @param instance 当前权威游戏实例
     * @param intent 交由选中引擎解释的结构化动作
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EngineResolution<?> resolveRuntime(GameEngineRuntime runtime, GameInstance<?> instance, ActionIntent intent) {
        var validation = validateRuntime(runtime, instance, intent);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.reason());
        return runtime.resolve(instance.rulesetState(), intent, instance.worldState().day());
    }

    /** 将世界推进后的效果委托给选中引擎。
     * @param runtime 选中引擎的运行时
     * @param state 当前引擎类型化状态
     * @param day 当前世界天数，以天为单位
     * @param seed 本轮确定性随机种子
     * @param events 本轮世界事件列表
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static GameState afterWorldAdvance(GameEngineRuntime runtime, GameState state, long day, long seed, List<WorldEvent> events) {
        return runtime.afterWorldAdvance(state, day, seed, events);
    }

    /** 一次动作与世界声明结算的权威结果。
     * @param instance 结算后的权威实例
     * @param events 引擎确认事件
     * @param worldEvents 本轮新增的世界确认事件
     */
    public record AppliedAction(GameInstance<?> instance, List<ConfirmedEvent> events, List<WorldEvent> worldEvents) {
        /** 复制确认事件集合，防止外部修改结算结果。 */
        public AppliedAction { events = List.copyOf(events); worldEvents = List.copyOf(worldEvents); }
    }

    /** 注册世界期限事件并保存新版本。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的权威修订号
     * @param event 待注册的有效期限事件
     */
    public GameInstance<?> scheduleEvent(String gameId, long expectedRevision, TimedEvent event) {
        return updateWorld(gameId, expectedRevision, world -> world.schedule(event));
    }

    /** 处理世界事件或价格修正并保存新版本。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的权威修订号
     * @param action 待执行的世界层动作
     */
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
                    world.day(), world.time(), world.flags(), world.threatLevel(),
                    world.threatCounter(), world.threatInterval(), world.threatHistory(), world.timedEvents(),
                    world.incidentalEvents(), world.lastTickDay(), world.crisisTriggered(), world.mainPlotStage(),
                    world.mainPlotStages(), world.priceModifiers(), world.originScene(), world.deadlineEvents(),
                    world.worldEvents(), next, world.activeEffects(), world.visitedLocations());
        });
        throw new IllegalArgumentException("Unsupported world action");
    }

    /** 解决指定世界事件并保存新版本。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的权威修订号
     * @param eventId 待解决事件的唯一标识
     * @param outcome 事件解决结果
     */
    public GameInstance<?> resolveEvent(String gameId, long expectedRevision, String eventId, String outcome) {
        return updateWorld(gameId, expectedRevision, world -> world.resolveTimedEvent(eventId, outcome));
    }

    /** 覆盖同分类价格修正并保存新版本。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的权威修订号
     * @param modifier 包含分类、百分比及到期日的价格修正
     */
    public GameInstance<?> setPriceModifier(String gameId, long expectedRevision, PriceModifier modifier) {
        return updateWorld(gameId, expectedRevision, world -> {
            Map<PriceModifier.Category, PriceModifier> next = new java.util.EnumMap<>(PriceModifier.Category.class);
            next.putAll(world.activePriceModifiers());
            next.put(modifier.category(), modifier);
            return new WorldState(world.worldId(), world.contentVersion(), world.revision(), world.setting(),
                    world.settingDescription(), world.location(), world.currentRegion(), world.mainQuest(),
                    world.day(), world.time(), world.flags(), world.threatLevel(),
                    world.threatCounter(), world.threatInterval(), world.threatHistory(), world.timedEvents(),
                    world.incidentalEvents(), world.lastTickDay(), world.crisisTriggered(), world.mainPlotStage(),
                    world.mainPlotStages(), world.priceModifiers(), world.originScene(), world.deadlineEvents(),
                    world.worldEvents(), next, world.activeEffects(), world.visitedLocations());
        });
    }

    /** 对当前世界应用变换，推进修订号并保存。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的权威修订号
     * @param update 对世界快照执行的纯变换
     */
    private GameInstance<?> updateWorld(String gameId, long expectedRevision,
                                        java.util.function.UnaryOperator<WorldState> update) {
        GameInstance<?> loaded = repository.load(gameId).orElseThrow(() -> new IllegalArgumentException("Save not found: " + gameId));
        if (expectedRevision != loaded.revision()) throw new IllegalArgumentException("Revision mismatch: expected " + expectedRevision + ", current " + loaded.revision());
        WorldState changed = update.apply(loaded.worldState()).withRevision(loaded.revision() + 1);
        GameInstance<?> next = new GameInstance<>(loaded.gameId(), loaded.worldId(), loaded.contentVersion(), loaded.rulesetId(), loaded.rulesetVersion(), GameInstance.Lifecycle.ACTIVE, loaded.revision() + 1, changed, loaded.rulesetState());
        repository.save(next);
        return next;
    }

    /** 推进一天并处理威胁、期限、随机事件与持续效果。
     * @param world 当前权威世界状态
     * @param revision 目标世界修订号
     * @param definition 当前世界模板定义
     */
    private WorldState advanceWorld(WorldState world, long revision, WorldDefinition definition) {
        long day = world.day() + 1;
        long counter = world.threatCounter();
        String level = world.threatLevel();
        List<String> history = new ArrayList<>(world.threatHistory());
        Map<PriceModifier.Category, PriceModifier> activePrices = new java.util.EnumMap<>(PriceModifier.Category.class);
        activePrices.putAll(world.activePriceModifiers());
        List<TimedEvent> deadlines = new ArrayList<>(world.deadlineEvents());
        List<WorldEvent> events = new ArrayList<>(world.worldEvents());
        var activeEffects = world.activeEffects();
        List<TimedEvent> advanced = deadlines.stream().map(event -> event.tick(day)).toList();
        for (int i = 0; i < advanced.size(); i++) if (deadlines.get(i).status() == TimedEvent.Status.ACTIVE && advanced.get(i).status() == TimedEvent.Status.EXPIRED) {
            TimedEvent event = advanced.get(i);
            events.add(new WorldEvent(event.id() + "-expired", day, WorldEvent.Type.DEADLINE, WorldEvent.Category.PLOT, event.consequence(), event.effects()));
            activeEffects = merge(activeEffects, event.effects());
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

    /** 复制世界计时和事件状态并保留访问记录。
     * @param world 当前权威世界状态
     * @param revision 目标世界修订号
     * @param day 当前世界天数，以天为单位
     * @param counter 当前威胁计数，单位为天
     * @param level 当前威胁级别标识
     * @param history 按发生顺序排列的威胁历史
     * @param timed 兼容的期限事件文本列表
     * @param incidental 兼容的偶发事件文本列表
     * @param lastTick 最后推进世界的天数
     * @param crisis 是否已触发危机
     * @param activePrices 按分类索引的有效价格修正
     * @param activeEffects 当前持续世界效果
     * @param deadlines 类型化期限事件列表
     * @param events 本轮世界事件列表
     */
    private WorldState copyWorld(WorldState world, long revision, long day, long counter, String level,
                                 List<String> history, List<String> timed, List<String> incidental,
                                 long lastTick, boolean crisis, Map<PriceModifier.Category, PriceModifier> activePrices,
                                 WorldEffects activeEffects,
                                 List<TimedEvent> deadlines, List<WorldEvent> events) {
        return new WorldState(world.worldId(), world.contentVersion(), revision, world.setting(), world.settingDescription(), world.location(), world.currentRegion(), world.mainQuest(), day, world.time(), world.flags(), level, counter, world.threatInterval(), history, timed, incidental, lastTick, crisis, world.mainPlotStage(), world.mainPlotStages(), world.priceModifiers(), world.originScene(), deadlines, events, activePrices, activeEffects, world.visitedLocations());
    }

    /** 按价格分类合并修正值。
     * @param target 待合并的价格修正映射
     * @param modifiers 本轮新增或覆盖的价格修正
     */
    private void applyPrices(Map<PriceModifier.Category, PriceModifier> target, List<PriceModifier> modifiers) { for (PriceModifier modifier : modifiers) target.put(modifier.category(), modifier); }
    /** 按模板权重选出一个世界事件。
     * @param templates 带正权重的事件模板列表
     * @param random 用于加权选择的随机源
     */
    private WorldDefinition.EventTemplate chooseWeighted(List<WorldDefinition.EventTemplate> templates, RandomGenerator random) {
        int totalWeight = templates.stream().mapToInt(WorldDefinition.EventTemplate::weight).sum();
        int draw = random.nextInt(totalWeight);
        for (WorldDefinition.EventTemplate template : templates) {
            draw -= template.weight();
            if (draw < 0) return template;
        }
        return templates.getLast();
    }

    /** 合并世界持续效果并对整数溢出进行检查。
     * @param current 当前持续世界效果
     * @param addition 需要叠加的世界效果
     */
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
    /** 将事件类型标识转换为枚举，未知类型回退为 OTHER。
     * @param value 待转换或验证的字段值
     */
    private static WorldEvent.Type parseType(String value) { try { return WorldEvent.Type.valueOf(value.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException exception) { return WorldEvent.Type.OTHER; } }
    /** 将事件分类标识转换为枚举，未知分类回退为 OTHER。
     * @param value 待转换或验证的字段值
     */
    private static WorldEvent.Category parseCategory(String value) { try { return WorldEvent.Category.valueOf(value.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException exception) { return WorldEvent.Category.OTHER; } }
}
