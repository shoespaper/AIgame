package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.List;

/** OpenAI Chat Completions compatible adapter, configured only when an API key is present. */
public final class OpenAiCompatibleNarrativeProvider implements NarrativeProvider {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;

    public OpenAiCompatibleNarrativeProvider(RestClient client, ObjectMapper objectMapper, String model) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.model = model;
    }

    @Override
    public NarrativeResponse respond(NarrativePrompt prompt) {
        try {
            String userContent = objectMapper.writeValueAsString(new UserInput(prompt.userInput(), prompt.locale(), prompt.snapshot()));
            var request = new ChatRequest(model, List.of(new ChatMessage("system", prompt.systemInstruction()),
                    new ChatMessage("user", userContent)), 900,
                    NarrativeProviderConfiguration.jsonObjectResponseFormat(objectMapper));
            ChatResponse response = client.post().uri("/chat/completions").body(request).retrieve().body(ChatResponse.class);
            if (response == null || response.choices() == null || response.choices().isEmpty()
                    || response.choices().getFirst().message() == null) throw new IllegalStateException("Narrative provider returned an empty completion");
            String content = response.choices().getFirst().message().content();
            if (content == null || content.isBlank()) throw new IllegalStateException("Narrative provider returned empty content");
            return objectMapper.readValue(content, NarrativeResponse.class);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to encode or decode narrative completion", exception);
        }
    }

    private record UserInput(String playerInput, String locale, NarrativeSnapshot confirmedSnapshot) {}
    private record ChatRequest(String model, List<ChatMessage> messages, int max_tokens, JsonNode response_format) {}
    private record ChatMessage(String role, String content) {}
    private record ChatResponse(List<Choice> choices) {}
    private record Choice(ChatMessage message) {}
}
