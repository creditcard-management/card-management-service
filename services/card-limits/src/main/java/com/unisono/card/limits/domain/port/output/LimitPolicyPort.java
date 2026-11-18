package com.unisono.card.limits.domain.port.output;

import com.unisono.card.limits.domain.model.LimitPolicy;

/**
 * Políticas de límites de crédito por moneda (rangos y valores por defecto)
 */
public interface LimitPolicyPort {

    /**
     * La política de la moneda
     *
     * @throws com.unisono.card.shared.domain.exception.BusinessRuleException si la moneda no tiene política
     *                                                                      configurada (no se pueden asignar
     *                                                                      límites en esa moneda)
     */
    LimitPolicy forCurrency(String currencyCode);
}
