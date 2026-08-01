package com.example.shadowproxy.config;

import com.example.shadowproxy.inference.InferenceClient;
import com.example.shadowproxy.inference.InferenceGateway;
import com.example.shadowproxy.inference.MockInferenceClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class InferenceClientConfig {

    @Bean(destroyMethod = "close")
    InferenceGateway primaryInferenceClient(
            ObjectMapper objectMapper, ProxyProperties properties) {
        if (properties.mock().enabled()) {
            return new MockInferenceClient(
                    objectMapper, properties.mock().actions(), "mock-primary");
        }
        return realClient(
                objectMapper,
                properties,
                properties.timeouts().primary(),
                properties.limits().maxConcurrentRequests(),
                false);
    }

    @Bean(destroyMethod = "close")
    InferenceGateway candidateInferenceClient(
            ObjectMapper objectMapper, ProxyProperties properties) {
        if (properties.mock().enabled()) {
            return new MockInferenceClient(
                    objectMapper, properties.mock().actions(), "mock-candidate");
        }
        return realClient(
                objectMapper,
                properties,
                properties.timeouts().shadow(),
                properties.shadow().workers(),
                true);
    }

    private static InferenceGateway realClient(
            ObjectMapper objectMapper,
            ProxyProperties properties,
            java.time.Duration readTimeout,
            int maxConnections,
            boolean shadowRole) {
        var connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(maxConnections)
                        .setMaxConnPerRoute(maxConnections)
                        .setDefaultConnectionConfig(
                                ConnectionConfig.custom()
                                        .setConnectTimeout(
                                                Timeout.of(properties.timeouts().connect()))
                                        .setSocketTimeout(Timeout.of(readTimeout))
                                        .build())
                        .build();

        CloseableHttpClient httpClient =
                HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()
                .build();
        RestClient restClient =
                RestClient.builder()
                .baseUrl(properties.upstream().baseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .defaultHeaders(
                        headers -> {
                            headers.setBearerAuth(properties.upstream().apiKey());
                            headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
                        })
                .build();
        return new InferenceClient(
                restClient,
                objectMapper,
                properties.limits().maxResponseBytes(),
                shadowRole,
                httpClient);
    }
}
