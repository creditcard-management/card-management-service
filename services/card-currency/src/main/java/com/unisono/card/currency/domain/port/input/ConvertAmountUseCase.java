package com.unisono.card.currency.domain.port.input;

import com.unisono.card.currency.domain.model.Conversion;
import com.unisono.card.shared.domain.valueobject.Money;

import java.time.Instant;

/**
 * Use Case: convertir un monto a otra moneda con la cotización vigente.
 */
public interface ConvertAmountUseCase {

    /**
     * Convierte {@code amount} a {@code targetCurrency} con la cotización vigente en {@code at} (nulo = ahora).
     * Usa la cotización directa del par y, si no hay, la inversa.
     */
    Conversion convert(Money amount, String targetCurrency, Instant at);
}
