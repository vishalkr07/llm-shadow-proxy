package com.example.shadowproxy.web;

import com.example.shadowproxy.proxy.ProxyErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RequestSizeLimitFilter extends OncePerRequestFilter {
    private final int maxRequestBytes;
    private final ProxyErrorWriter errorWriter;

    public RequestSizeLimitFilter(int maxRequestBytes, ProxyErrorWriter errorWriter) {
        this.maxRequestBytes = maxRequestBytes;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxRequestBytes) {
            writeTooLarge(request, response);
            return;
        }

        try {
            filterChain.doFilter(new LimitedRequest(request, maxRequestBytes), response);
        } catch (RequestTooLargeException exception) {
            if (!response.isCommitted()) {
                response.resetBuffer();
                writeTooLarge(request, response);
            }
        }
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        errorWriter.write(
                request,
                response,
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "request_too_large",
                "request body exceeded the configured size limit");
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final int limit;
        private ServletInputStream inputStream;

        private LimitedRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new LimitedServletInputStream(super.getInputStream(), limit);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset =
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final int limit;
        private int bytesRead;

        private LimitedServletInputStream(ServletInputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int count) {
            bytesRead += count;
            if (bytesRead > limit) {
                throw new RequestTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
