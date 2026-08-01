package com.example.shadowproxy.inference;

public interface InferenceGateway extends AutoCloseable {
    ChatCompletion complete(String model, byte[] requestBody);

    @Override
    default void close() {}
}
