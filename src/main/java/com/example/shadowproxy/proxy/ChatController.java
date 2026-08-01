package com.example.shadowproxy.proxy;

import com.example.shadowproxy.config.ProxyProperties;
import com.example.shadowproxy.inference.InferenceClient;
import com.example.shadowproxy.inference.InvalidChatRequestException;
import com.example.shadowproxy.metrics.MetricsRecorder;
import com.example.shadowproxy.shadow.ShadowDispatcher;
import com.example.shadowproxy.shadow.ShadowJob;
import com.example.shadowproxy.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/v1")
public class ChatController {
    private final InferenceClient primaryClient;
    private final ProxyProperties properties;
    private final ObjectMapper objectMapper;
    private final MetricsRecorder metrics;
    private final ShadowDispatcher shadowDispatcher;

    public ChatController(
            @Qualifier("primaryInferenceClient") InferenceClient primaryClient,
            ProxyProperties properties,
            ObjectMapper objectMapper,
            MetricsRecorder metrics,
            ShadowDispatcher shadowDispatcher) {
        this.primaryClient = primaryClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.shadowDispatcher = shadowDispatcher;
    }

    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> chat(
            @RequestBody byte[] requestBody, HttpServletRequest request) {
        metrics.recordRequest();
        validateRequest(requestBody);

        var completion =
                primaryClient.complete(properties.upstream().primaryModel(), requestBody);
        if (completion.truncated()) {
            throw new UpstreamUnreadableException();
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.status(completion.status());
        if (completion.contentType() != null) {
            response.contentType(completion.contentType());
        }
        if (completion.retryAfter() != null) {
            response.header(HttpHeaders.RETRY_AFTER, completion.retryAfter());
        }

        if (completion.isSuccess()) {
            shadowDispatcher.submit(
                    new ShadowJob(
                            RequestIdFilter.currentId(request),
                            requestBody.clone(),
                            completion.body().clone(),
                            System.nanoTime()));
        }
        return response.body(completion.body());
    }

    private void validateRequest(byte[] requestBody) {
        try {
            var root = objectMapper.readTree(requestBody);
            if (!(root instanceof ObjectNode object)) {
                throw new InvalidChatRequestException("request body must be a JSON object");
            }
            if (object.path("stream").asBoolean(false)) {
                throw new StreamingNotSupportedException();
            }
        } catch (InvalidChatRequestException | StreamingNotSupportedException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw new InvalidChatRequestException("request body must be valid JSON", exception);
        }
    }
}
