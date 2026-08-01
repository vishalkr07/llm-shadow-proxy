package com.example.shadowproxy.eval;

import com.example.shadowproxy.config.ProxyProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class HeuristicEvaluator implements Evaluator {
    public static final String EVAL_VERSION = "1";

    private final ObjectMapper objectMapper;
    private final int maxContentBytes;

    @Autowired
    public HeuristicEvaluator(ObjectMapper objectMapper, ProxyProperties properties) {
        this(objectMapper, properties.limits().maxEvalContentBytes());
    }

    HeuristicEvaluator(ObjectMapper objectMapper, int maxContentBytes) {
        this.objectMapper = objectMapper;
        this.maxContentBytes = maxContentBytes;
    }

    @Override
    public EvalResult compare(byte[] primaryBody, byte[] candidateBody) {
        SideResult primary = parse(primaryBody);
        SideResult candidate = parse(candidateBody);

        if (!primary.usable() && !candidate.usable()) {
            return new EvalResult(
                    EvalVerdict.BOTH_UNUSABLE, primary.reason(), candidate.reason(), false);
        }
        if (!primary.usable()) {
            return new EvalResult(
                    EvalVerdict.PRIMARY_UNUSABLE, primary.reason(), null, false);
        }
        if (!candidate.usable()) {
            return new EvalResult(
                    EvalVerdict.CANDIDATE_UNUSABLE, null, candidate.reason(), false);
        }
        if (primary.action().equals(candidate.action())) {
            return new EvalResult(EvalVerdict.MATCH, null, null, false);
        }

        boolean nearMatch =
                primary.action().trim().equalsIgnoreCase(candidate.action().trim());
        return new EvalResult(EvalVerdict.MISMATCH, null, null, nearMatch);
    }

    private SideResult parse(byte[] completionBody) {
        try {
            JsonNode completion = objectMapper.readTree(completionBody);
            JsonNode choice = completion.path("choices").path(0);
            if ("length".equals(choice.path("finish_reason").asText())) {
                return SideResult.unusable(UnusableReason.CONTENT_TRUNCATED);
            }

            JsonNode contentNode = choice.path("message").path("content");
            if (!contentNode.isString() || contentNode.asText().isBlank()) {
                return SideResult.unusable(UnusableReason.EMPTY_CONTENT);
            }

            String content = contentNode.asText();
            if (content.getBytes(StandardCharsets.UTF_8).length > maxContentBytes) {
                return SideResult.unusable(UnusableReason.CONTENT_TRUNCATED);
            }

            content = stripSingleCodeFence(content.trim());
            JsonNode payload;
            try (var parser = objectMapper.createParser(content)) {
                payload = objectMapper.readTree(parser);
                if (parser.nextToken() != null) {
                    return SideResult.unusable(UnusableReason.TRAILING_GARBAGE);
                }
            }

            if (payload == null) {
                return SideResult.unusable(UnusableReason.NOT_JSON);
            }
            if (!payload.isObject()) {
                return SideResult.unusable(UnusableReason.NOT_OBJECT);
            }
            JsonNode action = payload.get("action");
            if (action == null) {
                return SideResult.unusable(UnusableReason.ACTION_ABSENT);
            }
            if (!action.isString()) {
                return SideResult.unusable(UnusableReason.ACTION_NOT_STRING);
            }
            return SideResult.usable(action.asText());
        } catch (JacksonException exception) {
            return SideResult.unusable(UnusableReason.NOT_JSON);
        } catch (RuntimeException exception) {
            return SideResult.unusable(UnusableReason.NOT_JSON);
        }
    }

    private static String stripSingleCodeFence(String content) {
        if (!content.startsWith("```") || !content.endsWith("```")) {
            return content;
        }
        int firstNewline = content.indexOf('\n');
        if (firstNewline < 0 || firstNewline >= content.length() - 3) {
            return content;
        }
        return content.substring(firstNewline + 1, content.length() - 3).trim();
    }

    private record SideResult(String action, UnusableReason reason) {
        static SideResult usable(String action) {
            return new SideResult(action, null);
        }

        static SideResult unusable(UnusableReason reason) {
            return new SideResult(null, reason);
        }

        boolean usable() {
            return reason == null;
        }
    }
}
