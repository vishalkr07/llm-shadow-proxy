package com.example.shadowproxy.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "proxy")
public record ProxyProperties(
        @Valid @NotNull Upstream upstream,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Timeouts timeouts,
        @Valid @NotNull Shadow shadow,
        @Valid @NotNull Mock mock,
        boolean allowAaMode) {

    public record Upstream(
            @NotBlank String baseUrl,
            String apiKey,
            String primaryModel,
            String candidateModel) {
        @Override
        public String toString() {
            return "Upstream[baseUrl=%s, apiKey=****, primaryModel=%s, candidateModel=%s]"
                    .formatted(baseUrl, primaryModel, candidateModel);
        }
    }

    public record Limits(
            @Positive int maxConcurrentRequests,
            @Positive int maxRequestBytes,
            @Positive int maxResponseBytes,
            @Positive int maxEvalContentBytes) {}

    public record Timeouts(
            @NotNull Duration connect,
            @NotNull Duration primary,
            @NotNull Duration shadow,
            @NotNull Duration shutdownGrace) {
        @AssertTrue(message = "all timeouts must be positive")
        public boolean isPositive() {
            return positive(connect) && positive(primary) && positive(shadow) && positive(shutdownGrace);
        }

        private static boolean positive(Duration duration) {
            return duration != null && !duration.isZero() && !duration.isNegative();
        }
    }

    public record Shadow(
            @Positive int queueSize,
            @Positive int workers,
            @Positive long budgetBytes,
            @NotNull Duration taskTtl,
            @DecimalMin("0.0") @DecimalMax("1.0") double sampleRate,
            @DecimalMin("0.0") @DecimalMax("1.0") double controlArmRate) {
        @AssertTrue(message = "SHADOW_TASK_TTL must be positive")
        public boolean isTaskTtlPositive() {
            return taskTtl != null && !taskTtl.isZero() && !taskTtl.isNegative();
        }
    }

    public record Mock(boolean enabled, @NotEmpty List<@NotBlank String> actions) {
        public Mock {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    @AssertTrue(message = "SHADOW_QUEUE_SIZE must be greater than or equal to SHADOW_WORKERS")
    public boolean isQueueLargeEnough() {
        return shadow == null || shadow.queueSize() >= shadow.workers();
    }

    @AssertTrue(
            message =
                    "DO_INFERENCE_API_KEY, PRIMARY_MODEL, and CANDIDATE_MODEL are required unless mock mode is enabled")
    public boolean isRealUpstreamConfigured() {
        return mock == null
                || mock.enabled()
                || (upstream != null
                        && hasText(upstream.apiKey())
                        && hasText(upstream.primaryModel())
                        && hasText(upstream.candidateModel()));
    }

    @AssertTrue(
            message =
                    "PRIMARY_MODEL and CANDIDATE_MODEL must differ unless ALLOW_AA_MODE is true")
    public boolean isModelPairMeaningful() {
        return upstream == null
                || mock == null
                || mock.enabled()
                || allowAaMode
                || !java.util.Objects.equals(
                        upstream.primaryModel(), upstream.candidateModel());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
