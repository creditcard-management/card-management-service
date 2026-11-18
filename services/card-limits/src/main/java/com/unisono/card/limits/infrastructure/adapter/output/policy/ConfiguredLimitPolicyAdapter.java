package com.unisono.card.limits.infrastructure.adapter.output.policy;

import com.unisono.card.limits.domain.model.LimitPolicy;
import com.unisono.card.limits.domain.port.input.AssignCreditLimitUseCase;
import com.unisono.card.limits.domain.port.output.LimitPolicyPort;
import com.unisono.card.limits.infrastructure.config.LimitPolicyProperties;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Políticas de límites leídas de {@code card.limits.policies}. Se arman al arrancar: si falta una política, un
 * valor o alguno es inconsistente (mínimo mayor al máximo, diario mayor al mensual...), el servicio no arranca.
 */
@Slf4j
@Component
@EnableConfigurationProperties(LimitPolicyProperties.class)
public class ConfiguredLimitPolicyAdapter implements LimitPolicyPort {

    private final Map<String, LimitPolicy> policies;

    public ConfiguredLimitPolicyAdapter(LimitPolicyProperties properties) {
        if (properties.policies().isEmpty()) {
            throw new IllegalStateException(
                    "card.limits.policies no tiene ninguna moneda: no se podrían asignar límites de crédito");
        }
        Map<String, LimitPolicy> built = new HashMap<>();
        properties.policies().forEach((currency, policy) -> built.put(currency, toPolicy(currency, policy)));
        this.policies = Map.copyOf(built);
        log.info("Credit limit policies loaded for currencies {}", this.policies.keySet());
    }

    @Override
    public LimitPolicy forCurrency(String currencyCode) {
        LimitPolicy policy = policies.get(currencyCode);
        if (policy == null) {
            throw new BusinessRuleException(String.format(
                    "No se pueden operar límites de crédito en %s: la moneda no tiene política configurada",
                    currencyCode));
        }
        return policy;
    }

    private static LimitPolicy toPolicy(String currency, LimitPolicyProperties.Policy policy) {
        if (policy == null) {
            throw new IllegalStateException("card.limits.policies." + currency + " está vacía");
        }
        try {
            LimitPolicy limitPolicy = new LimitPolicy(
                    currency,
                    money(policy.minPurchaseLimit(), currency, "min-purchase-limit"),
                    money(policy.maxPurchaseLimit(), currency, "max-purchase-limit"),
                    money(policy.minCashAdvanceLimit(), currency, "min-cash-advance-limit"),
                    money(policy.maxCashAdvanceLimit(), currency, "max-cash-advance-limit"),
                    money(policy.defaultDailyLimit(), currency, "default-daily-limit"),
                    money(policy.defaultMonthlyLimit(), currency, "default-monthly-limit")
            );
            // Los defaults tienen que funcionar con cualquier límite del rango (asignar sin porcentaje explícito
            // nunca falla por el tope de adelantos, y el cupo mensual por defecto no supera al límite de compras)
            limitPolicy.checkCoherentWithDefaults(AssignCreditLimitUseCase.DEFAULT_CASH_ADVANCE_PERCENTAGE);
            return limitPolicy;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "card.limits.policies." + currency + " es inválida: " + e.getMessage(), e);
        }
    }

    private static Money money(BigDecimal amount, String currency, String property) {
        if (amount == null) {
            throw new IllegalStateException("falta " + property);
        }
        return Money.of(amount, currency);
    }
}
