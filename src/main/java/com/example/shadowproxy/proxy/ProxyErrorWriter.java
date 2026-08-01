package com.example.shadowproxy.proxy;

import com.example.shadowproxy.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProxyErrorWriter {
    private final ObjectMapper objectMapper;

    public ProxyErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body(request, code, message));
    }

    public Map<String, Object> body(
            HttpServletRequest request, String code, String message) {
        return Map.of(
                "error",
                Map.of(
                        "type", "proxy_error",
                        "code", code,
                        "message", message,
                        "request_id", RequestIdFilter.currentId(request)));
    }
}
