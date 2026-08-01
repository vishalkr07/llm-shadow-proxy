package com.example.shadowproxy.metrics;

import com.example.shadowproxy.eval.EvalResult;

public interface MetricsRecorder {
    void recordRequest();

    default void recordShadowEligible() {}

    default void recordShadowEnqueued() {}

    default void recordShadowShed(ShedReason reason) {}

    default void recordShadowCompleted(ShadowOutcome outcome) {}

    default void recordEvaluation(EvalResult result) {}
}
