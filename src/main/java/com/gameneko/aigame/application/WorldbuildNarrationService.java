package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.WorldbookRepository;
import com.gameneko.aigame.content.TemplateCatalog;
import com.gameneko.aigame.content.WorldBook;
import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.content.WorldTemplate;
import com.gameneko.aigame.content.WorldbookDefinition;
import com.gameneko.aigame.narrative.NarrativeProviderException;
import com.gameneko.aigame.narrative.NarrativeRequest.ConversationMessage;
import com.gameneko.aigame.narrative.WorldDraftModel;
import com.gameneko.aigame.narrative.WorldDraftModel.Generation;
import com.gameneko.aigame.narrative.WorldDraftModel.GenerationInput;
import com.gameneko.aigame.narrative.WorldDraftModel.Identity;
import com.gameneko.aigame.narrative.WorldDraftModel.ReviewInput;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 生成、验收并保存用于开局的世界书正文。 */
@Service
public final class WorldbuildNarrationService {
    private static final int MAX_REVISIONS = 2;

    private final WorldDraftModel model;
    private final WorldbookRepository repository;
    private final WorldBookCatalog worlds;
    private final TemplateCatalog templates;

    /** 装配世界草案所需的模型、仓储和已有类型来源。
     * @param model 返回已校验的草案、验收结论和世界身份
     * @param repository 自定义世界书仓储
     * @param worlds 可用于查找已使用类型的世界书目录
     * @param templates 可用于查找已使用类型的世界模板目录
     */
    public WorldbuildNarrationService(WorldDraftModel model, WorldbookRepository repository,
                                      WorldBookCatalog worlds, TemplateCatalog templates) {
        this.model = model;
        this.repository = repository;
        this.worlds = worlds;
        this.templates = templates;
    }

    /** 创建无需调用模型的空白会话。
     * @param id 会话稳定标识
     */
    public Session session(String id) {
        return new Session(id);
    }

    /** 处理玩家输入，只有通过验收的候选才能替换草案。
     * @param session 当前世界构建会话
     * @param input 本轮自然语言输入，非空且最长 8000 字符
     */
    public Turn answer(Session session, String input) {
        if (input == null || input.isBlank() || input.length() > 8000) {
            throw GameRequestException.invalid("请输入 1 至 8000 个字符的世界描述或修改意见");
        }
        synchronized (session) {
            String playerInput = input.strip();
            String candidate = "";
            List<String> issues = List.of();
            for (int revision = 0; revision <= MAX_REVISIONS; revision++) {
                Generation generated = model.generate(
                        new GenerationInput(session.turn.draft(), session.history, playerInput, candidate, issues),
                        session.turn.done() && revision == 0);
                if (generated.revisionIssue() != null) {
                    issues = List.of(generated.revisionIssue());
                    continue;
                }
                if (generated.draft() == null) {
                    return commit(session, playerInput, generated.reply(), session.turn.draft(), session.turn.version());
                }

                candidate = generated.draft();
                issues = model.review(new ReviewInput(candidate, session.turn.draft(), session.history, playerInput));
                if (issues.isEmpty()) {
                    long version = candidate.equals(session.turn.draft())
                            ? session.turn.version() : session.turn.version() + 1;
                    return commit(session, playerInput, generated.reply(), candidate, version);
                }
            }
            throw NarrativeProviderException.invalidOutput("世界草案未通过验收：" + String.join("；", issues), null);
        }
    }

    /** 保存指定已验收版本，并复用同版本的成功保存结果。
     * @param session 当前世界构建会话
     * @param version 玩家确认保存的草案版本
     */
    public WorldbookDefinition save(Session session, long version) {
        synchronized (session) {
            if (!session.turn.done() || version != session.turn.version()) {
                throw GameRequestException.invalid("当前草案版本不可保存，请刷新草案后重试");
            }
            if (session.saved != null && session.savedVersion == version) return session.saved;
            List<String> knownTypes = Stream.concat(
                    worlds.available().stream().map(WorldBook::type),
                    templates.availableTemplates().stream().map(WorldTemplate::type))
                    .filter(WorldbookDefinition::isValidTypeId).distinct().sorted().toList();
            Identity identity = model.identify(session.turn.draft(), knownTypes);
            WorldbookDefinition saved = new WorldbookDefinition("custom_" + session.id + "_" + version, "1.0",
                    identity.name(), identity.type(), "", "balanced", session.turn.draft(), List.of(), List.of(), List.of(), Map.of());
            repository.save(saved);
            session.saved = saved;
            session.savedVersion = version;
            return saved;
        }
    }

    /** 提交成功的对话和已验收的草案。
     * @param session 当前已加锁的会话
     * @param player 玩家本轮输入
     * @param reply GM 给玩家的回复
     * @param draft 已通过验收的 Markdown 正文
     * @param version 正文版本，内容未变时保持原值
     */
    private static Turn commit(Session session, String player, String reply, String draft, long version) {
        session.history.add(new ConversationMessage("player", player));
        session.history.add(new ConversationMessage("gm", reply));
        session.turn = new Turn(reply, draft, version, true);
        return session.turn;
    }

    /** 保存单个世界构建会话的已验收内容及保存结果。 */
    public static final class Session {
        private final String id;
        private final List<ConversationMessage> history = new ArrayList<>();
        private Turn turn = new Turn("", "", 0, false);
        private WorldbookDefinition saved;
        private long savedVersion;

        /** 创建空白世界构建会话。
         * @param id 会话稳定标识
         */
        private Session(String id) {
            this.id = id;
        }

        /** 读取当前会话回合。 */
        public synchronized Turn turn() {
            return turn;
        }
    }

    /** 返回页面可展示的会话视图。
     * @param reply 本轮 GM 回复，首次进入为空
     * @param draft 当前已验收的 Markdown 正文，首次进入为空
     * @param version 草案版本，从 1 开始，0 表示尚无草案
     * @param done 是否已有可进入下一步的草案
     */
    public record Turn(String reply, String draft, long version, boolean done) {}

}
