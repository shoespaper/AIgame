package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.NarrativePrompt;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.persistence.ConfirmedTemplateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证应用装配和游戏 API 的初始化、持久化与回合入口。 */
@SpringBootTest(properties = {
        "aigame.persistence.root=${java.io.tmpdir}/aigame-tests",
        "aigame.templates.registry=${java.io.tmpdir}/aigame-templates-${random.uuid}/registry.json"
})
@AutoConfigureMockMvc
class AIgameApplicationTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfirmedTemplateRegistry templates;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private NarrativeProvider narrativeProvider;

    /** 用确定性模型桩隔离 HTTP 集成测试，避免开发者本地 API 设置和外部模型影响回归。 */
    @BeforeEach
    void configureNarrativeProvider() {
        when(narrativeProvider.respond(any()))
                .thenReturn(new NarrativeResponse("确认后的行动已经发生。", null, List.of("继续前进", "就地休息", "查看背包")));
    }

    @Test
    void contextLoads() {
    }

    /** 验证同一内置世界书可用纯叙事和 AP 开局，并复用内置规则与素材。 */
    @Test
    void sameWorldbookOpensWithOrWithoutAp() throws Exception {
        mockMvc.perform(post("/api/games/template-story/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"cinder_continent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetId").doesNotExist());
        mockMvc.perform(post("/api/games/template-ap/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"cinder_continent\",\"engineId\":\"ap\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetId").value("ap"));

        assertTrue(templates.world("template-story").at("/skill_types/0/engines").isMissingNode());
        assertTrue(templates.world("template-ap").at("/skill_types/0/engines/ap/template_id").isTextual());
        verifyNoInteractions(narrativeProvider);
    }

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void initializesWorldFromJsonRequest() throws Exception {
        mockMvc.perform(post("/api/worlds/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"worldId":"isekai_grey","name":"灰烬界",
                                 "location":"白栎镇","region":"forest","time":"黄昏"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldId").value("isekai_grey"))
                .andExpect(jsonPath("$.contentVersion").value("1.0"))
                .andExpect(jsonPath("$.day").value(1))
                .andExpect(jsonPath("$.time").value("黄昏"))
                .andExpect(jsonPath("$.weather").doesNotExist())
                .andExpect(jsonPath("$.season").doesNotExist())
                .andExpect(jsonPath("$.currentRegion").value("forest"))
                .andExpect(jsonPath("$.threatLevel").value("Dormant"));
    }

    @Test
    void rejectsUnknownWorldFromJsonRequest() throws Exception {
        mockMvc.perform(post("/api/worlds/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"missing\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unsupported world: missing"));
    }

    @Test
    void rejectsMalformedJsonRequest() throws Exception {
        mockMvc.perform(post("/api/worlds/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{bad-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body must be valid JSON"));
    }

    @Test
    void savesAndLoadsWorldStateThroughApi() throws Exception {
        mockMvc.perform(post("/api/games/api-test/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\",\"engineId\":\"ap\",\"region\":\"forest\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/api-test/save")
                        .contentType(APPLICATION_JSON).content("{\"expectedRevision\":0}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/games/api-test/save"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldId").value("isekai_grey"))
                .andExpect(jsonPath("$.day").value(1));
    }

    @Test
    void saveEndpointDoesNotAcceptStatePatchFields() throws Exception {
        mockMvc.perform(post("/api/games/protected/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/protected/save")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"worldId\":\"forged\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/games/protected/save"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldId").value("isekai_grey"));
    }

    @Test
    void saveEndpointRejectsStaleRevision() throws Exception {
        mockMvc.perform(post("/api/games/stale/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/stale/save")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_MISMATCH"))
                .andExpect(jsonPath("$.error").value("Revision mismatch: expected 1, current 0"));
    }

    @Test
    void initializesAndPersistsCompleteGameInstance() throws Exception {
        mockMvc.perform(post("/api/games/instance-test/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\",\"engineId\":\"ap\",\"region\":\"forest\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value("instance-test"))
                .andExpect(jsonPath("$.rulesetId").value("ap"))
                .andExpect(jsonPath("$.rulesetVersion").value("baseline-1"))
                .andExpect(jsonPath("$.lifecycle").value("INITIALIZED"))
                .andExpect(jsonPath("$.world.worldId").value("isekai_grey"));

        mockMvc.perform(get("/api/games/instance-test/save"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldId").value("isekai_grey"));

        mockMvc.perform(get("/api/games/instance-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value("instance-test"))
                .andExpect(jsonPath("$.rulesetState.characterName").exists())
                .andExpect(jsonPath("$.rulesetState.resources.rations").value(3));
    }

    @Test
    void actionEndpointPersistsUpdatedRulesetStateForResume() throws Exception {
        mockMvc.perform(post("/api/games/action-api-test/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\",\"engineId\":\"ap\",\"answers\":{\"characterName\":{\"type\":\"text\",\"value\":\"Traveler\"}}}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/action-api-test/actions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"type\":\"advance_day\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.world.day").value(2))
                .andExpect(jsonPath("$.rulesetState.resources.rations").value(2));

        mockMvc.perform(get("/api/games/action-api-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.rulesetState.characterName").value("Traveler"))
                .andExpect(jsonPath("$.rulesetState.resources.rations").value(2));
    }

    @Test
    void experienceActionEndpointPersistsLevelUp() throws Exception {
        mockMvc.perform(post("/api/games/xp-api-test/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\",\"engineId\":\"ap\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/xp-api-test/actions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"type\":\"grant_experience\",\"arguments\":[{\"name\":\"amount\",\"type\":\"integer\",\"integer\":100}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.rulesetState.level").value(2))
                .andExpect(jsonPath("$.rulesetState.xp").value(0))
                .andExpect(jsonPath("$.rulesetState.xpToNext").value(200));
    }

    /** 验证 AI 创作后按实例 ID 读取模板，结算伤害、治疗和防御。 */
    @Test
    void apNarrationLearnsAndCastsBoundAbilityInstances() throws Exception {
        String gameId = "ap-bound-abilities";
        openApInTown(gameId)
                .andExpect(jsonPath("$.rulesetState.hpMax").value(34))
                .andExpect(jsonPath("$.rulesetState.uniqueTalent.type").value("fog_listener"));

        performAction(gameId, 0, "create_skill", Map.of("abilityId", "custom_strike", "name", "雾刃", "template_id", "missing"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value(containsString("未知 AP 能力模板")));
        performAction(gameId, 0, "create_skill", Map.of("abilityId", "custom_strike", "name", "雾刃", "template_id", "npc_guide"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value(containsString("模板类别")));

        ActionIntent created = new ActionIntent("create_skill", "", List.of(
                new ActionIntent.Argument("abilityId", new InitializationValue.Text("custom_strike")),
                new ActionIntent.Argument("name", new InitializationValue.Text("雾刃")),
                new ActionIntent.Argument("template_id", new InitializationValue.Text("skill_single_target_strike"))));
        when(narrativeProvider.respond(any())).thenReturn(
                new NarrativeResponse("你试着领会这一式。", created, List.of("继续前进")),
                new NarrativeResponse("你已经学会雾刃。", null, List.of("继续前进")));
        mockMvc.perform(post("/api/games/{gameId}/narration", gameId).contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"playerInput\":\"领会雾刃\",\"locale\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.narration").value("你已经学会雾刃。"))
                .andExpect(jsonPath("$.game.revision").value(1))
                .andExpect(jsonPath("$.game.rulesetState.skills[0].templateId").value("skill_single_target_strike"))
                .andExpect(jsonPath("$.confirmedEvents[0].target").value("custom_strike"));
        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(narrativeProvider, times(2)).respond(prompts.capture());
        assertEquals("custom_strike", prompts.getValue().snapshot().confirmedEvents().getFirst().target());
        assertTrue(prompts.getValue().systemInstruction().contains("描写本轮已经发生的结果"));

        performAction(gameId, 1, "create_skill", Map.of("abilityId", "custom_guard", "name", "架盾", "template_id", "skill_guard"))
                .andExpect(status().isOk());
        performAction(gameId, 2, "create_skill", Map.of("abilityId", "custom_heal", "name", "回春", "template_id", "spell_self_heal"))
                .andExpect(status().isOk());
        assertRejected(gameId, 3, "start_battle", Map.of(), "缺少遭遇怪物素材标识");
        assertRejected(gameId, 3, "start_battle", Map.of("monsterId", "missing"), "找不到怪物素材");
        performAction(gameId, 3, "start_battle", Map.of("monsterId", "high")).andExpect(status().isOk())
                .andExpect(jsonPath("$.world.currentRegion").value("town"))
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[1].monsterType").value("high"))
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[1].hp").value(12))
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[1].power").value(4));

        assertRejected(gameId, 4, "use_skill", Map.of("abilityId", "skill_single_target_strike", "targetId", "enemy-1"), "未学习能力");
        assertRejected(gameId, 4, "cast_spell", Map.of("abilityId", "custom_strike", "targetId", "enemy-1"), "类别不符");
        assertRejected(gameId, 4, "use_skill", Map.of("abilityId", "custom_strike", "targetId", "player"), "无效的战斗目标");
        assertRejected(gameId, 4, "use_skill", Map.of("abilityId", "custom_strike", "targetId", "missing"), "无效的战斗目标");
        assertRejected(gameId, 4, "cast_spell", Map.of("abilityId", "custom_heal", "targetId", "enemy-1"), "只能以自身为目标");

        performAction(gameId, 4, "use_skill", Map.of("abilityId", "custom_strike", "targetId", "enemy-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[0].currentAp").value(6))
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[1].hp").value(7))
                .andExpect(jsonPath("$.confirmedEvents[0].amount").value(5));
        performAction(gameId, 5, "advance_battle_turn", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.hpCurrent").value(33))
                .andExpect(jsonPath("$.rulesetState.systems.battle.log[1].action").value("ATTACK"))
                .andExpect(jsonPath("$.confirmedEvents[1].subject").value("enemy-1"))
                .andExpect(jsonPath("$.confirmedEvents[1].target").value("player"))
                .andExpect(jsonPath("$.confirmedEvents[1].amount").value(1));
        performAction(gameId, 6, "cast_spell", Map.of("abilityId", "custom_heal")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.hpCurrent").value(34))
                .andExpect(jsonPath("$.rulesetState.spCurrent").value(7))
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[0].currentAp").value(6))
                .andExpect(jsonPath("$.confirmedEvents[0].amount").value(1));
        performAction(gameId, 7, "use_skill", Map.of("abilityId", "custom_guard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[0].guard").value(4))
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[0].currentAp").value(4));
        performAction(gameId, 8, "defend", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[0].guard").value(6))
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[0].currentAp").value(0));
        assertRejected(gameId, 9, "use_skill", Map.of("abilityId", "custom_strike", "targetId", "enemy-1"), "行动点不足");
        mockMvc.perform(get("/api/games/{gameId}", gameId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(9))
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[0].currentAp").value(0))
                .andExpect(jsonPath("$.rulesetState.systems.battle.combatants[1].hp").value(7));
    }

    /** 验证模板绑定的 NPC、道具、任务和事件按 AP 规则结算。 */
    @Test
    void apResolvesRegisteredNpcItemsQuestsAndSupplies() throws Exception {
        String gameId = "ap-material-effects";
        openApInTown(gameId);
        performAction(gameId, 0, "learn_ability", Map.of("abilityId", "skill_offense")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.skills[0].templateId").value("skill_single_target_strike"));
        performAction(gameId, 1, "register_npc", Map.of("npcId", "guide", "npcType", "merchant", "name", "向导"))
                .andExpect(status().isOk());
        performAction(gameId, 2, "talk_npc", Map.of("npcId", "guide")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.systems.npcs[0].interactions[0].day").value(1))
                .andExpect(jsonPath("$.confirmedEvents[0].target").value("guide"));
        performAction(gameId, 3, "offer_quest", Map.of("questId", "caravan_job", "questTemplateId", "lost_caravan", "xp", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.systems.questLog[0].reward.experience").value(10));
        performAction(gameId, 4, "accept_quest", Map.of("questId", "caravan_job")).andExpect(status().isOk());
        performAction(gameId, 5, "complete_quest", Map.of("questId", "caravan_job")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.xp").value(10))
                .andExpect(jsonPath("$.confirmedEvents[0].type").value("quest_completed"));
        assertRejected(gameId, 6, "complete_quest", Map.of("questId", "caravan_job"), "Quest is not active");
        performAction(gameId, 6, "supply_event", Map.of("eventId", "caravan_arrival")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.resources.rations").value(4))
                .andExpect(jsonPath("$.confirmedEvents[0].amount").value(1));
        performAction(gameId, 7, "acquire_item", Map.of("itemName", "山草药剂", "itemCategory", "herbal_draught", "itemTier", "common"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.inventory[2].effect.hpRecovery").value(5));
        performAction(gameId, 8, "start_battle", Map.of("monsterId", "low")).andExpect(status().isOk());
        for (int revision = 9; revision < 15; revision++) {
            performAction(gameId, revision, "advance_battle_turn", Map.of()).andExpect(status().isOk());
        }
        performAction(gameId, 15, "use_item", Map.of("itemName", "山草药剂")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.hpCurrent").value(33))
                .andExpect(jsonPath("$.rulesetState.inventory.length()").value(2))
                .andExpect(jsonPath("$.confirmedEvents[0].amount").value(5));
    }

    /** 验证纯叙事世界在直接动作和模型提案两条路径都明确拒绝引擎动作。 */
    @Test
    void storyGameRejectsEngineActionsFromBothEntryPoints() throws Exception {
        String gameId = "story-rejects-engine-actions";
        mockMvc.perform(post("/api/games/{gameId}/initialize", gameId).contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"cinder_continent\"}"))
                .andExpect(status().isOk());
        performAction(gameId, 0, "rest", Map.of()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENGINE_REQUIRED"));
        when(narrativeProvider.respond(any())).thenReturn(
                new NarrativeResponse("你想休息。", new ActionIntent("rest", "", List.of()), List.of("稍作停留")));
        mockMvc.perform(post("/api/games/{gameId}/narration", gameId).contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"playerInput\":\"休息\",\"locale\":\"zh-CN\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENGINE_REQUIRED"));
        mockMvc.perform(get("/api/games/{gameId}", gameId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0));
    }

    /** 使用内置素材在城镇开局，场景文本可独立于遭遇选择。
     * @param gameId 本用例的游戏标识
     */
    private ResultActions openApInTown(String gameId) throws Exception {
        return mockMvc.perform(post("/api/games/{gameId}/initialize", gameId).contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"cinder_continent\",\"engineId\":\"ap\",\"region\":\"town\"}"))
                .andExpect(status().isOk());
    }

    /** 提交一项结构化 AP 动作。
     * @param gameId 当前游戏标识
     * @param revision 提交动作所依据的修订号
     * @param type 动作类型
     * @param arguments 文本参数名和值
     */
    private ResultActions performAction(String gameId, int revision, String type, Map<String, String> arguments) throws Exception {
        List<Map<String, String>> values = arguments.entrySet().stream()
                .map(entry -> Map.of("name", entry.getKey(), "type", "text", "text", entry.getValue())).toList();
        String body = mapper.writeValueAsString(Map.of("expectedRevision", revision, "type", type, "arguments", values));
        return mockMvc.perform(post("/api/games/{gameId}/actions", gameId).contentType(APPLICATION_JSON).content(body));
    }

    /** 检查被拒动作返回稳定错误码和具体原因。
     * @param gameId 当前游戏标识
     * @param revision 应保持的修订号
     * @param type 动作类型
     * @param arguments 文本动作参数
     * @param reason 预期出现在错误文本中的原因
     */
    private void assertRejected(String gameId, int revision, String type, Map<String, String> arguments, String reason) throws Exception {
        performAction(gameId, revision, type, arguments).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENGINE_ACTION_REJECTED"))
                .andExpect(jsonPath("$.error").value(containsString(reason)));
    }

    @Test
    void narrationEndpointReturnsNarrationWithoutEngineAction() throws Exception {
        mockMvc.perform(post("/api/games/narration-api-test/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/narration-api-test/narration")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"playerInput\":\"观察四周\",\"locale\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.narration").value("确认后的行动已经发生。"))
                .andExpect(jsonPath("$.options.length()").value(3))
                .andExpect(jsonPath("$.game.revision").value(0))
                .andExpect(jsonPath("$.confirmedEvents").isEmpty());
    }

}
