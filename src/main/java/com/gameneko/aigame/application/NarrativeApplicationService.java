package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.SupportedAction;
import com.gameneko.aigame.narrative.NarrativeRequest;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshotFactory;
import com.gameneko.aigame.narrative.NarrationService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 编排叙事与引擎结算，保持权威状态由服务端持有：action 为空时纯叙事，非空时经引擎校验并结算。 */
@Service
public final class NarrativeApplicationService {
    private final GameRepository repository;
    private final ContentCatalog contentCatalog;
    private final NarrationService narrationService;
    private final GameActionService gameActionService;

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
        var snapshot = NarrativeSnapshotFactory.from(instance, List.of(), instance.worldState().worldEvents(), projection(instance))
                .withWorldbook(contentCatalog.worldBook(instance.worldId()).narrative());
        var response = narrationService.narrate(new NarrativeRequest(gameId, expectedRevision, playerInput, locale, snapshot, history),
                supportedActions(instance));
        return settle(instance, response);
    }

    /** 生成游戏开局的首段叙事；action 非空时结算并持久化。 */
    /** @param gameId 当前游戏标识 */
    /** @param revision 保存版本 */
    /** @param locale 叙事语言标识 */
    public NarratedTurn opening(String gameId, long revision, String locale) {
        GameInstance<?> instance = load(gameId, revision);
        var snapshot = NarrativeSnapshotFactory.from(instance, List.of(), instance.worldState().worldEvents(), projection(instance))
                .withWorldbook(contentCatalog.worldBook(instance.worldId()).narrative());
        var response = narrationService.opening(new NarrativeRequest(gameId, revision, "", locale, snapshot),
                supportedActions(instance));
        return settle(instance, response);
    }

    /** 依据模型输出决定纯叙事还是引擎结算，并返回带候选与确认事件的回合。 */
    /** @param instance 结算前的权威游戏实例 @param response 模型叙事响应 */
    private NarratedTurn settle(GameInstance<?> instance, NarrativeResponse response) {
        if (response.action() == null) {
            return new NarratedTurn(instance, response.narration(), response.options(), List.of());
        }
        var applied = gameActionService.applyIntent(instance.gameId(), instance.revision(), response.action());
        var confirmed = NarrativeSnapshotFactory.fromConfirmed(applied.instance(), applied.events(),
                applied.worldEvents(), projection(applied.instance())).confirmedEvents();
        return new NarratedTurn(applied.instance(), response.narration(), response.options(), confirmed);
    }

    /** 叙事回合：权威实例、叙事文本、候选行动与本次结算确认事件。 */
    /** @param instance 结算后的权威游戏实例 */
    /** @param narration 基于确认状态生成的叙事文本 */
    /** @param options 引导玩家下一步的纯文本候选行动 */
    /** @param confirmedEvents 本次引擎与世界层确认事件 */
    public record NarratedTurn(GameInstance<?> instance, String narration, List<String> options,
                               List<com.gameneko.aigame.narrative.NarrativeSnapshot.ConfirmedEvent> confirmedEvents) {
        public NarratedTurn {
            options = List.copyOf(options);
            confirmedEvents = List.copyOf(confirmedEvents);
        }
    }

    private GameInstance<?> load(String gameId, long expectedRevision) {
        GameInstance<?> loaded = repository.load(gameId).orElseThrow(() -> new IllegalArgumentException("Save not found: " + gameId));
        if (expectedRevision != loaded.revision()) throw new IllegalArgumentException("Revision mismatch: expected " + expectedRevision + ", current " + loaded.revision());
        return loaded;
    }

    /** 读取当前引擎可执行的动作清单；纯叙事世界返回空列表。 */
    /** @param instance 当前游戏实例 */
    private List<SupportedAction> supportedActions(GameInstance<?> instance) {
        var runtime = runtimeFor(instance);
        return runtime == null ? List.of() : runtime.supportedActions();
    }

    private GameEngineRuntime<?, ?> runtimeFor(GameInstance<?> instance) {
        if (instance.rulesetId() == null) return null;
        return gameActionService.runtimeForNarration(instance, contentCatalog.world(instance.worldId()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object projection(GameInstance<?> instance) {
        if (instance.rulesetId() == null) return null;
        GameEngineRuntime runtime = gameActionService.runtimeForNarration(instance, contentCatalog.world(instance.worldId()));
        return runtime.project(instance.rulesetState());
    }
}
