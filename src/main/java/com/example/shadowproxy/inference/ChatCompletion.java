package com.example.shadowproxy.inference;

import org.springframework.http.MediaType;

public record ChatCompletion(
        int status, byte[] body, MediaType contentType, String retryAfter, boolean truncated) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
