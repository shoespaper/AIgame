package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.engine.EngineActionChoice;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.SupportedAction;
import com.gameneko.aigame.narrative.NarrativeRequest;
import com.gameneko.aigame.narrative.NarrativeProviderException;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshot;
import com.gameneko.aigame.narrative.NarrativeSnapshot.ConfirmedEvent;
import com.gameneko.aigame.narrative.NarrativeSnapshotFactory;
import com.gameneko.aigame.narrative.NarrationService;
import org.springframework.stereotype.Service;

import java.util.List;
import com.gameneko.aigame.persistence.ConfirmedTemplateRegistry;
import org.springframework.beans.factory.annotation.Autowired;

/** 编排叙事与引擎结算，保持权威状态由服务端持有：世界声明与经引擎校验的动作共同结算一次。 */
@Service
public final class NarrativeApplicationService {
    private final GameRepository repository;
    private final ContentCatalog contentCatalog;
    private final NarrationService narrationService;
    private final GameActionService gameActionService;

    private ConfirmedTemplateRegistry templates;

    /** 注入已注册素材目录。
     * @param registry 开局确认后的模板目录
     */
    @Autowired
    void configureTemplates(ConfirmedTemplateRegistry registry) { templates = registry; }

    /** 装配叙事、内容读取及统一回合结算依赖。
     * @param repository 游戏存档端口
     * @param contentCatalog 世界书和模板目录
     * @param narrationService 模型叙事服务
     * @param gameActionService 动作及世界声明的统一结算服务
     */
    public NarrativeApplicationService(GameRepository repository, ContentCatalog contentCatalog,
                                       NarrationService narrationService,
                                       GameActionService gameActionService) {
        this.repository = repository;
        this.contentCatalog = contentCatalog;
        this.narrationService = narrationService;
        this.gameActionService = gameActionService;
    }

    /** 使用最新状态及世界书叙事设定生成当前回合；action 非空时结算并持久化。 */
    /** @param gameId 游戏标识 */
    /** @param expectedRevision 客户端观察到的修订号 */
    /** @param playerInput 玩家自由输入 */
    /** @param locale 叙事语言标识 */
    /** @return 叙事与可能发生的引擎结算结果 */
    public NarratedTurn narrate(String gameId, long expectedRevision, String playerInput, String locale) {
        return narrate(gameId, expectedRevision, playerInput, locale, List.of());
    }

    /** 使用最新状态、世界书与近期对话生成回合；对话仅作为模型叙事上下文。 */
    /** @param gameId 游戏标识 @param expectedRevision 客户端观察到的修订号 @param playerInput 玩家本轮输入 */
    /** @param locale 叙事语言标识 @param history 仅供模型参考的近期对话 */
    /** @return 叙事与可能发生的引擎结算结果 */
    public NarratedTurn narrate(String gameId, long expectedRevision, String playerInput, String locale,
                                List<NarrativeRequest.ConversationMessage> history) {
        GameInstance<?> instance = load(gameId, expectedRevision);
        NarrativeResponse response = narrationService.narrate(
                new NarrativeRequest(gameId, expectedRevision, playerInput, locale, snapshot(instance), history),
                supportedActions(instance));
        if (response.action() == null && response.updates().isEmpty()) {
            return turn(instance, response.narration(), response.options(), List.of());
        }
        GameActionService.AppliedAction applied = gameActionService.applyNarrativeTurn(
                instance.gameId(), instance.revision(), response.action(), response.updates());
        return toTurn(applied, response, playerInput, locale, history);
    }

    /** 按当前权威状态解析按钮标识，直接结算后再交给 GM 描写。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端读取到的修订号
     * @param choiceId 引擎提供的行动标识
     * @param locale 叙事语言标识
     * @param history 仅供模型参考的近期对话
     */
    public NarratedTurn choose(String gameId, long expectedRevision, String choiceId, String locale,
                               List<NarrativeRequest.ConversationMessage> history) {
        GameInstance<?> instance = load(gameId, expectedRevision);
        EngineActionChoice choice = gameActionService.actionChoices(instance).stream()
                .filter(candidate -> candidate.id().equals(choiceId)).findFirst()
                .orElseThrow(() -> GameRequestException.invalid("此行动已不可用，请选择当前可用的行动。"));
        return describeTurn(gameActionService.applyIntent(gameId, expectedRevision, choice.intent()),
                choice.label(), locale, history);
    }

    /** 在尚未存档的初始实例上生成并结算开场，返回可一次性持久化的结果。
     * @param instance 按确认答案建立的初始游戏实例
     * @param locale 开场叙事语言标识
     */
    public NarratedTurn prepareOpening(GameInstance<?> instance, String locale) {
        NarrativeResponse response = narrationService.opening(
                new NarrativeRequest(instance.gameId(), instance.revision(), "", locale, snapshot(instance)), supportedActions(instance));
        if (response.action() == null && response.updates().isEmpty()) {
            return turn(instance, response.narration(), response.options(), List.of());
        }
        GameActionService.AppliedAction applied = gameActionService.resolveNarrativeTurn(instance, response.action(), response.updates());
        return toTurn(applied, response, "游戏开始", locale, List.of());
    }

    /** 将权威实例、世界书和已登记素材转换为只读的模型快照。
     * @param instance 当前权威游戏实例
     */
    private NarrativeSnapshot snapshot(GameInstance<?> instance) {
        return NarrativeSnapshotFactory.from(instance, List.of(), instance.worldState().worldEvents(), projection(instance))
                .withWorldbook(contentCatalog.worldBook(instance.worldId()).narrative())
                .withMaterials(templates == null ? null : templates.world(instance.gameId()));
    }

    /** 用结算后的快照生成叙事，描写失败时仍返回已确认的结果。
     * @param applied 结算后的权威实例及确认事件
     * @param response 模型的首轮响应，没有确认事件时直接使用
     * @param playerInput 玩家原始输入，开局时为开场提示
     * @param locale 叙事语言标识
     * @param history 近期对话记录，开局时为空
     */
    private NarratedTurn toTurn(GameActionService.AppliedAction applied, NarrativeResponse response,
                                String playerInput, String locale, List<NarrativeRequest.ConversationMessage> history) {
        if (applied.events().isEmpty() && applied.worldEvents().isEmpty()) {
            return turn(applied.instance(), response.narration(), response.options(), List.of());
        }
        return describeTurn(applied, playerInput, locale, history);
    }

    /** 将已确认的动作结算交给 GM 描写。
     * @param applied 已结算的实例及事件
     * @param playerInput 玩家输入或引擎选项文本
     * @param locale 叙事语言标识
     * @param history 仅供模型参考的近期对话
     */
    private NarratedTurn describeTurn(GameActionService.AppliedAction applied, String playerInput, String locale,
                                      List<NarrativeRequest.ConversationMessage> history) {
        GameInstance<?> instance = applied.instance();
        NarrativeSnapshot snapshot = NarrativeSnapshotFactory.fromConfirmed(instance, applied.events(),
                        applied.worldEvents(), projection(instance))
                .withWorldbook(contentCatalog.worldBook(instance.worldId()).narrative())
                .withMaterials(templates == null ? null : templates.world(instance.gameId()));
        try {
            NarrativeResponse description = narrationService.describeConfirmedTurn(new NarrativeRequest(
                    instance.gameId(), instance.revision(), playerInput, locale, snapshot, history));
            return turn(instance, description.narration(), description.options(), snapshot.confirmedEvents());
        } catch (NarrativeProviderException exception) {
            return confirmedOnly(instance, snapshot.confirmedEvents());
        } catch (GameRequestException exception) {
            if (exception.code() != GameRequestException.Code.MODEL_CONFIGURATION_REQUIRED) throw exception;
            return confirmedOnly(instance, snapshot.confirmedEvents());
        }
    }

    /** 在模型无法描写时直接展示已确认的事实，避免客户端误以为结算失败。
     * @param instance 已结算的权威实例
     * @param events 本轮确认事件
     */
    private NarratedTurn confirmedOnly(GameInstance<?> instance, List<ConfirmedEvent> events) {
        String description = String.join("\n", events.stream().map(ConfirmedEvent::outcome).toList());
        return turn(instance, description, List.of("继续行动", "观察四周", "查看状态"), events);
    }

    /** 使用同一份权威实例生成回合和可执行选项。
     * @param instance 当前权威实例
     * @param narration 本轮叙事文字
     * @param options GM 提供的文本建议
     * @param events 本轮已确认事件
     */
    private NarratedTurn turn(GameInstance<?> instance, String narration, List<String> options,
                              List<ConfirmedEvent> events) {
        return new NarratedTurn(instance, narration, options, events, gameActionService.actionChoices(instance));
    }

    /** 包含叙事、权威实例与可选动作的回合结果。
     * @param instance 结算后的权威游戏实例
     * @param narration 基于确认状态生成的叙事文本
     * @param options 引导玩家下一步的纯文本建议
     * @param confirmedEvents 本次引擎与世界层确认事件
     * @param actionChoices 当前引擎提供的直接行动
     */
    public record NarratedTurn(GameInstance<?> instance, String narration, List<String> options,
                               List<ConfirmedEvent> confirmedEvents, List<EngineActionChoice> actionChoices) {
        /** 复制选项和确认事件，保持返回快照不可变。
         * @param instance 当前权威实例
         * @param narration 本轮叙事
         * @param options GM 的文本建议
         * @param confirmedEvents 本轮已确认事件
         * @param actionChoices 当前引擎可执行选项
         */
        public NarratedTurn {
            options = List.copyOf(options);
            confirmedEvents = List.copyOf(confirmedEvents);
            actionChoices = List.copyOf(actionChoices);
        }
    }

    /** 读取权威存档并校验客户端修订号。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的权威修订号
     */
    private GameInstance<?> load(String gameId, long expectedRevision) {
        GameInstance<?> loaded = repository.load(gameId).orElseThrow(() -> new GameRequestException(GameRequestException.Code.SAVE_NOT_FOUND, "Save not found: " + gameId));
        GameRequestException.checkRevision(loaded, expectedRevision, null);
        return loaded;
    }

    /** 读取当前引擎可执行的动作清单；纯叙事世界返回空列表。 */
    /** @param instance 当前游戏实例 */
    private List<SupportedAction> supportedActions(GameInstance<?> instance) {
        var runtime = runtimeFor(instance);
        return runtime == null ? List.of() : runtime.supportedActions();
    }

    /** 获取实例选中的引擎运行时。
     * @param instance 当前权威游戏实例
     */
    private GameEngineRuntime<?, ?> runtimeFor(GameInstance<?> instance) {
        if (instance.rulesetId() == null) return null;
        return gameActionService.runtimeForNarration(instance, contentCatalog.world(instance.worldId()));
    }

    /** 通过选中引擎生成只读投影，不读取具体引擎字段。
     * @param instance 当前权威游戏实例
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object projection(GameInstance<?> instance) {
        if (instance.rulesetId() == null) return null;
        GameEngineRuntime runtime = gameActionService.runtimeForNarration(instance, contentCatalog.world(instance.worldId()));
        return runtime.project(instance.rulesetState());
    }
}
