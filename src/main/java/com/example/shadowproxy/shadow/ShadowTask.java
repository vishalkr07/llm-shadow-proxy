package com.example.shadowproxy.shadow;

import com.example.shadowproxy.config.ProxyProperties;
import com.example.shadowproxy.eval.Evaluator;
import com.example.shadowproxy.inference.InferenceClient;
import com.example.shadowproxy.metrics.MetricsRecorder;
import com.example.shadowproxy.metrics.ShadowOutcome;
import com.example.shadowproxy.metrics.ShedReason;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

final class ShadowTask implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowTask.class);

    private final ShadowJob job;
    private final long reservedBytes;
    private final ByteBudget byteBudget;
    private final ProxyProperties properties;
    private final InferenceClient candidateClient;
    private final Evaluator evaluator;
    private final MetricsRecorder metrics;
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile boolean rejected;

    ShadowTask(
            ShadowJob job,
            long reservedBytes,
            ByteBudget byteBudget,
            ProxyProperties properties,
            InferenceClient candidateClient,
            Evaluator evaluator,
            MetricsRecorder metrics) {
        this.job = job;
        this.reservedBytes = reservedBytes;
        this.byteBudget = byteBudget;
        this.properties = properties;
        this.candidateClient = candidateClient;
        this.evaluator = evaluator;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        try {
            if (isStale()) {
                metrics.recordShadowShed(ShedReason.STALE_IN_QUEUE);
                return;
            }

            var candidate =
                    candidateClient.complete(
                            properties.upstream().candidateModel(), job.requestBody());
            if (!candidate.isSuccess() || candidate.truncated()) {
                metrics.recordShadowCompleted(ShadowOutcome.ERROR);
                return;
            }

            metrics.recordEvaluation(
                    evaluator.compare(job.primaryResponseBody(), candidate.body()));
            metrics.recordShadowCompleted(ShadowOutcome.OK);
        } catch (ResourceAccessException exception) {
            metrics.recordShadowCompleted(
                    hasCause(exception, SocketTimeoutException.class)
                            ? ShadowOutcome.TIMEOUT
                            : ShadowOutcome.ERROR);
        } catch (Throwable throwable) {
            metrics.recordShadowCompleted(ShadowOutcome.ERROR);
            LOGGER.warn("Shadow evaluation failed for request {}", job.requestId(), throwable);
        } finally {
            releaseOnce();
        }
    }

    void reject(ShedReason reason) {
        if (released.compareAndSet(false, true)) {
            rejected = true;
            try {
                metrics.recordShadowShed(reason);
            } finally {
                byteBudget.release(reservedBytes);
            }
        }
    }

    boolean wasRejected() {
        return rejected;
    }

    private boolean isStale() {
        long age = System.nanoTime() - job.capturedAtNanos();
        return age > properties.shadow().taskTtl().toNanos();
    }

    private void releaseOnce() {
        if (released.compareAndSet(false, true)) {
            byteBudget.release(reservedBytes);
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
