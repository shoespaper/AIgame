package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.gameneko.aigame.api.NarrationStreamController;
import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.NarrativePrompt;
import com.gameneko.aigame.narrative.NarrativeProviderException;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalAnswers.returnsElementsOf;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

    @Autowired
    private NarrativeApplicationService narrativeApplication;

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
                .andExpect(jsonPath("$.threatLevel").doesNotExist());
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

    /** 验证 GM 的购买意图，以及赠礼、以物易物、单向付款与失败回滚。 */
    @Test
    void mockedGmExchangesWithNpcInventory() throws Exception {
        String gameId = "ap-npc-exchange-" + UUID.randomUUID();
        openApInTown(gameId);
        performAction(gameId, 0, "register_npc", Map.of("npcId", "horn", "npcType", "merchant", "name", "霍恩",
                        "stockItemName", "山草药剂", "stockItemCategory", "herbal_draught", "stockQuantity", "3", "stockCopper", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.systems.npcs[0].mood").doesNotExist());
        performAction(gameId, 1, "acquire_item", Map.of("itemName", "包扎布", "itemCategory", "herbal_draught", "itemTier", "common"))
                .andExpect(status().isOk());

        ActionIntent exchange = new ActionIntent("exchange_with_npc", "", List.of(
                new ActionIntent.Argument("npcId", new InitializationValue.Text("horn")),
                new ActionIntent.Argument("giveCopper", new InitializationValue.IntegerNumber(2)),
                new ActionIntent.Argument("receiveItem", new InitializationValue.Text("山草药剂")),
                new ActionIntent.Argument("receiveQuantity", new InitializationValue.IntegerNumber(1))));
        when(narrativeProvider.respond(any())).thenReturn(
                new NarrativeResponse("你把两枚铜币放到桌上。", exchange, List.of("问问情报")),
                new NarrativeResponse("霍恩收下两枚铜币，递给你一瓶山草药剂。", null, List.of("问问情报")));
        List<JsonNode> events = stream(mockMvc, gameId, UUID.randomUUID(), Map.of("expectedRevision", 2,
                "playerInput", "给霍恩两枚铜币买一瓶山草药剂"));
        assertEquals(List.of("started", "committed", "complete"), eventTypes(events));
        JsonNode result = events.getLast().path("result");
        assertEquals(3, result.at("/game/revision").asInt());
        assertEquals(13, result.at("/game/rulesetState/resources/copper").asInt());
        assertEquals(7, result.at("/game/rulesetState/systems/npcs/0/copper").asInt());
        assertEquals(2, result.at("/game/rulesetState/systems/npcs/0/inventory/0/quantity").asInt());
        assertEquals("山草药剂", result.at("/game/rulesetState/inventory/3/name").asText());
        assertEquals("npc_exchange", result.at("/confirmedEvents/0/type").asText());
        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(narrativeProvider, times(2)).respond(prompts.capture());
        assertTrue(prompts.getAllValues().getFirst().systemInstruction().contains("exchange_with_npc"));
        assertTrue(prompts.getAllValues().getLast().snapshot().confirmedEvents().stream().anyMatch(event -> "npc_exchange".equals(event.type())));

        assertRejected(gameId, 3, "exchange_with_npc", Map.of("npcId", "horn", "giveCopper", "1",
                "receiveItem", "山草药剂", "receiveQuantity", "3"), "数量不足");
        assertRejected(gameId, 3, "exchange_with_npc", Map.of("npcId", "horn", "giveCopper", "16"), "玩家铜币不足");
        assertRejected(gameId, 3, "exchange_with_npc", Map.of("npcId", "horn", "receiveCopper", "8"), "NPC 铜币不足");
        assertRejected(gameId, 3, "exchange_with_npc", Map.of("npcId", "horn", "giveCopper", "-1"), "数量无效");
        assertRejected(gameId, 3, "exchange_with_npc", Map.of("npcId", "horn", "giveItem",
                result.at("/game/rulesetState/equipment/weapon").asText()), "正在装备");
        assertRejected(gameId, 3, "exchange_with_npc", Map.of("npcId", "horn"), "至少需要一方");
        mockMvc.perform(get("/api/games/{gameId}", gameId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.rulesetState.resources.copper").value(13))
                .andExpect(jsonPath("$.rulesetState.systems.npcs[0].copper").value(7))
                .andExpect(jsonPath("$.rulesetState.systems.npcs[0].inventory[0].quantity").value(2));

        performAction(gameId, 3, "exchange_with_npc", Map.of("npcId", "horn", "giveCopper", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rulesetState.resources.copper").value(12));
        performAction(gameId, 4, "exchange_with_npc", Map.of("npcId", "horn", "giveItem", "包扎布", "receiveItem", "山草药剂"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rulesetState.inventory[2].quantity").value(2))
                .andExpect(jsonPath("$.rulesetState.systems.npcs[0].inventory[1].name").value("包扎布"));
        performAction(gameId, 5, "exchange_with_npc", Map.of("npcId", "horn", "giveItem", "山草药剂", "receiveCopper", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rulesetState.resources.copper").value(14))
                .andExpect(jsonPath("$.rulesetState.systems.npcs[0].copper").value(6));
        performAction(gameId, 6, "exchange_with_npc", Map.of("npcId", "horn", "receiveItem", "包扎布"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rulesetState.inventory[3].name").value("包扎布"));
        performAction(gameId, 7, "exchange_with_npc", Map.of("npcId", "horn", "giveItem", "包扎布"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rulesetState.inventory.length()").value(3));
        mockMvc.perform(get("/api/games/{gameId}", gameId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(8))
                .andExpect(jsonPath("$.rulesetState.systems.npcs[0].inventory[1].name").value("包扎布"));
    }

    /** 验证批量登记原子提交，存档恢复后也能通过服务端标识直接接取。 */
    @Test
    void questChoicesAreAvailableOnlyAfterConfirmedPublication() throws Exception {
        String gameId = "ap-quest-choices-" + UUID.randomUUID();
        openApInTown(gameId);
        assertRejected(gameId, 0, "offer_quests", Map.of(
                "questId1", "guard_job", "questTemplateId1", "lost_caravan",
                "questId2", "broken_job", "questTemplateId2", "missing_quest"), "未知素材");
        mockMvc.perform(get("/api/games/{gameId}", gameId))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.rulesetState.systems.questLog").isEmpty())
                .andExpect(jsonPath("$.actionChoices").isEmpty());

        List<ActionIntent.Argument> offers = Map.of(
                "questId1", "guard_job", "questTemplateId1", "lost_caravan", "questTitle1", "南坡护卫",
                "questId2", "night_watch", "questTemplateId2", "lost_caravan", "questTitle2", "服役巡夜")
                .entrySet().stream()
                .map(entry -> new ActionIntent.Argument(entry.getKey(), new InitializationValue.Text(entry.getValue())))
                .toList();
        NarrativeResponse proposal = new NarrativeResponse("记录员拿出两张委托。", new ActionIntent("offer_quests", "", offers),
                List.of("问问详情"));
        NarrativeResponse description = new NarrativeResponse("记录员把委托贴上墙。", null, List.of("问问详情"));
        when(narrativeProvider.respond(any())).thenReturn(proposal, description);
        mockMvc.perform(post("/api/games/{gameId}/narration", gameId).contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"playerInput\":\"看看委托板\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.revision").value(1))
                .andExpect(jsonPath("$.game.actionChoices[1].id").value("accept_quest:night_watch"))
                .andExpect(jsonPath("$.confirmedEvents.length()").value(2));
        mockMvc.perform(get("/api/games/{gameId}", gameId))
                .andExpect(jsonPath("$.actionChoices[1].label").value("接取「服役巡夜」"));

        String choose = "{\"expectedRevision\":1,\"choiceId\":\"accept_quest:night_watch\",\"playerInput\":\"查看委托板\"}";
        mockMvc.perform(post("/api/games/{gameId}/narration", gameId).contentType(APPLICATION_JSON).content(choose))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.revision").value(2))
                .andExpect(jsonPath("$.game.rulesetState.systems.questLog[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.game.actionChoices.length()").value(1))
                .andExpect(jsonPath("$.confirmedEvents[0].type").value("quest_accepted"));
        mockMvc.perform(post("/api/games/{gameId}/narration", gameId).contentType(APPLICATION_JSON).content(choose))
                .andExpect(status().isConflict());
        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(narrativeProvider, times(3)).respond(prompts.capture());
        assertEquals("接取「服役巡夜」", prompts.getAllValues().getLast().userInput());
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

    /** 验证到期事件只保存事实，AP 按素材结算补给且只执行一次。 */
    @Test
    void apResolvesDeadlineFactsThroughRegisteredEventRules() throws Exception {
        String gameId = "ap-world-facts";
        openApInTown(gameId).andExpect(jsonPath("$.world.threatLevel").doesNotExist());
        performAction(gameId, 0, "register_npc", Map.of("npcId", "guide", "npcType", "merchant", "name", "向导"))
                .andExpect(status().isOk());
        scheduleDeadline(gameId, 1, "delivery", "caravan_arrival");
        performAction(gameId, 2, "advance_day", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.world.day").value(2))
                .andExpect(jsonPath("$.world.worldEvents.length()").value(1))
                .andExpect(jsonPath("$.world.worldEvents[0].materialId").value("caravan_arrival"))
                .andExpect(jsonPath("$.world.worldEvents[0].effects").doesNotExist())
                .andExpect(jsonPath("$.rulesetState.resources.rations").value(3))
                .andExpect(jsonPath("$.rulesetState.systems.threatDays").value(1))
                .andExpect(jsonPath("$.confirmedEvents[1].type").value("supply_event"));

        performAction(gameId, 3, "advance_day", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.world.worldEvents.length()").value(1))
                .andExpect(jsonPath("$.rulesetState.resources.rations").value(2));
    }

    /** 登记下一天到期的世界事件。
     * @param gameId 当前游戏标识
     * @param revision 玩家观察到的当前修订号
     * @param eventId 本次事件标识
     * @param materialId 到期事实关联的世界素材标识
     */
    private void scheduleDeadline(String gameId, int revision, String eventId, String materialId) throws Exception {
        List<Map<String, Object>> arguments = List.of(
                Map.of("name", "eventId", "type", "text", "text", eventId),
                Map.of("name", "materialId", "type", "text", "text", materialId),
                Map.of("name", "deadlineDays", "type", "integer", "integer", 1));
        String body = mapper.writeValueAsString(Map.of("expectedRevision", revision, "type", "schedule_timed_event",
                "arguments", arguments));
        mockMvc.perform(post("/api/games/{gameId}/actions", gameId).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
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

    /** 验证无效的叙事和引擎动作各通知一次，并用原输入和快照重试。 */
    @Test
    void streamedNarrationRetriesWithCorrectionAndKeepsOriginalInput() throws Exception {
        String gameId = "retry-correction-" + UUID.randomUUID();
        openApInTown(gameId);
        NarrativeResponse badQuest = new NarrativeResponse("我接单。", new ActionIntent("accept_quest", "missing", List.of()),
                List.of("询问委托"));
        when(narrativeProvider.respond(any()))
                .thenThrow(NarrativeProviderException.invalidOutput("无效格式", null))
                .thenReturn(badQuest, new NarrativeResponse("记录员请你先向守备官咨询。", null, List.of("去问守备官")));
        List<JsonNode> events = stream(mockMvc, gameId, UUID.randomUUID(), "我接北墙巡夜。");
        assertEquals(List.of("started", "attempt_failed", "attempt_failed", "complete"), eventTypes(events));
        assertEquals(1, events.get(1).path("attempt").asInt());
        assertEquals(2, events.get(2).path("attempt").asInt());
        assertEquals(0, events.getLast().at("/result/game/revision").asInt());
        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(narrativeProvider, times(3)).respond(prompts.capture());
        assertTrue(prompts.getAllValues().stream().allMatch(prompt -> prompt.singleAttempt()
                && prompt.userInput().equals("我接北墙巡夜。") && prompt.snapshot().equals(prompts.getValue().snapshot())));
        assertTrue(prompts.getAllValues().get(1).systemInstruction().contains("上一次回复不符合协议"));
        assertTrue(prompts.getAllValues().get(2).systemInstruction().contains("引擎动作被拒绝"));
    }

    /** 验证十次失败后不写入状态，重新连接也不会再次调用模型。 */
    @Test
    void streamedNarrationExhaustsExactlyTenAttemptsWithoutMutation() throws Exception {
        String gameId = "retry-exhausted-" + UUID.randomUUID();
        openApInTown(gameId);
        UUID turnId = UUID.randomUUID();
        when(narrativeProvider.respond(any())).thenThrow(NarrativeProviderException.invalidOutput("格式错误", null));
        List<JsonNode> events = stream(mockMvc, gameId, turnId, "看看委托板");
        assertEquals("started", events.getFirst().path("type").asText());
        assertFailedAttempts(events.subList(1, events.size() - 1), 1, 10);
        assertEquals("failed", events.getLast().path("type").asText());
        assertEquals("ATTEMPTS_EXHAUSTED", events.getLast().path("code").asText());
        assertEquals("10 次尝试均未成功，原文已恢复。", events.getLast().path("message").asText());
        mockMvc.perform(get("/api/games/{gameId}", gameId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0));
        assertEquals(List.of("started", "failed"), eventTypes(stream(mockMvc, gameId, turnId, "看看委托板")));
        verify(narrativeProvider, times(10)).respond(any());
    }

    /** 验证描写用完剩余预算后返回已结算回执，换一个流控制器也可以从存档恢复。 */
    @Test
    void streamedNarrationRecoversCommittedReceiptAfterDescriptionFails() throws Exception {
        String gameId = "retry-receipt-" + UUID.randomUUID();
        openApInTown(gameId);
        UUID turnId = UUID.randomUUID();
        when(narrativeProvider.respond(any()))
                .thenReturn(new NarrativeResponse("你准备吃一份口粮。", new ActionIntent("consume_ration", "", List.of()),
                        List.of("继续赶路")))
                .thenThrow(NarrativeProviderException.invalidOutput("描写失败", null));
        List<JsonNode> first = stream(mockMvc, gameId, turnId, "吃一份口粮");
        assertEquals(List.of("started", "committed"), eventTypes(first.subList(0, 2)));
        assertFailedAttempts(first.subList(2, first.size() - 1), 2, 10);
        assertEquals("complete", first.getLast().path("type").asText());
        assertEquals(1, first.get(1).at("/result/game/revision").asInt());
        assertTrue(first.getLast().at("/result/receiptOnly").asBoolean());
        assertEquals(first.get(1).path("result"), first.getLast().path("result"));
        MockMvc fresh = MockMvcBuilders.standaloneSetup(new NarrationStreamController(narrativeApplication)).build();
        List<JsonNode> reconnected = stream(fresh, gameId, turnId, "吃一份口粮");
        assertEquals(List.of("started", "complete"), eventTypes(reconnected));
        assertEquals(first.getLast().at("/result/confirmedEvents"), reconnected.getLast().at("/result/confirmedEvents"));
        mockMvc.perform(get("/api/games/{gameId}", gameId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.rulesetState.resources.rations").value(2));
        verify(narrativeProvider, times(10)).respond(any());
    }

    /** 验证直接接取只结算一次，遇到过长选项后可以在第十次补全描写。 */
    @Test
    void streamedChoiceUsesTenDescriptionAttemptsWithoutReapplyingAction() throws Exception {
        String gameId = "retry-choice-" + UUID.randomUUID();
        openApInTown(gameId);
        performAction(gameId, 0, "offer_quest", Map.of("questId", "caravan_job", "questTemplateId", "lost_caravan"))
                .andExpect(status().isOk());
        String atLimit = "去医帐问那个归来的先遣队员关于当晚巡逻时看到什么";
        NarrativeResponse tooLong = new NarrativeResponse("记录员替你登记。", null, List.of(atLimit + "？"));
        NarrativeResponse valid = new NarrativeResponse("记录员替你登记。", null, List.of(atLimit, "去医帐问那个归来的先遣队员"));
        List<NarrativeResponse> replies = new ArrayList<>(Collections.nCopies(9, tooLong));
        replies.add(valid);
        when(narrativeProvider.respond(any())).thenAnswer(returnsElementsOf(replies));
        UUID turnId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("expectedRevision", 1, "choiceId", "accept_quest:caravan_job");
        List<JsonNode> events = stream(mockMvc, gameId, turnId, payload);
        assertEquals(List.of("started", "committed"), eventTypes(events.subList(0, 2)));
        assertFailedAttempts(events.subList(2, events.size() - 1), 1, 9);
        assertEquals("complete", events.getLast().path("type").asText());
        assertEquals("ACTIVE", events.getLast().at("/result/game/rulesetState/systems/questLog/0/status").asText());
        assertEquals(2, events.getLast().at("/result/game/revision").asInt());
        assertEquals("记录员替你登记。", events.getLast().at("/result/narration").asText());
        assertEquals(atLimit, events.getLast().at("/result/options/0").asText());
        assertTrue(events.getLast().at("/result/receiptOnly").isBoolean());
        assertFalse(events.getLast().at("/result/receiptOnly").asBoolean());
        MockMvc fresh = MockMvcBuilders.standaloneSetup(new NarrationStreamController(narrativeApplication)).build();
        assertTrue(stream(fresh, gameId, turnId, payload).getLast().at("/result/receiptOnly").asBoolean());
        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(narrativeProvider, times(10)).respond(prompts.capture());
        assertTrue(prompts.getAllValues().stream().allMatch(prompt -> prompt.singleAttempt()
                && prompt.snapshot().equals(prompts.getValue().snapshot())));
        assertTrue(prompts.getAllValues().get(1).systemInstruction().contains("上一次描写与修正要求"));
    }

    /** 验证第十次才结算时直接返回回执，预算用尽后不再调用模型描写。 */
    @Test
    void streamedNarrationCapsModelCallsEvenWhenTenthAttemptCommits() throws Exception {
        String gameId = "retry-budget-" + UUID.randomUUID();
        openApInTown(gameId);
        NarrativeProviderException invalid = NarrativeProviderException.invalidOutput("格式错误", null);
        when(narrativeProvider.respond(any()))
                .thenThrow(Collections.nCopies(9, invalid).toArray(NarrativeProviderException[]::new))
                .thenReturn(new NarrativeResponse("你准备吃一份口粮。", new ActionIntent("consume_ration", "", List.of()),
                        List.of("继续赶路")));
        List<JsonNode> events = stream(mockMvc, gameId, UUID.randomUUID(), "吃一份口粮");
        assertEquals("started", events.getFirst().path("type").asText());
        assertFailedAttempts(events.subList(1, events.size() - 2), 1, 9);
        assertEquals(List.of("committed", "complete"), eventTypes(events.subList(events.size() - 2, events.size())));
        assertEquals(1, events.getLast().at("/result/game/revision").asInt());
        assertTrue(events.getLast().at("/result/receiptOnly").asBoolean());
        verify(narrativeProvider, times(10)).respond(any());
    }

    /** 验证一段失败事件的类型和连续尝试序号。
     * @param events 模拟流中属于失败尝试的事件片段
     * @param first 起始尝试序号，含该次
     * @param last 结束尝试序号，含该次
     */
    private static void assertFailedAttempts(List<JsonNode> events, int first, int last) {
        assertEquals(Collections.nCopies(last - first + 1, "attempt_failed"), eventTypes(events));
        assertEquals(IntStream.rangeClosed(first, last).boxed().toList(),
                events.stream().map(node -> node.path("attempt").asInt()).toList());
    }

    /** 获取一次模拟文本回合的 SSE 数据。
     * @param client 用于发送请求的 MockMvc 实例
     * @param gameId 本轮所属存档标识
     * @param turnId 重连时复用的回合标识
     * @param input 本轮玩家输入
     */
    private List<JsonNode> stream(MockMvc client, String gameId, UUID turnId, String input) throws Exception {
        return stream(client, gameId, turnId, Map.of("expectedRevision", 0, "playerInput", input));
    }

    /** 获取模拟叙事回合的 SSE 数据，玩家可发送正文或选择服务端行动。
     * @param client 用于发送请求的 MockMvc 实例
     * @param gameId 本轮所属存档标识
     * @param turnId 重连时复用的回合标识
     * @param payload 完整的叙事请求字段
     */
    private List<JsonNode> stream(MockMvc client, String gameId, UUID turnId, Map<String, Object> payload) throws Exception {
        MvcResult opened = client.perform(post("/api/games/{gameId}/narration/turns/{turnId}", gameId, turnId)
                        .accept("text/event-stream").contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(request().asyncStarted()).andReturn();
        opened.getAsyncResult(8000);
        String response = client.perform(asyncDispatch(opened)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> events = new ArrayList<>();
        for (String line : response.lines().toList()) {
            if (line.startsWith("data:")) events.add(mapper.readTree(line.substring(5)));
        }
        return events;
    }

    /** 取得模拟回合推送的事件顺序。
     * @param events 模拟流收到的事件
     */
    private static List<String> eventTypes(List<JsonNode> events) {
        return events.stream().map(node -> node.path("type").asText()).toList();
    }
}
