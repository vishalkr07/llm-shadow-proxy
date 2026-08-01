package com.example.shadowproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ShadowProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShadowProxyApplication.class, args);
    }
}
