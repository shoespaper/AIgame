package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

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
     * @param updates 叙事模型声明的世界字段，忽略未知字段和错误类型
     * @return 合并字段后的权威实例及本次确认事件
     */
    public AppliedAction applyNarrativeTurn(String gameId, long expectedRevision, ActionIntent intent,
                                            Map<String, InitializationValue> updates) {
        synchronized (repository) {
            GameInstance<?> loaded = repository.load(gameId)
                    .orElseThrow(() -> new GameRequestException(GameRequestException.Code.SAVE_NOT_FOUND, "Save not found: " + gameId));
            String actionType = intent == null ? null : intent.type();
            GameRequestException.checkRevision(loaded, expectedRevision, actionType);
            AppliedAction result = resolveNarrativeTurn(loaded, intent, updates);
            if (result.instance().revision() != loaded.revision()) repository.save(result.instance());
            return result;
        }
    }

    /** 在给定权威实例上计算叙事结算，由调用者决定何时提交存档。
     * @param loaded 结算前的权威实例，可为尚未保存的开局游戏
     * @param intent 可空的引擎动作
     * @param updates 模型声明的世界字段
     */
    AppliedAction resolveNarrativeTurn(GameInstance<?> loaded, ActionIntent intent, Map<String, InitializationValue> updates) {
        Map<String, InitializationValue> accepted = WorldUpdatePolicy.accepted(updates);
        if (intent == null) {
            if (accepted.isEmpty()) return new AppliedAction(loaded, List.of(), List.of());
            long revision = loaded.revision() + 1;
            WorldState world = loaded.worldState().applyWorldUpdates(accepted).withRevision(revision);
            GameInstance<?> next = loaded.withProgress(world, loaded.rulesetState());
            return new AppliedAction(next, List.of(), List.of());
        }
        if (loaded.rulesetId() == null) {
            throw GameRequestException.action(GameRequestException.Code.ENGINE_REQUIRED, "engine_required",
                    loaded, intent.type(), loaded.revision());
        }
        WorldDefinition definition = contentCatalog.world(loaded.worldId());
        GameEngineRuntime<?, ?> runtime = runtimeFor(loaded, definition);
        EngineResolution<?> resolution = resolveRuntime(runtime, loaded, intent);
        long nextRevision = loaded.revision() + 1;
        WorldState world = loaded.worldState();
        WorldState nextWorld = resolution.advancesDay()
                ? world.advanceDay().withRevision(nextRevision) : world.withRevision(nextRevision);
        List<WorldEvent> newEvents = nextWorld.worldEvents().subList(world.worldEvents().size(), nextWorld.worldEvents().size());
        GameState nextState = resolution.nextState();
        List<ConfirmedEvent> confirmed = new ArrayList<>(resolution.events());
        if (resolution.advancesDay()) {
            EngineResolution<?> afterAdvance = afterWorldAdvance(runtime, nextState, nextWorld.day(),
                    loaded.gameId().hashCode() ^ nextRevision, newEvents);
            nextState = afterAdvance.nextState();
            confirmed.addAll(afterAdvance.events());
        }
        nextWorld = nextWorld.applyWorldUpdates(accepted);
        GameInstance<?> next = loaded.withProgress(nextWorld, nextState);
        return new AppliedAction(next, confirmed, newEvents);
    }

    /** 获取实例选中的引擎运行时。
     * @param instance 当前权威游戏实例
     * @param definition 当前世界模板定义
     */
    private GameEngineRuntime<?, ?> runtimeFor(GameInstance<?> instance, WorldDefinition definition) {
        if (instance.rulesetId() == null) throw new GameRequestException(GameRequestException.Code.ENGINE_REQUIRED, "engine_required");
        return gameEngineFactory.createForGame(instance.rulesetId(), definition, instance.gameId());
    }

    /** 为叙事提供只读引擎运行时，不修改权威状态。
     * @param instance 当前权威游戏实例
     * @param definition 当前世界模板定义
     */
    public GameEngineRuntime<?, ?> runtimeForNarration(GameInstance<?> instance, WorldDefinition definition) {
        return runtimeFor(instance, definition);
    }

    /** 从当前权威实例读取选中引擎给出的可选动作。
     * @param instance 当前游戏实例
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<EngineActionChoice> actionChoices(GameInstance<?> instance) {
        if (instance.rulesetId() == null) return List.of();
        GameEngineRuntime runtime = runtimeFor(instance, contentCatalog.world(instance.worldId()));
        return runtime.actionChoices(instance.rulesetState());
    }

    /** 委托所选引擎校验与结算，并为被拒绝的意图附加存档上下文。
     * @param runtime 选中引擎的运行时
     * @param instance 当前权威游戏实例
     * @param intent 交由选中引擎解释的结构化动作
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EngineResolution<?> resolveRuntime(GameEngineRuntime runtime, GameInstance<?> instance,
                                                       ActionIntent intent) {
        try {
            return runtime.resolve(instance.rulesetState(), intent, instance.worldState());
        } catch (IllegalArgumentException exception) {
            throw GameRequestException.action(GameRequestException.Code.ENGINE_ACTION_REJECTED, exception.getMessage(),
                    instance, intent.type(), instance.revision());
        }
    }

    /** 将新的一天和已确认的事件事实交给所选引擎。
     * @param runtime 选中引擎的运行时
     * @param state 当前引擎类型化状态
     * @param day 当前世界天数，以天为单位
     * @param seed 本轮确定性随机种子
     * @param events 本轮世界事件列表
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EngineResolution<?> afterWorldAdvance(GameEngineRuntime runtime, GameState state, long day, long seed, List<WorldEvent> events) {
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
            Map<PriceModifier.Category, PriceModifier> next = new EnumMap<>(PriceModifier.Category.class);
            next.putAll(world.activePriceModifiers());
            next.remove(remove.category());
            return world.withPriceModifiers(next);
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
            Map<PriceModifier.Category, PriceModifier> next = new EnumMap<>(PriceModifier.Category.class);
            next.putAll(world.activePriceModifiers());
            next.put(modifier.category(), modifier);
            return world.withPriceModifiers(next);
        });
    }

    /** 对当前世界应用变换，推进修订号并保存。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的权威修订号
     * @param update 对世界快照执行的纯变换
     */
    private GameInstance<?> updateWorld(String gameId, long expectedRevision,
                                        UnaryOperator<WorldState> update) {
        synchronized (repository) {
            GameInstance<?> loaded = repository.load(gameId).orElseThrow(() -> new GameRequestException(GameRequestException.Code.SAVE_NOT_FOUND, "Save not found: " + gameId));
            GameRequestException.checkRevision(loaded, expectedRevision, null);
            WorldState changed = update.apply(loaded.worldState()).withRevision(loaded.revision() + 1);
            GameInstance<?> next = loaded.withProgress(changed, loaded.rulesetState());
            repository.save(next);
            return next;
        }
    }

}
