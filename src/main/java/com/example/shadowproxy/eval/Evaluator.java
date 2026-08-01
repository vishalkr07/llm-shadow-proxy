package com.example.shadowproxy.eval;

public interface Evaluator {
    EvalResult compare(byte[] primaryBody, byte[] candidateBody);
}
