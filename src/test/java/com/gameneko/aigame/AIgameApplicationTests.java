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

@SpringBootTest
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
                                {"worldId":"isekai_grey","name":"\u7070\u70ec\u754c",
                                 "location":"\u767d\u680e\u9547","region":"forest","time":"\u9ec4\u660f"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldId").value("isekai_grey"))
                .andExpect(jsonPath("$.contentVersion").value("1.0"))
                .andExpect(jsonPath("$.day").value(1))
                .andExpect(jsonPath("$.time").value("\u9ec4\u660f"))
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

}
