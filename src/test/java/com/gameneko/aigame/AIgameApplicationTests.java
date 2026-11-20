package com.gameneko.aigame;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "aigame.persistence.root=${java.io.tmpdir}/aigame-tests")
@AutoConfigureMockMvc
class AIgameApplicationTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
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
                        .content("{\"worldId\":\"isekai_grey\",\"region\":\"forest\"}"))
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Revision mismatch: expected 1, current 0"));
    }

    @Test
    void initializesAndPersistsCompleteGameInstance() throws Exception {
        mockMvc.perform(post("/api/games/instance-test/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\",\"region\":\"forest\"}"))
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
                        .content("{\"worldId\":\"isekai_grey\",\"characterName\":\"Traveler\"}"))
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
                        .content("{\"worldId\":\"isekai_grey\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/xp-api-test/actions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"type\":\"grant_experience\",\"amount\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.rulesetState.level").value(2))
                .andExpect(jsonPath("$.rulesetState.xp").value(0))
                .andExpect(jsonPath("$.rulesetState.xpToNext").value(200));
    }

    @Test
    void phaseThreeActionsUseTypedPayloads() throws Exception {
        mockMvc.perform(post("/api/games/phase3-api-test/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/phase3-api-test/actions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"type\":\"learn_ability\",\"abilityId\":\"skill_offense\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetState.skills[0].id").value("skill_offense"));

        mockMvc.perform(post("/api/games/phase3-api-test/actions")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"type\":\"claim_quest_reward\",\"questId\":\"q1\",\"xp\":20,\"copper\":5,\"rations\":1,\"potions\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.rulesetState.resources.copper").value(20));
    }

    @Test
    void narratedActionUsesConfirmedEventsAndReturnsPendingProposals() throws Exception {
        mockMvc.perform(post("/api/games/narrative-api-test/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\",\"region\":\"forest\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/narrative-api-test/actions/narrate")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"action":{"expectedRevision":0,"type":"advance_day"},
                                 "playerInput":"Move toward the trail","locale":"en-US"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game.revision").value(1))
                .andExpect(jsonPath("$.narration").isNotEmpty())
                .andExpect(jsonPath("$.confirmedEvents[0].type").value("advance_day"));
    }

    @Test
    void proposalMustPassConversionAndRulesetBeforeItChangesTheSave() throws Exception {
        mockMvc.perform(post("/api/games/narrative-proposal-test/initialize")
                        .contentType(APPLICATION_JSON)
                        .content("{\"worldId\":\"isekai_grey\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/narrative-proposal-test/narration/proposals")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":0,"proposalIndex":0,
                                 "proposal":{"type":"gain_experience","fields":{"amount":"45"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.rulesetState.xp").value(45));

        mockMvc.perform(post("/api/games/narrative-proposal-test/narration/proposals")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"proposalIndex":1,
                                 "proposal":{"type":"gain_experience","fields":{"amount":"100000"}}}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/games/narrative-proposal-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.rulesetState.xp").value(45));
    }

}
