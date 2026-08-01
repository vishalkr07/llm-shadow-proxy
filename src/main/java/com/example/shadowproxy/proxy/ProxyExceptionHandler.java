package com.example.shadowproxy.proxy;

import com.example.shadowproxy.inference.InvalidChatRequestException;
import com.example.shadowproxy.web.RequestTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

@RestControllerAdvice
public class ProxyExceptionHandler {
    private final ProxyErrorWriter errorWriter;

    public ProxyExceptionHandler(ProxyErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @ExceptionHandler({
        InvalidChatRequestException.class,
        StreamingNotSupportedException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<Map<String, Object>> badRequest(
            Exception exception, HttpServletRequest request) {
        if (hasCause(exception, RequestTooLargeException.class)) {
            return error(
                    request,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "request_too_large",
                    "request body exceeded the configured size limit");
        }
        String code =
                exception instanceof StreamingNotSupportedException
                        ? "streaming_not_supported"
                        : "invalid_request";
        return error(request, HttpStatus.BAD_REQUEST, code, exception.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> methodNotAllowed(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return error(
                request, HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", exception.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> unsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return error(
                request,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_media_type",
                exception.getMessage());
    }

    @ExceptionHandler(UpstreamUnreadableException.class)
    ResponseEntity<Map<String, Object>> unreadableUpstream(
            UpstreamUnreadableException exception, HttpServletRequest request) {
        return error(
                request, HttpStatus.BAD_GATEWAY, "upstream_unreadable", exception.getMessage());
    }

    @ExceptionHandler(ResourceAccessException.class)
    ResponseEntity<Map<String, Object>> upstreamUnavailable(
            ResourceAccessException exception, HttpServletRequest request) {
        if (hasCause(exception, SocketTimeoutException.class)) {
            return error(
                    request,
                    HttpStatus.GATEWAY_TIMEOUT,
                    "upstream_timeout",
                    "primary model exceeded its response timeout");
        }
        return error(
                request,
                HttpStatus.BAD_GATEWAY,
                "upstream_unavailable",
                "primary model could not be reached");
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpServletRequest request, HttpStatus status, String code, String message) {
        String safeMessage = message == null || message.isBlank() ? status.getReasonPhrase() : message;
        return ResponseEntity.status(status).body(errorWriter.body(request, code, safeMessage));
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
