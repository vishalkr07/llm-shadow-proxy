package com.example.shadowproxy.proxy;

final class StreamingNotSupportedException extends RuntimeException {
    StreamingNotSupportedException() {
        super("streaming responses are not supported");
    }
}
