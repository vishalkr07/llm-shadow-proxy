package com.example.shadowproxy.shadow;

public record ShadowJob(
        String requestId,
        byte[] requestBody,
        byte[] primaryResponseBody,
        long capturedAtNanos) {}
