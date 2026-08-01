package com.example.shadowproxy.eval;

public enum UnusableReason {
    EMPTY_CONTENT,
    CONTENT_TRUNCATED,
    NOT_JSON,
    TRAILING_GARBAGE,
    NOT_OBJECT,
    ACTION_ABSENT,
    ACTION_NOT_STRING
}
