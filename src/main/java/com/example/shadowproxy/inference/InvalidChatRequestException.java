package com.example.shadowproxy.inference;

public final class InvalidChatRequestException extends RuntimeException {
    public InvalidChatRequestException(String message) {
        super(message);
    }

    public InvalidChatRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
