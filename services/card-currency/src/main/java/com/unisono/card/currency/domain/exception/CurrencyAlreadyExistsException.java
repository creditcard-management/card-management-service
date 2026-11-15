package com.unisono.card.currency.domain.exception;

import com.unisono.card.shared.domain.exception.BusinessRuleException;

public class CurrencyAlreadyExistsException extends BusinessRuleException {

    public CurrencyAlreadyExistsException(String currencyCode) {
        super("Currency already registered: " + currencyCode);
    }
}
