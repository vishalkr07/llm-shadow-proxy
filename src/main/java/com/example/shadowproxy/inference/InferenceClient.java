package com.example.shadowproxy.inference;

import java.util.Arrays;
import java.io.IOException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class InferenceClient implements InferenceGateway {
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String SHADOW_HEADER = "X-Shadow-Request";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final int maxResponseBytes;
    private final boolean shadowRole;
    private final CloseableHttpClient httpClient;

    public InferenceClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            int maxResponseBytes,
            boolean shadowRole,
            CloseableHttpClient httpClient) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.maxResponseBytes = maxResponseBytes;
        this.shadowRole = shadowRole;
        this.httpClient = httpClient;
    }

    @Override
    public ChatCompletion complete(String model, byte[] requestBody) {
        byte[] upstreamBody = withModelOverride(model, requestBody);

        return restClient
                .post()
                .uri(COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(
                        headers -> {
                            if (shadowRole) {
                                headers.set(SHADOW_HEADER, "true");
                            }
                        })
                .body(upstreamBody)
                .exchange(
                        (request, response) -> {
                            byte[] bytes = response.getBody().readNBytes(maxResponseBytes + 1);
                            boolean truncated = bytes.length > maxResponseBytes;
                            MediaType contentType = response.getHeaders().getContentType();
                            String retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                            return new ChatCompletion(
                                    response.getStatusCode().value(),
                                    truncated
                                            ? Arrays.copyOf(bytes, maxResponseBytes)
                                            : bytes,
                                    contentType,
                                    retryAfter,
                                    truncated);
                        });
    }

    private byte[] withModelOverride(String model, byte[] requestBody) {
        try {
            var root = objectMapper.readTree(requestBody);
            if (!(root instanceof ObjectNode object)) {
                throw new InvalidChatRequestException("request body must be a JSON object");
            }
            object.put("model", model);
            return objectMapper.writeValueAsBytes(object);
        } catch (InvalidChatRequestException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw new InvalidChatRequestException("request body must be valid JSON", exception);
        }
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (IOException exception) {
            throw new IllegalStateException("could not close inference HTTP client", exception);
        }
    }
}
