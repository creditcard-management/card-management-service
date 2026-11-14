package com.unisono.card.limits.infrastructure.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Repositorios y entidades JPA de card-limits: las propias (credit_limits, credit_limit_history,
 * credit_operations, credit_limit_outbox_events) y la lectura de cards (CardJpaEntity).
 * <p>
 * El DataSource (HikariCP) y el manejo de transacciones los arma Spring Boot a partir de
 * {@code spring.datasource.*} y {@code spring.datasource.hikari.*}.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = {
                "com.unisono.card.limits.infrastructure.adapter.output.persistence.repository",
                "com.unisono.card.limits.infrastructure.adapter.output.card"
        }
)
@EntityScan(
        basePackages = {
                "com.unisono.card.limits.infrastructure.adapter.output.persistence.entity",
                "com.unisono.card.limits.infrastructure.adapter.output.card"
        }
)
public class CreditLimitDatabaseConfig {
}
