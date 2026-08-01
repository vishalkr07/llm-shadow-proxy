package com.example.shadowproxy.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatProxyIntegrationTest {
    private static final UpstreamStub UPSTREAM = new UpstreamStub();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream.base-url", UPSTREAM::baseUrl);
        registry.add("proxy.upstream.api-key", () -> "test-key");
        registry.add("proxy.upstream.primary-model", () -> "primary-model");
        registry.add("proxy.upstream.candidate-model", () -> "candidate-model");
        registry.add("proxy.limits.max-concurrent-requests", () -> 1);
        registry.add("proxy.limits.max-request-bytes", () -> 256);
        registry.add("proxy.timeouts.primary", () -> "1s");
        registry.add("management.server.port", () -> 0);
    }

    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach
    void resetUpstream() {
        UPSTREAM.reset();
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @Test
    void relaysPrimaryStatusAndBodyWithoutReserializingAndOverridesModel() throws Exception {
        byte[] expected =
                "{\"unknown\":true,\"number\":1.0,\"choices\":[]}"
                        .getBytes(StandardCharsets.UTF_8);
        UPSTREAM.respond(201, expected);

        HttpResponse<byte[]> response =
                send("{\"model\":\"attacker-choice\",\"messages\":[]}", "Bearer caller-secret");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo(expected);
        awaitCandidateRequest();
        assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer test-key");
        assertThat(UPSTREAM.lastBody()).contains("\"model\":\"primary-model\"");
        assertThat(UPSTREAM.lastBody()).doesNotContain("attacker-choice");
        assertThat(UPSTREAM.lastShadowHeader()).isNull();
        assertThat(UPSTREAM.lastCandidateBody()).contains("\"model\":\"candidate-model\"");
        assertThat(UPSTREAM.lastCandidateShadowHeader()).isEqualTo("true");
    }

    @Test
    void relaysUpstreamErrorsInsteadOfReplacingThem() throws Exception {
        byte[] expected = "{\"error\":\"model on fire\"}".getBytes(StandardCharsets.UTF_8);
        UPSTREAM.respond(500, expected);

        HttpResponse<byte[]> response = send("{\"messages\":[]}", null);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(expected);
    }

    @Test
    void rejectsStreamingWithoutCallingUpstream() throws Exception {
        HttpResponse<byte[]> response = send("{\"messages\":[],\"stream\":true}", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("\"type\":\"proxy_error\"")
                .contains("\"code\":\"streaming_not_supported\"");
        assertThat(UPSTREAM.requestCount()).isZero();
    }

    @Test
    void rejectsOversizedBodyBeforeCallingUpstream() throws Exception {
        String body = "{\"padding\":\"" + "x".repeat(300) + "\"}";

        HttpResponse<byte[]> response = send(body, null);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(UPSTREAM.requestCount()).isZero();
    }

    @Test
    void mapsPrimaryReadTimeoutToGatewayTimeout() throws Exception {
        UPSTREAM.delay(Duration.ofSeconds(2));

        HttpResponse<byte[]> response = send("{\"messages\":[]}", null);

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("\"code\":\"upstream_timeout\"");
    }

    @Test
    void shedsImmediatelyWhenTheInboundConcurrencyPermitIsTaken() throws Exception {
        CountDownLatch entered = UPSTREAM.block();
        CompletableFuture<HttpResponse<byte[]>> first =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return send("{\"messages\":[]}", null);
                            } catch (Exception exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        HttpResponse<byte[]> rejected = send("{\"messages\":[]}", null);
        UPSTREAM.release();

        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.headers().firstValue("Retry-After")).contains("1");
        first.get(2, TimeUnit.SECONDS);
        awaitCandidateRequest();
    }

    private HttpResponse<byte[]> send(String json, String authorization) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void awaitCandidateRequest() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (UPSTREAM.candidateRequestCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(UPSTREAM.candidateRequestCount()).isEqualTo(1);
    }

    private static final class UpstreamStub implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
        private final AtomicReference<String> lastPrimaryShadowHeader = new AtomicReference<>();
        private final AtomicReference<String> lastPrimaryBody = new AtomicReference<>();
        private final AtomicReference<String> lastCandidateShadowHeader = new AtomicReference<>();
        private final AtomicReference<String> lastCandidateBody = new AtomicReference<>();
        private final AtomicInteger candidateRequests = new AtomicInteger();
        private volatile int status = 200;
        private volatile byte[] response = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
        private volatile Duration delay = Duration.ZERO;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        private UpstreamStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                server.createContext("/v1/chat/completions", this::handle);
                server.setExecutor(executor);
                server.start();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String shadowHeader = exchange.getRequestHeaders().getFirst("X-Shadow-Request");
            String body =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("true".equals(shadowHeader)) {
                candidateRequests.incrementAndGet();
                lastCandidateShadowHeader.set(shadowHeader);
                lastCandidateBody.set(body);
            } else {
                lastPrimaryShadowHeader.set(shadowHeader);
                lastPrimaryBody.set(body);
            }
            CountDownLatch currentEntered = entered;
            if (currentEntered != null) {
                currentEntered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        }

        private void reset() {
            requests.set(0);
            candidateRequests.set(0);
            lastAuthorization.set(null);
            lastPrimaryShadowHeader.set(null);
            lastPrimaryBody.set(null);
            lastCandidateShadowHeader.set(null);
            lastCandidateBody.set(null);
            status = 200;
            response = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            delay = Duration.ZERO;
            entered = null;
            release = null;
        }

        private void respond(int status, byte[] response) {
            this.status = status;
            this.response = response;
        }

        private void delay(Duration duration) {
            delay = duration;
        }

        private CountDownLatch block() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            return entered;
        }

        private void release() {
            release.countDown();
        }

        private int requestCount() {
            return requests.get();
        }

        private String lastAuthorization() {
            return lastAuthorization.get();
        }

        private String lastShadowHeader() {
            return lastPrimaryShadowHeader.get();
        }

        private String lastBody() {
            return lastPrimaryBody.get();
        }

        private int candidateRequestCount() {
            return candidateRequests.get();
        }

        private String lastCandidateShadowHeader() {
            return lastCandidateShadowHeader.get();
        }

        private String lastCandidateBody() {
            return lastCandidateBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
