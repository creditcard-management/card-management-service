package com.unisono.card.currency.domain.model;

import com.unisono.card.shared.domain.valueobject.Money;

import java.math.BigDecimal;

/**
 * Resultado de convertir un monto: el monto original, el convertido, la tasa aplicada (unidades de destino por
 * unidad de origen) y la cotización usada. {@code exchangeRate} es nulo si las monedas son iguales, y
 * {@code inverse} indica que se usó la cotización del par contrario (destino/origen).
 */
public record Conversion(
        Money source,
        Money target,
        BigDecimal appliedRate,
        ExchangeRate exchangeRate,
        boolean inverse
) {

    public boolean isIdentity() {
        return exchangeRate == null;
    }
}
