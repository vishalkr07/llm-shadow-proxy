package com.example.shadowproxy.shadow;

import com.example.shadowproxy.config.ProxyProperties;
import com.example.shadowproxy.eval.Evaluator;
import com.example.shadowproxy.inference.InferenceClient;
import com.example.shadowproxy.metrics.MetricsRecorder;
import com.example.shadowproxy.metrics.ShedReason;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class BoundedShadowDispatcher implements ShadowDispatcher {
    private final ProxyProperties properties;
    private final MetricsRecorder metrics;
    private final ByteBudget byteBudget;
    private final ThreadPoolExecutor executor;
    private final InferenceClient candidateClient;
    private final Evaluator evaluator;
    private volatile boolean shuttingDown;

    public BoundedShadowDispatcher(
            ProxyProperties properties,
            MetricsRecorder metrics,
            @Qualifier("candidateInferenceClient") InferenceClient candidateClient,
            Evaluator evaluator) {
        this.properties = properties;
        this.metrics = metrics;
        this.candidateClient = candidateClient;
        this.evaluator = evaluator;
        this.byteBudget = new ByteBudget(properties.shadow().budgetBytes());

        int workers = properties.shadow().workers();
        this.executor =
                new ThreadPoolExecutor(
                        workers,
                        workers,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(properties.shadow().queueSize()),
                        new ShadowThreadFactory(),
                        (runnable, rejectedExecutor) ->
                                ((ShadowTask) runnable)
                                        .reject(
                                                rejectedExecutor.isShutdown()
                                                        ? ShedReason.SHUTTING_DOWN
                                                        : ShedReason.QUEUE_FULL));
    }

    @Override
    public void submit(ShadowJob job) {
        metrics.recordShadowEligible();
        if (shuttingDown) {
            metrics.recordShadowShed(ShedReason.SHUTTING_DOWN);
            return;
        }
        if (!sampledIn()) {
            metrics.recordShadowShed(ShedReason.SAMPLED_OUT);
            return;
        }

        long reservedBytes = ByteBudget.reservationFor(job);
        if (!byteBudget.tryReserve(reservedBytes)) {
            metrics.recordShadowShed(ShedReason.BYTE_BUDGET);
            return;
        }

        var task =
                new ShadowTask(
                        job,
                        reservedBytes,
                        byteBudget,
                        properties,
                        candidateClient,
                        evaluator,
                        metrics);
        try {
            executor.execute(task);
            if (!task.wasRejected()) {
                metrics.recordShadowEnqueued();
            }
        } catch (RuntimeException exception) {
            task.reject(shuttingDown ? ShedReason.SHUTTING_DOWN : ShedReason.QUEUE_FULL);
        }
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    public int inFlight() {
        return executor.getActiveCount();
    }

    public long bytesUsed() {
        return byteBudget.used();
    }

    private boolean sampledIn() {
        double rate = properties.shadow().sampleRate();
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        List<Runnable> queued = executor.shutdownNow();
        queued.forEach(runnable -> ((ShadowTask) runnable).reject(ShedReason.SHUTTING_DOWN));
        try {
            executor.awaitTermination(
                    properties.timeouts().shutdownGrace().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ShadowThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread =
                    new Thread(runnable, "shadow-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
