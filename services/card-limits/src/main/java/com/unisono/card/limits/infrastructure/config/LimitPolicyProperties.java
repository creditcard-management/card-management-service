package com.unisono.card.limits.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * {@code card.limits.policies.<MONEDA>.*}: rangos y valores por defecto de los límites de crédito por moneda.
 * Solo se pueden asignar límites en las monedas configuradas.
 */
@ConfigurationProperties(prefix = "card.limits")
public record LimitPolicyProperties(Map<String, Policy> policies) {

    public LimitPolicyProperties {
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }

    public record Policy(
            BigDecimal minPurchaseLimit,
            BigDecimal maxPurchaseLimit,
            BigDecimal minCashAdvanceLimit,
            BigDecimal maxCashAdvanceLimit,
            BigDecimal defaultDailyLimit,
            BigDecimal defaultMonthlyLimit
    ) {
    }
}
