package com.unisono.card.limits.domain.model;

import com.unisono.card.limits.domain.port.output.LimitPolicyPort;
import com.unisono.card.shared.domain.valueobject.Money;

import java.math.BigDecimal;

/**
 * Políticas de límites para los tests de dominio. Usan rangos amplios (no los de application.yml, que se
 * prueban en ConfiguredLimitPolicyAdapterTest) para que los montos de los tests sean chicos y legibles.
 * No pasan {@link LimitPolicy#checkCoherentWithDefaults}, a propósito: así se pueden probar los rechazos del
 * tope de adelantos con límites de compras bajos.
 */
public final class TestLimitPolicies {

    private TestLimitPolicies() {
    }

    /** ARS: compras 1.000–10.000.000, adelantos 500–5.000.000, diario 100.000, mensual 500.000 */
    public static LimitPolicy ars() {
        return of("ARS");
    }

    /** Las mismas reglas que {@link #ars()}, en otra moneda */
    public static LimitPolicy of(String currency) {
        return new LimitPolicy(currency,
                money(1_000, currency), money(10_000_000, currency),
                money(500, currency), money(5_000_000, currency),
                money(100_000, currency), money(500_000, currency));
    }

    /** Puerto con {@link #of(String)} para cualquier moneda */
    public static LimitPolicyPort port() {
        return TestLimitPolicies::of;
    }

    private static Money money(long amount, String currency) {
        return Money.of(BigDecimal.valueOf(amount), currency);
    }
}
