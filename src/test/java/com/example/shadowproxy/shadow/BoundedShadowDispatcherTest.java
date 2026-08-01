package com.example.shadowproxy.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.shadowproxy.config.ProxyProperties;
import com.example.shadowproxy.eval.EvalResult;
import com.example.shadowproxy.eval.EvalVerdict;
import com.example.shadowproxy.eval.Evaluator;
import com.example.shadowproxy.inference.ChatCompletion;
import com.example.shadowproxy.inference.InferenceClient;
import com.example.shadowproxy.metrics.MetricsRecorder;
import com.example.shadowproxy.metrics.ShedReason;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedShadowDispatcherTest {

    @Test
    void fullQueueShedsWithoutBlockingAndReleasesEveryByteReservation() throws Exception {
        CountDownLatch candidateEntered = new CountDownLatch(1);
        CountDownLatch releaseCandidate = new CountDownLatch(1);
        InferenceClient candidate = mock(InferenceClient.class);
        when(candidate.complete(eq("candidate"), any()))
                .thenAnswer(
                        invocation -> {
                            candidateEntered.countDown();
                            releaseCandidate.await(2, TimeUnit.SECONDS);
                            return new ChatCompletion(200, completion(), null, null, false);
                        });
        Evaluator evaluator = mock(Evaluator.class);
        when(evaluator.compare(any(), any()))
                .thenReturn(new EvalResult(EvalVerdict.MATCH, null, null, false));
        RecordingMetrics metrics = new RecordingMetrics();
        BoundedShadowDispatcher dispatcher =
                new BoundedShadowDispatcher(properties(), metrics, candidate, evaluator);

        dispatcher.submit(job("one"));
        assertThat(candidateEntered.await(1, TimeUnit.SECONDS)).isTrue();
        dispatcher.submit(job("two"));

        long startedAt = System.nanoTime();
        dispatcher.submit(job("three"));
        long submitMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(submitMillis).isLessThan(100);
        assertThat(dispatcher.queueDepth()).isEqualTo(1);
        assertThat(metrics.queueFull.get()).isEqualTo(1);

        releaseCandidate.countDown();
        awaitDrained(dispatcher);
        assertThat(dispatcher.bytesUsed()).isZero();
        dispatcher.shutdown();
    }

    private static ProxyProperties properties() {
        return new ProxyProperties(
                new ProxyProperties.Upstream(
                        "http://localhost", "key", "primary", "candidate"),
                new ProxyProperties.Limits(8, 1024, 1024, 1024),
                new ProxyProperties.Timeouts(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new ProxyProperties.Shadow(
                        1, 1, 8192, Duration.ofSeconds(30), 1.0, 0.0),
                false);
    }

    private static ShadowJob job(String id) {
        return new ShadowJob(id, "{}".getBytes(), completion(), System.nanoTime());
    }

    private static byte[] completion() {
        return "{\"choices\":[{\"message\":{\"content\":\"{\\\"action\\\":\\\"ok\\\"}\"}}]}"
                .getBytes();
    }

    private static void awaitDrained(BoundedShadowDispatcher dispatcher)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dispatcher.bytesUsed() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static final class RecordingMetrics implements MetricsRecorder {
        private final AtomicInteger queueFull = new AtomicInteger();

        @Override
        public void recordRequest() {}

        @Override
        public void recordShadowShed(ShedReason reason) {
            if (reason == ShedReason.QUEUE_FULL) {
                queueFull.incrementAndGet();
            }
        }
    }
}
