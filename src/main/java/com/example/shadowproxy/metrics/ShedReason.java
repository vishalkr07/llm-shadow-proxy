package com.example.shadowproxy.metrics;

public enum ShedReason {
    SAMPLED_OUT,
    QUEUE_FULL,
    BYTE_BUDGET,
    BREAKER_OPEN,
    STALE_IN_QUEUE,
    SHUTTING_DOWN
}
