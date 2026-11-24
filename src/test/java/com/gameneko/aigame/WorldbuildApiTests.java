package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.api.GlobalExceptionHandler;
import com.gameneko.aigame.api.WorldbuildSessionController;
import com.gameneko.aigame.application.WorldbuildNarrationService;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.NarrativeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证世界构建会话的 HTTP 路由、AI 声明收集和完成条件。 */
class WorldbuildApiTests {
    /** 验证创建、回答、非法会话和返回配置均符合前端约定。 */
    @Test
    void sessionsExposeValidatedConfigurationThroughHttp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var catalog = new ClasspathContentCatalog(mapper, new DefaultResourceLoader());
        var service = new WorldbuildNarrationService(prompt -> {
            String input = prompt.userInput();
            if (input.contains("异世界")) return response(update("type", "异世界"));
            if (input.contains("改名")) return response(update("name", "灰烬王座"));
            if (input.contains("双月")) return response(update("name", "双月世界"));
            return response();
        }, mapper, catalog);
        var mvc = MockMvcBuilders.standaloneSetup(new WorldbuildSessionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var created = mvc.perform(post("/api/initialization/worldbuild/sessions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.options").isArray())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("id").asText();
        String answerUrl = "/api/initialization/worldbuild/sessions/" + id + "/answer";
        mvc.perform(post(answerUrl).contentType(APPLICATION_JSON).content("{\"answer\":\"异世界\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turn.templateId").value("isekai"))
                .andExpect(jsonPath("$.turn.collected.type").value("isekai"));
        mvc.perform(post(answerUrl).contentType(APPLICATION_JSON).content("{\"answer\":\"双月世界\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.turn.collected.name").value("双月世界"));
        mvc.perform(post(answerUrl).contentType(APPLICATION_JSON).content("{\"answer\":\"我想改名\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.turn.collected.name").value("灰烬王座"));
        mvc.perform(post("/api/initialization/worldbuild/sessions/missing/answer").contentType(APPLICATION_JSON).content("{\"answer\":\"异世界\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(answerUrl).contentType(APPLICATION_JSON).content("{\"answer\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    /** 创建一个类型化确定字段声明。
     * @param field 世界构建字段标识
     * @param value 文本或 JSON 数组文本
     * @return 字段和值的映射项
     */
    private static Map.Entry<String, InitializationValue> update(String field, String value) {
        return Map.entry(field, new InitializationValue.Text(value));
    }

    /** 创建只声明确定字段值的模型响应。
     * @param updates 本轮确定字段值
     * @return 不包含问题结构的叙事响应
     */
    @SafeVarargs
    private static NarrativeResponse response(Map.Entry<String, InitializationValue>... updates) {
        return new NarrativeResponse("已记录。", null, List.of(), Map.ofEntries(updates));
    }
}
