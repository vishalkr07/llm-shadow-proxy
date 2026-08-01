package com.example.shadowproxy.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HeuristicEvaluatorTest {
    private final HeuristicEvaluator evaluator = new HeuristicEvaluator(new ObjectMapper(), 1024);

    @Test
    void matchesExactActions() {
        EvalResult result = evaluator.compare(completion("{\"action\":\"refund\"}"), completion("{\"action\":\"refund\"}"));

        assertThat(result.verdict()).isEqualTo(EvalVerdict.MATCH);
        assertThat(result.nearMatch()).isFalse();
    }

    @Test
    void keepsCaseSensitiveDifferenceAsMismatchButReportsNearMatch() {
        EvalResult result = evaluator.compare(completion("{\"action\":\"refund\"}"), completion("{\"action\":\" Refund \"}"));

        assertThat(result.verdict()).isEqualTo(EvalVerdict.MISMATCH);
        assertThat(result.nearMatch()).isTrue();
    }

    @Test
    void acceptsOneSurroundingMarkdownFence() {
        EvalResult result =
                evaluator.compare(
                        completion("{\"action\":\"refund\"}"),
                        completion("```json\n{\"action\":\"refund\"}\n```"));

        assertThat(result.verdict()).isEqualTo(EvalVerdict.MATCH);
    }

    @Test
    void identifiesCandidateFormattingFailuresSeparately() {
        EvalResult result =
                evaluator.compare(
                        completion("{\"action\":\"refund\"}"),
                        completion("not json"));

        assertThat(result.verdict()).isEqualTo(EvalVerdict.CANDIDATE_UNUSABLE);
        assertThat(result.candidateReason()).isEqualTo(UnusableReason.NOT_JSON);
    }

    @Test
    void checksTruncationBeforeParsingContent() {
        byte[] truncated =
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);

        EvalResult result = evaluator.compare(completion("{\"action\":\"refund\"}"), truncated);

        assertThat(result.verdict()).isEqualTo(EvalVerdict.CANDIDATE_UNUSABLE);
        assertThat(result.candidateReason()).isEqualTo(UnusableReason.CONTENT_TRUNCATED);
    }

    @Test
    void requiresActionToBeAString() {
        EvalResult result =
                evaluator.compare(
                        completion("{\"action\":\"refund\"}"),
                        completion("{\"action\":1}"));

        assertThat(result.verdict()).isEqualTo(EvalVerdict.CANDIDATE_UNUSABLE);
        assertThat(result.candidateReason()).isEqualTo(UnusableReason.ACTION_NOT_STRING);
    }

    private static byte[] completion(String content) {
        String escaped =
                content.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n");
        return ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\""
                        + escaped
                        + "\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
