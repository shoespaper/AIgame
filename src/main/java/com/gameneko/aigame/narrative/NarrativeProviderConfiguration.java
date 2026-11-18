package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class NarrativeProviderConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "aigame.narrative", name = "enabled", havingValue = "false", matchIfMissing = true)
    NarrativeProvider localNarrativePort() {
        LocalNarrativeProvider fallback = new LocalNarrativeProvider();
        return fallback::respond;
    }

    @Bean
    @ConditionalOnProperty(prefix = "aigame.narrative", name = "enabled", havingValue = "true")
    NarrativeProvider openAiCompatibleNarrativePort(RestClient.Builder restClientBuilder,
                                                    ObjectMapper objectMapper,
                                                    @Value("${aigame.narrative.api-key}") String apiKey,
                                                    @Value("${aigame.narrative.base-url:https://api.openai.com/v1}") String baseUrl,
                                                    @Value("${aigame.narrative.model:gpt-4o-mini}") String model) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Narrative API key is required when the external provider is enabled");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(3));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(20));
        RestClient client = restClientBuilder.requestFactory(requestFactory).baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        return new OpenAiCompatibleNarrativeProvider(client, objectMapper, model);
    }

    static ObjectNode jsonObjectResponseFormat(ObjectMapper objectMapper) {
        return objectMapper.createObjectNode().put("type", "json_object");
    }
}
