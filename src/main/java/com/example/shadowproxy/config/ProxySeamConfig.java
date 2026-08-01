package com.example.shadowproxy.config;

import com.example.shadowproxy.metrics.MetricsRecorder;
import com.example.shadowproxy.shadow.ShadowDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProxySeamConfig {

    @Bean
    @ConditionalOnMissingBean
    MetricsRecorder metricsRecorder() {
        return () -> {};
    }

    @Bean
    @ConditionalOnMissingBean
    ShadowDispatcher shadowDispatcher() {
        return job -> {};
    }
}
