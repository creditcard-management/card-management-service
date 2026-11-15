package com.unisono.card.currency.infrastructure.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Repositorios y entidades JPA de card-currency (currencies, exchange_rates). El DataSource lo arma Spring Boot.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.unisono.card.currency.infrastructure.adapter.output.persistence.repository")
@EntityScan(basePackages = "com.unisono.card.currency.infrastructure.adapter.output.persistence.entity")
public class CurrencyDatabaseConfig {
}
