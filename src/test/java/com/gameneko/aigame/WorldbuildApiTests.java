package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.gameneko.aigame.api.GlobalExceptionHandler;
import com.gameneko.aigame.api.WorldbuildSessionController;
import com.gameneko.aigame.application.WorldbuildNarrationService;
import com.gameneko.aigame.application.port.WorldbookRepository;
import com.gameneko.aigame.content.TemplateCatalog;
import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.narrative.WorldDraftModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/** 验证世界草案生成和保存的 HTTP 协议。 */
class WorldbuildApiTests {
    /** 验证空会话、单轮通过、保存及过期草案拒绝。 */
    @Test
    void exposesAcceptedDraftAndSavesItsVersion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WorldBookCatalog worlds = mock(WorldBookCatalog.class);
        TemplateCatalog templates = mock(TemplateCatalog.class);
        when(worlds.available()).thenReturn(List.of());
        when(templates.availableTemplates()).thenReturn(List.of());
        AtomicInteger calls = new AtomicInteger();
        WorldbuildNarrationService service = new WorldbuildNarrationService(new WorldDraftModel((operation, instruction, input) ->
                switch (calls.incrementAndGet()) {
                    case 1 -> mapper.createObjectNode();
                    case 2 -> mapper.valueToTree(Map.of("draft", "# 双月世界", "reply", "草案已生成。"));
                    case 3 -> mapper.valueToTree(Map.of("status", "PASS"));
                    case 4 -> mapper.valueToTree(Map.of("name", "双月世界", "type", "isekai"));
                    default -> throw new AssertionError("unexpected model call");
                }, mapper), mock(WorldbookRepository.class), worlds, templates);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorldbuildSessionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String route = "/api/initialization/worldbuild/sessions";
        String created = mvc.perform(post(route))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.done").value(false))
                .andExpect(jsonPath("$.turn.draft").value(""))
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, calls.get());

        String session = route + "/" + mapper.readTree(created).path("id").asText();
        MvcResult stream = mvc.perform(post(session + "/answer/stream").accept("text/event-stream")
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"双月世界\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        stream.getAsyncResult(8000);
        String frames = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> events = frames.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5)).toList();
        JsonNode retry = mapper.readTree(events.get(1));
        assertEquals("progress", retry.path("type").asText());
        assertEquals(true, retry.at("/progress/failed").asBoolean());
        assertEquals(1, retry.at("/progress/attempt").asInt());
        JsonNode completed = mapper.readTree(events.getLast());
        assertEquals("complete", completed.path("type").asText());
        assertEquals("# 双月世界", completed.at("/result/turn/draft").asText());
        assertEquals(1, completed.at("/result/turn/version").asInt());
        mvc.perform(post(session + "/save").contentType(APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(session + "/save").contentType(APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.worldbookId").isString());
        mvc.perform(post(route + "/missing/answer").contentType(APPLICATION_JSON).content("{\"answer\":\"双月世界\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        assertEquals(4, calls.get());
    }
}
