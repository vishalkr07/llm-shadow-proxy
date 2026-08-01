package com.example.shadowproxy.eval;

public record EvalResult(
        EvalVerdict verdict,
        UnusableReason primaryReason,
        UnusableReason candidateReason,
        boolean nearMatch) {}
