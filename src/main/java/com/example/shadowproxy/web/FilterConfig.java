package com.example.shadowproxy.web;

import com.example.shadowproxy.config.ProxyProperties;
import com.example.shadowproxy.proxy.ProxyErrorWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterConfig {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        return registration(new RequestIdFilter(), Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    FilterRegistrationBean<ConcurrencyLimitFilter> concurrencyLimitFilter(
            ProxyProperties properties, ProxyErrorWriter errorWriter) {
        return registration(
                new ConcurrencyLimitFilter(
                        properties.limits().maxConcurrentRequests(), errorWriter),
                Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
            ProxyProperties properties, ProxyErrorWriter errorWriter) {
        return registration(
                new RequestSizeLimitFilter(properties.limits().maxRequestBytes(), errorWriter),
                Ordered.HIGHEST_PRECEDENCE + 20);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> registration(
            T filter, int order) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        registration.addUrlPatterns("/v1/*");
        return registration;
    }
}
