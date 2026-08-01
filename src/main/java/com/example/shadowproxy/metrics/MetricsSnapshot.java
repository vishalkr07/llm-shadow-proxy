package com.example.shadowproxy.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record MetricsSnapshot(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("eval_version") String evalVersion,
        String scope,
        @JsonProperty("replica_id") String replicaId,
        @JsonProperty("uptime_seconds") long uptimeSeconds,
        @JsonProperty("total_requests") long totalRequests,
        ShadowSnapshot shadow,
        EvaluationSnapshot evaluation) {

    public record ShadowSnapshot(
            long eligible,
            long enqueued,
            @JsonProperty("coverage_pct") double coveragePct,
            @JsonProperty("current_admit_probability") double currentAdmitProbability,
            @JsonProperty("shed_total") long shedTotal,
            @JsonProperty("shed_by_reason") Map<String, Long> shedByReason,
            long completed,
            long errors,
            long timeouts,
            @JsonProperty("queue_depth") int queueDepth,
            @JsonProperty("queue_capacity") int queueCapacity,
            @JsonProperty("bytes_used") long bytesUsed,
            @JsonProperty("bytes_capacity") long bytesCapacity,
            @JsonProperty("in_flight") int inFlight,
            int workers,
            @JsonProperty("breaker_state") String breakerState) {}

    public record EvaluationSnapshot(
            long evaluated,
            @JsonProperty("exact_matches") long exactMatches,
            long mismatches,
            @JsonProperty("near_matches") long nearMatches,
            @JsonProperty("primary_unusable") long primaryUnusable,
            @JsonProperty("candidate_unusable") long candidateUnusable,
            @JsonProperty("both_unusable") long bothUnusable,
            @JsonProperty("exact_match_rate") double exactMatchRate,
            @JsonProperty("exact_match_rate_basis") String exactMatchRateBasis,
            @JsonProperty("exact_match_rate_ci95") ConfidenceInterval exactMatchRateCi95,
            @JsonProperty("unusable_by_reason") UnusableByReason unusableByReason) {}

    public record ConfidenceInterval(double low, double high, String method) {}

    public record UnusableByReason(Map<String, Long> primary, Map<String, Long> candidate) {}
}
