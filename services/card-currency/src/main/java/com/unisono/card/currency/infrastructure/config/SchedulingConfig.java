package com.unisono.card.currency.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tareas programadas: el control de antigüedad de las cotizaciones (ExchangeRateFreshnessMonitor).
 * Se desactiva con {@code card.currency.scheduling.enabled=false} (por ejemplo, en tests).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "card.currency.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
