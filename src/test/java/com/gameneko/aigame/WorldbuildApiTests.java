package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.api.GlobalExceptionHandler;
import com.gameneko.aigame.api.WorldbuildSessionController;
import com.gameneko.aigame.application.WorldbuildNarrationService;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.NarrativeInitializationQuestion;
import com.gameneko.aigame.narrative.NarrativeParseCandidate;
import com.gameneko.aigame.narrative.NarrativeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证世界构建会话的 HTTP 路由、AI 候选收集和完成条件。 */
class WorldbuildApiTests {
    /** 验证创建、回答、非法会话和返回配置均符合前端约定。 */
    @Test
    void sessionsExposeValidatedConfigurationThroughHttp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var catalog = new ClasspathContentCatalog(mapper, new DefaultResourceLoader());
        var service = new WorldbuildNarrationService(prompt -> {
            String input = prompt.userInput();
            if (input.contains("异世界")) return response("name", candidate("type", "异世界"));
            if (input.contains("双月")) return response("tone", candidate("name", "双月世界"));
            if (input.contains("平衡")) return response(null, candidate("tone", "平衡"));
            return response("type");
        }, mapper, catalog);
        var mvc = MockMvcBuilders.standaloneSetup(new WorldbuildSessionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var created = mvc.perform(post("/api/initialization/worldbuild/sessions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.question.fieldId").value("type"))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("id").asText();
        String answerUrl = "/api/initialization/worldbuild/sessions/" + id + "/answer";
        mvc.perform(post(answerUrl).contentType(APPLICATION_JSON).content("{\"answer\":\"异世界\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.templateId").value("isekai"))
                .andExpect(jsonPath("$.turn.collected.type").value("isekai"));
        mvc.perform(post(answerUrl).contentType(APPLICATION_JSON).content("{\"answer\":\"双月世界\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(false));
        mvc.perform(post(answerUrl).contentType(APPLICATION_JSON).content("{\"answer\":\"平衡\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.turn.collected.name").value("双月世界"))
                .andExpect(jsonPath("$.turn.collected.tone").value("balanced"));
        mvc.perform(post("/api/initialization/worldbuild/sessions/missing/answer").contentType(APPLICATION_JSON).content("{\"answer\":\"异世界\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(answerUrl).contentType(APPLICATION_JSON).content("{\"answer\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    /** 构造模型结构化候选。
     * @param field 世界构建字段标识
     * @param value 文本或 JSON 数组文本
     * @return 类型化候选
     */
    private static NarrativeParseCandidate candidate(String field, String value) {
        return new NarrativeParseCandidate(field, new InitializationValue.Text(value), 1);
    }

    /** 构造可选问题和候选组成的模型响应。
     * @param field 问题字段，空值表示模型结束
     * @param candidates 模型解析的候选
     * @return 叙事模型响应
     */
    private static NarrativeResponse response(String field, NarrativeParseCandidate... candidates) {
        return new NarrativeResponse("继续构建世界。", null, List.of(),
                field == null ? List.of() : List.of(new NarrativeInitializationQuestion(field, "请确认" + field, List.of())),
                List.of(candidates));
    }
}
