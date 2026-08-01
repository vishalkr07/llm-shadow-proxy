package com.example.shadowproxy.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shadowproxy.eval.EvalResult;
import com.example.shadowproxy.eval.EvalVerdict;
import com.example.shadowproxy.eval.UnusableReason;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class MetricsRegistryTest {

    @Test
    void snapshotsCountersRatesReasonsAndLiveGauges() {
        MetricsRegistry registry = registry(gauges());
        registry.recordRequest();
        registry.recordRequest();
        for (int i = 0; i < 5; i++) {
            registry.recordShadowEligible();
        }
        for (int i = 0; i < 3; i++) {
            registry.recordShadowEnqueued();
        }
        registry.recordShadowShed(ShedReason.QUEUE_FULL);
        registry.recordShadowShed(ShedReason.BYTE_BUDGET);
        registry.recordShadowCompleted(ShadowOutcome.OK);
        registry.recordShadowCompleted(ShadowOutcome.ERROR);
        registry.recordShadowCompleted(ShadowOutcome.TIMEOUT);
        registry.recordEvaluation(new EvalResult(EvalVerdict.MATCH, null, null, false));
        registry.recordEvaluation(new EvalResult(EvalVerdict.MISMATCH, null, null, true));
        registry.recordEvaluation(
                new EvalResult(
                        EvalVerdict.CANDIDATE_UNUSABLE,
                        null,
                        UnusableReason.NOT_JSON,
                        false));
        registry.recordEvaluation(
                new EvalResult(
                        EvalVerdict.BOTH_UNUSABLE,
                        UnusableReason.EMPTY_CONTENT,
                        UnusableReason.ACTION_ABSENT,
                        false));

        MetricsSnapshot snapshot = registry.snapshot();

        assertThat(snapshot.totalRequests()).isEqualTo(2);
        assertThat(snapshot.shadow().eligible()).isEqualTo(5);
        assertThat(snapshot.shadow().enqueued()).isEqualTo(3);
        assertThat(snapshot.shadow().coveragePct()).isEqualTo(60);
        assertThat(snapshot.shadow().shedTotal()).isEqualTo(2);
        assertThat(snapshot.shadow().completed()).isEqualTo(3);
        assertThat(snapshot.shadow().errors()).isEqualTo(1);
        assertThat(snapshot.shadow().timeouts()).isEqualTo(1);
        assertThat(snapshot.shadow().queueDepth()).isEqualTo(2);
        assertThat(snapshot.shadow().bytesUsed()).isEqualTo(1024);
        assertThat(snapshot.evaluation().evaluated()).isEqualTo(4);
        assertThat(snapshot.evaluation().exactMatchRate()).isEqualTo(50);
        assertThat(snapshot.evaluation().nearMatches()).isEqualTo(1);
        assertThat(snapshot.evaluation().exactMatchRateCi95().low()).isEqualTo(9.45);
        assertThat(snapshot.evaluation().exactMatchRateCi95().high()).isEqualTo(90.55);
        assertThat(snapshot.evaluation().unusableByReason().primary())
                .containsEntry("empty_content", 1L);
        assertThat(snapshot.evaluation().unusableByReason().candidate())
                .containsEntry("not_json", 1L)
                .containsEntry("action_absent", 1L);
    }

    @Test
    void zeroDenominatorsProduceJsonSafeZeroRates() {
        MetricsSnapshot snapshot = registry(ShadowGauges.EMPTY).snapshot();

        assertThat(snapshot.shadow().coveragePct()).isZero();
        assertThat(snapshot.evaluation().exactMatchRate()).isZero();
        assertThat(snapshot.evaluation().exactMatchRateCi95().low()).isZero();
        assertThat(snapshot.evaluation().exactMatchRateCi95().high()).isZero();
    }

    @Test
    void concurrentWritersDoNotLoseIncrements() throws Exception {
        MetricsRegistry registry = registry(ShadowGauges.EMPTY);
        var executor = Executors.newFixedThreadPool(8);

        for (int worker = 0; worker < 8; worker++) {
            executor.submit(
                    () -> {
                        for (int increment = 0; increment < 10_000; increment++) {
                            registry.recordRequest();
                        }
                    });
        }
        executor.shutdown();

        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.snapshot().totalRequests()).isEqualTo(80_000);
    }

    private static MetricsRegistry registry(ShadowGauges gauges) {
        var factory = new StaticListableBeanFactory(Map.of("gauges", gauges));
        return new MetricsRegistry(factory.getBeanProvider(ShadowGauges.class), "test-replica");
    }

    private static ShadowGauges gauges() {
        return new ShadowGauges() {
            @Override
            public int queueDepth() {
                return 2;
            }

            @Override
            public int queueCapacity() {
                return 32;
            }

            @Override
            public long bytesUsed() {
                return 1024;
            }

            @Override
            public long bytesCapacity() {
                return 8192;
            }

            @Override
            public int inFlight() {
                return 3;
            }

            @Override
            public int workers() {
                return 8;
            }

            @Override
            public double currentAdmitProbability() {
                return 0.75;
            }

            @Override
            public String breakerState() {
                return "closed";
            }
        };
    }
}
