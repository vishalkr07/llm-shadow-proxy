package com.example.shadowproxy.inference;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class MockInferenceClient implements InferenceGateway {
    private final ObjectMapper objectMapper;
    private final List<String> actions;
    private final String modelName;

    public MockInferenceClient(
            ObjectMapper objectMapper, List<String> actions, String modelName) {
        this.objectMapper = objectMapper;
        this.actions = List.copyOf(actions);
        this.modelName = modelName;
    }

    @Override
    public ChatCompletion complete(String ignoredModel, byte[] requestBody) {
        String action = actions.get(ThreadLocalRandom.current().nextInt(actions.size()));
        try {
            String content = objectMapper.writeValueAsString(Map.of("action", action));
            byte[] body =
                    objectMapper.writeValueAsBytes(
                            Map.of(
                                    "id", "mock-" + UUID.randomUUID(),
                                    "object", "chat.completion",
                                    "model", modelName,
                                    "choices",
                                            List.of(
                                                    Map.of(
                                                            "index", 0,
                                                            "message",
                                                                    Map.of(
                                                                            "role", "assistant",
                                                                            "content", content),
                                                            "finish_reason", "stop")),
                                    "usage",
                                            Map.of(
                                                    "prompt_tokens", 0,
                                                    "completion_tokens", 0,
                                                    "total_tokens", 0)));
            return new ChatCompletion(200, body, MediaType.APPLICATION_JSON, null, false);
        } catch (JacksonException exception) {
            throw new IllegalStateException("could not serialize mock completion", exception);
        }
    }
}
