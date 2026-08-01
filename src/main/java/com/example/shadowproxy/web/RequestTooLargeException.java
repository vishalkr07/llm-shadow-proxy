package com.example.shadowproxy.web;

public final class RequestTooLargeException extends RuntimeException {
    public RequestTooLargeException() {
        super("request body exceeded the configured size limit");
    }
}
