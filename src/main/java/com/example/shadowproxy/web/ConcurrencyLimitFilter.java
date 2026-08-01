package com.example.shadowproxy.web;

import com.example.shadowproxy.proxy.ProxyErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

public final class ConcurrencyLimitFilter extends OncePerRequestFilter {
    private final Semaphore permits;
    private final ProxyErrorWriter errorWriter;

    public ConcurrencyLimitFilter(int maxConcurrentRequests, ProxyErrorWriter errorWriter) {
        this.permits = new Semaphore(maxConcurrentRequests, false);
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            errorWriter.write(
                    request,
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "concurrency_limit",
                    "too many requests are currently in flight");
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
