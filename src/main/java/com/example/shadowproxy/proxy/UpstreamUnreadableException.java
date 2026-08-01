package com.example.shadowproxy.proxy;

final class UpstreamUnreadableException extends RuntimeException {
    UpstreamUnreadableException() {
        super("primary response exceeded the configured size limit");
    }
}
