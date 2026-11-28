package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.application.port.GameRepository.NarrationReceipt;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.engine.EngineActionChoice;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.SupportedAction;
import com.gameneko.aigame.engine.WorldState.WorldActionRejected;
import com.gameneko.aigame.narrative.NarrativeRequest;
import com.gameneko.aigame.narrative.NarrativeProviderException;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshot;
import com.gameneko.aigame.narrative.NarrativeSnapshot.ConfirmedEvent;
import com.gameneko.aigame.narrative.NarrativeSnapshotFactory;
import com.gameneko.aigame.narrative.NarrationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import com.gameneko.aigame.persistence.ConfirmedTemplateRegistry;
import org.springframework.beans.factory.annotation.Autowired;

/** 编排叙事与引擎结算，保持权威状态由服务端持有：世界声明与经引擎校验的动作共同结算一次。 */
@Service
public final class NarrativeApplicationService {
    public static final int MAX_MODEL_ATTEMPTS = 5;
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

    /** 在同一个预算内生成、校验并提交自由文本回合。
     * @param gameId 游戏存档标识
     * @param revision 客户端观察到的权威修订号
     * @param turnId 本轮可用于断线恢复的标识
     * @param input 玩家原文
     * @param locale 叙事语言标识
     * @param history 仅供模型参考的近期对话
     * @param onFailure 报告一次失败的尝试
     * @param onCommit 在后续描写前发布权威结算回执
     * @return 完成的回合，所有机会耗尽时为空
     */
    public Optional<NarratedTurn> retryNarration(String gameId, long revision, UUID turnId, String input,
                                                String locale, List<NarrativeRequest.ConversationMessage> history,
                                                Consumer<AttemptFailure> onFailure, Consumer<NarratedTurn> onCommit) {
        Optional<NarratedTurn> recovered = recoverTurn(gameId, turnId);
        if (recovered.isPresent()) return recovered;
        GameInstance<?> instance = load(gameId, revision);
        NarrativeRequest request = new NarrativeRequest(gameId, revision, input, locale, snapshot(instance), history);
        List<SupportedAction> actions = supportedActions(instance);
        String correction = null;
        for (int attempt = 1; attempt <= MAX_MODEL_ATTEMPTS; attempt++) {
            NarrativeResponse response;
            GameActionService.AppliedAction applied;
            try {
                response = narrationService.proposeOnce(request, actions, correction);
                if (response.action() == null && response.updates().isEmpty()) {
                    return Optional.of(turn(load(gameId, revision), response.narration(), response.options(), List.of()));
                }
                applied = gameActionService.applyNarrativeTurn(gameId, revision, response.action(), response.updates(), turnId);
            } catch (NarrativeProviderException failure) {
                RetryReason reason = retryReason(failure);
                onFailure.accept(new AttemptFailure(attempt, reason.message()));
                if (!reason.retryable()) throw failure;
                correction = reason.correction();
                failure.pauseBeforeRetry(attempt, MAX_MODEL_ATTEMPTS);
                continue;
            } catch (GameRequestException failure) {
                if (failure.code() != GameRequestException.Code.ENGINE_ACTION_REJECTED
                        && failure.code() != GameRequestException.Code.ENGINE_REQUIRED) throw failure;
                onFailure.accept(new AttemptFailure(attempt, "动作未通过引擎校验"));
                correction = "上一份回复的引擎动作被拒绝。核对可用动作、参数约束和权威快照中已登记的目标标识；如无法执行，请描写可行的下一步。";
                continue;
            } catch (WorldActionRejected failure) {
                onFailure.accept(new AttemptFailure(attempt, "世界变更未通过校验"));
                correction = "上一份回复中的世界变更被拒绝。按当前快照重新选择有效的变更；没有变更时返回空对象。";
                continue;
            }
            return Optional.of(finishRetriedTurn(applied, response, request, attempt, onFailure, onCommit));
        }
        return Optional.empty();
    }

    /** 读取同一次存档提交中的结算回执，用最新权威实例恢复显示。
     * @param gameId 游戏存档标识
     * @param turnId 客户端生成的回合标识
     */
    public Optional<NarratedTurn> recoverTurn(String gameId, UUID turnId) {
        synchronized (repository) {
            Optional<NarrationReceipt> receipt = repository.narrationReceipt(gameId, turnId);
            if (receipt.isEmpty()) return Optional.empty();
            GameInstance<?> current = repository.load(gameId)
                    .orElseThrow(() -> new GameRequestException(GameRequestException.Code.SAVE_NOT_FOUND, "存档已不存在。"));
            NarrationReceipt confirmed = receipt.get();
            NarrativeSnapshot snapshot = NarrativeSnapshotFactory.fromConfirmed(current, confirmed.events(),
                    confirmed.worldEvents(), null);
            return Optional.of(confirmedOnly(current, snapshot.confirmedEvents()));
        }
    }

    /** 发布结算回执，并在预算许可时调用一次模型描写结果。
     * @param applied 已结算的实例和确认事件
     * @param response 校验通过的模型提案
     * @param request 同一回合的原始请求
     * @param attempt 成功提交的尝试序号
     * @param onFailure 发送一次描写失败提示
     * @param onCommit 发送权威结算回执
     */
    private NarratedTurn finishRetriedTurn(GameActionService.AppliedAction applied, NarrativeResponse response,
                                           NarrativeRequest request, int attempt, Consumer<AttemptFailure> onFailure,
                                           Consumer<NarratedTurn> onCommit) {
        GameInstance<?> instance = applied.instance();
        if (instance.revision() == request.expectedRevision()) {
            return turn(instance, response.narration(), response.options(), List.of());
        }
        List<ConfirmedEvent> events = NarrativeSnapshotFactory.fromConfirmed(instance, applied.events(),
                applied.worldEvents(), null).confirmedEvents();
        NarratedTurn receipt = confirmedOnly(instance, events);
        onCommit.accept(receipt);
        if (events.isEmpty()) return turn(instance, response.narration(), response.options(), events);
        if (attempt >= MAX_MODEL_ATTEMPTS) return receipt;
        try {
            NarrativeSnapshot confirmed = confirmedSnapshot(applied);
            NarrativeResponse description = narrationService.describeConfirmedTurnOnce(new NarrativeRequest(
                    instance.gameId(), instance.revision(), request.playerInput(), request.locale(), confirmed, request.history()));
            return turn(instance, description.narration(), description.options(), events);
        } catch (NarrativeProviderException | GameRequestException failure) {
            onFailure.accept(new AttemptFailure(attempt + 1, "后续描写未生成，行动已结算"));
            return receipt;
        }
    }

    /** 将模型错误转换成界面文案和可安全发送给模型的纠正指令。
     * @param failure 模型入口报告的归一化异常
     */
    private static RetryReason retryReason(NarrativeProviderException failure) {
        return switch (failure.reasonCode()) {
            case "empty_output" -> new RetryReason("GM 回复为空", "上一次回复只有空白。请返回完整 JSON 对象，包含叙事和有效选项。", true);
            case "output_parse_failed", "invalid_model_output", "response_decoding_failed", "missing_choice" ->
                    new RetryReason("GM 回复格式有误", "上一次回复不符合协议。请只返回完整 JSON，检查每个必填字段、参数类型和选项长度。", true);
            case "output_truncated", "incomplete_output" ->
                    new RetryReason("GM 回复不完整", "上一次回复没有完整结束。请缩短叙事和选项，并完整闭合 JSON 对象。", true);
            default -> failure.retryable()
                    ? new RetryReason("暂时无法取得 GM 回复", "上一次请求遭遇瞬时服务错误，未取得可用回复。请根据原请求重新生成完整答复。", true)
                    : new RetryReason("GM 暂时无法回复", null, false);
        };
    }

    /** 一次调用失败的用户可见摘要。
     * @param attempt 从 1 开始的调用序号
     * @param message 面向玩家的简短失败原因
     */
    public record AttemptFailure(int attempt, String message) {}

    /** 一种失败和下一次调用可用的纠正指令。
     * @param message 面向玩家的简短说明
     * @param correction 可空的模型纠正指令
     * @param retryable 是否允许再尝试
     */
    private record RetryReason(String message, String correction, boolean retryable) {}

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
        NarrativeSnapshot snapshot = confirmedSnapshot(applied);
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

    /** 生成确认事件和当前状态组成的描写快照。
     * @param applied 已完成的引擎和世界状态结算
     */
    private NarrativeSnapshot confirmedSnapshot(GameActionService.AppliedAction applied) {
        GameInstance<?> instance = applied.instance();
        return NarrativeSnapshotFactory.fromConfirmed(instance, applied.events(), applied.worldEvents(), projection(instance))
                .withWorldbook(contentCatalog.worldBook(instance.worldId()).narrative())
                .withMaterials(templates == null ? null : templates.world(instance.gameId()));
    }

    /** 在模型无法描写时直接展示已确认的事实，避免客户端误以为结算失败。
     * @param instance 已结算的权威实例
     * @param events 本轮确认事件
     */
    private NarratedTurn confirmedOnly(GameInstance<?> instance, List<ConfirmedEvent> events) {
        String description = events.isEmpty() ? "本轮状态已更新。"
                : String.join("\n", events.stream().map(ConfirmedEvent::outcome).toList());
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
