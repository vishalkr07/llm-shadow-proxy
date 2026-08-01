package com.example.shadowproxy.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "proxy.mock.enabled=true",
            "proxy.mock.actions=approve",
            "management.server.port=0"
        })
class MockInferenceModeIntegrationTest {
    @LocalServerPort private int port;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void runsWithoutCredentialsAndEvaluatesMockPrimaryAgainstMockCandidate()
            throws Exception {
        HttpRequest chat =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"messages\":[{\"role\":\"user\",\"content\":\"test\"}]}"))
                        .build();

        HttpResponse<String> response =
                client.send(chat, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"model\":\"mock-primary\"")
                .contains("\\\"action\\\":\\\"approve\\\"");

        String metrics = awaitCompletedMetrics();
        assertThat(metrics)
                .contains("\"completed\":1")
                .contains("\"evaluated\":1")
                .contains("\"exact_matches\":1");
    }

    private String awaitCompletedMetrics() throws Exception {
        URI metricUri = URI.create("http://localhost:" + port + "/metric");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        String body = "";
        do {
            HttpResponse<byte[]> response =
                    client.send(
                            HttpRequest.newBuilder(metricUri).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            body = new String(response.body(), StandardCharsets.UTF_8);
            if (body.contains("\"completed\":1")) {
                return body;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return body;
    }
}
