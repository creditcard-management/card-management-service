package com.unisono.card.lifecycle.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Cliente HTTP hacia card-data. Con timeouts, un card-data colgado se traduce en 502
 * (CardDataIntegrationException) en lugar de dejar el hilo de la request bloqueado.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            @Value("${card-data.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${card-data.read-timeout-ms:5000}") long readTimeoutMs) {
        return builder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
