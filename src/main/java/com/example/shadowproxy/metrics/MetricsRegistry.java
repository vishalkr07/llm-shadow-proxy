package com.example.shadowproxy.metrics;

import com.example.shadowproxy.eval.EvalResult;
import com.example.shadowproxy.eval.EvalVerdict;
import com.example.shadowproxy.eval.HeuristicEvaluator;
import com.example.shadowproxy.eval.UnusableReason;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class MetricsRegistry implements MetricsRecorder, MetricsReader {
    private static final String MATCH_RATE_BASIS = "exact_matches / (exact_matches + mismatches)";
    private static final double WILSON_Z = 1.959963984540054;

    private final long startedAtNanos = System.nanoTime();
    private final String replicaId;
    private final ObjectProvider<ShadowGauges> gaugesProvider;

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder eligible = new LongAdder();
    private final LongAdder enqueued = new LongAdder();
    private final EnumMap<ShedReason, LongAdder> shed = counters(ShedReason.class);
    private final EnumMap<ShadowOutcome, LongAdder> outcomes = counters(ShadowOutcome.class);
    private final EnumMap<EvalVerdict, LongAdder> verdicts = counters(EvalVerdict.class);
    private final LongAdder nearMatches = new LongAdder();
    private final EnumMap<UnusableReason, LongAdder> primaryReasons =
            counters(UnusableReason.class);
    private final EnumMap<UnusableReason, LongAdder> candidateReasons =
            counters(UnusableReason.class);

    public MetricsRegistry(
            ObjectProvider<ShadowGauges> gaugesProvider,
            @Value("${HOSTNAME:local}") String replicaId) {
        this.gaugesProvider = gaugesProvider;
        this.replicaId = replicaId;
    }

    @Override
    public void recordRequest() {
        totalRequests.increment();
    }

    @Override
    public void recordShadowEligible() {
        eligible.increment();
    }

    @Override
    public void recordShadowEnqueued() {
        enqueued.increment();
    }

    @Override
    public void recordShadowShed(ShedReason reason) {
        shed.get(reason).increment();
    }

    @Override
    public void recordShadowCompleted(ShadowOutcome outcome) {
        outcomes.get(outcome).increment();
    }

    @Override
    public void recordEvaluation(EvalResult result) {
        verdicts.get(result.verdict()).increment();
        if (result.nearMatch()) {
            nearMatches.increment();
        }
        if (result.primaryReason() != null) {
            primaryReasons.get(result.primaryReason()).increment();
        }
        if (result.candidateReason() != null) {
            candidateReasons.get(result.candidateReason()).increment();
        }
    }

    @Override
    public MetricsSnapshot snapshot() {
        ShadowGauges gauges = gaugesProvider.getIfAvailable(() -> ShadowGauges.EMPTY);
        long eligibleCount = eligible.sum();
        long enqueuedCount = enqueued.sum();
        long shedTotal = sum(shed);
        long matches = verdicts.get(EvalVerdict.MATCH).sum();
        long mismatches = verdicts.get(EvalVerdict.MISMATCH).sum();
        long primaryUnusable = verdicts.get(EvalVerdict.PRIMARY_UNUSABLE).sum();
        long candidateUnusable = verdicts.get(EvalVerdict.CANDIDATE_UNUSABLE).sum();
        long bothUnusable = verdicts.get(EvalVerdict.BOTH_UNUSABLE).sum();
        long evaluated =
                matches
                        + mismatches
                        + primaryUnusable
                        + candidateUnusable
                        + bothUnusable;
        long matchRateDenominator = matches + mismatches;

        return new MetricsSnapshot(
                "1",
                HeuristicEvaluator.EVAL_VERSION,
                "replica_local",
                replicaId,
                (System.nanoTime() - startedAtNanos) / 1_000_000_000L,
                totalRequests.sum(),
                new MetricsSnapshot.ShadowSnapshot(
                        eligibleCount,
                        enqueuedCount,
                        percentage(enqueuedCount, eligibleCount),
                        gauges.currentAdmitProbability(),
                        shedTotal,
                        namedCounts(shed),
                        sum(outcomes),
                        outcomes.get(ShadowOutcome.ERROR).sum(),
                        outcomes.get(ShadowOutcome.TIMEOUT).sum(),
                        gauges.queueDepth(),
                        gauges.queueCapacity(),
                        gauges.bytesUsed(),
                        gauges.bytesCapacity(),
                        gauges.inFlight(),
                        gauges.workers(),
                        gauges.breakerState()),
                new MetricsSnapshot.EvaluationSnapshot(
                        evaluated,
                        matches,
                        mismatches,
                        nearMatches.sum(),
                        primaryUnusable,
                        candidateUnusable,
                        bothUnusable,
                        percentage(matches, matchRateDenominator),
                        MATCH_RATE_BASIS,
                        wilson(matches, matchRateDenominator),
                        new MetricsSnapshot.UnusableByReason(
                                namedCounts(primaryReasons), namedCounts(candidateReasons))));
    }

    private static MetricsSnapshot.ConfidenceInterval wilson(long successes, long total) {
        if (total == 0) {
            return new MetricsSnapshot.ConfidenceInterval(0, 0, "wilson");
        }
        double n = total;
        double p = successes / n;
        double zSquared = WILSON_Z * WILSON_Z;
        double denominator = 1 + zSquared / n;
        double center = (p + zSquared / (2 * n)) / denominator;
        double margin =
                WILSON_Z
                        * Math.sqrt((p * (1 - p) + zSquared / (4 * n)) / n)
                        / denominator;
        return new MetricsSnapshot.ConfidenceInterval(
                round2(100 * Math.max(0, center - margin)),
                round2(100 * Math.min(1, center + margin)),
                "wilson");
    }

    private static double percentage(long numerator, long denominator) {
        return denominator == 0 ? 0 : round2(100.0 * numerator / denominator);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static <E extends Enum<E>> EnumMap<E, LongAdder> counters(Class<E> type) {
        EnumMap<E, LongAdder> result = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            result.put(value, new LongAdder());
        }
        return result;
    }

    private static <E extends Enum<E>> long sum(EnumMap<E, LongAdder> counters) {
        return counters.values().stream().mapToLong(LongAdder::sum).sum();
    }

    private static <E extends Enum<E>> Map<String, Long> namedCounts(
            EnumMap<E, LongAdder> counters) {
        Map<String, Long> result = new LinkedHashMap<>();
        counters.forEach(
                (key, value) ->
                        result.put(key.name().toLowerCase(Locale.ROOT), value.sum()));
        return Map.copyOf(result);
    }
}
