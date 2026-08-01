package com.example.shadowproxy.config;

import com.example.shadowproxy.inference.InferenceClient;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class InferenceClientConfig {

    @Bean(destroyMethod = "close")
    CloseableHttpClient primaryHttpClient(ProxyProperties properties) {
        return httpClient(
                properties,
                properties.timeouts().primary(),
                properties.limits().maxConcurrentRequests());
    }

    @Bean(destroyMethod = "close")
    CloseableHttpClient candidateHttpClient(ProxyProperties properties) {
        return httpClient(
                properties, properties.timeouts().shadow(), properties.shadow().workers());
    }

    @Bean
    RestClient primaryRestClient(
            ProxyProperties properties,
            @Qualifier("primaryHttpClient") HttpClient httpClient) {
        return restClient(properties, httpClient);
    }

    @Bean
    RestClient candidateRestClient(
            ProxyProperties properties,
            @Qualifier("candidateHttpClient") HttpClient httpClient) {
        return restClient(properties, httpClient);
    }

    @Bean
    InferenceClient primaryInferenceClient(
            @Qualifier("primaryRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            ProxyProperties properties) {
        return new InferenceClient(
                restClient, objectMapper, properties.limits().maxResponseBytes(), false);
    }

    @Bean
    InferenceClient candidateInferenceClient(
            @Qualifier("candidateRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            ProxyProperties properties) {
        return new InferenceClient(
                restClient, objectMapper, properties.limits().maxResponseBytes(), true);
    }

    private static CloseableHttpClient httpClient(
            ProxyProperties properties, java.time.Duration readTimeout, int maxConnections) {
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

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()
                .build();
    }

    private static RestClient restClient(ProxyProperties properties, HttpClient httpClient) {
        return RestClient.builder()
                .baseUrl(properties.upstream().baseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .defaultHeaders(
                        headers -> {
                            headers.setBearerAuth(properties.upstream().apiKey());
                            headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
                        })
                .build();
    }
}
