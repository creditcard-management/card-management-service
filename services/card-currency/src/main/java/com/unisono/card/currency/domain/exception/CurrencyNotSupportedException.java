package com.unisono.card.currency.domain.exception;

import com.unisono.card.shared.domain.exception.BusinessRuleException;

/**
 * La moneda no está dada de alta o está desactivada.
 */
public class CurrencyNotSupportedException extends BusinessRuleException {

    public CurrencyNotSupportedException(String currencyCode) {
        super("Currency not supported: " + currencyCode);
    }
}
