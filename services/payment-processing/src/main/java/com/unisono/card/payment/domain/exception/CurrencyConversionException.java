package com.unisono.card.payment.domain.exception;

/**
 * The amount cannot be converted to the currency of the card's credit limit: the currency is not supported
 * by card-currency or there is no exchange rate for the pair.
 */
public class CurrencyConversionException extends PaymentException {

    private final String sourceCurrency;
    private final String targetCurrency;

    public CurrencyConversionException(String sourceCurrency, String targetCurrency, String detail) {
        super(String.format("Cannot convert %s to %s: %s", sourceCurrency, targetCurrency, detail));
        this.sourceCurrency = sourceCurrency;
        this.targetCurrency = targetCurrency;
    }

    public String getSourceCurrency() {
        return sourceCurrency;
    }

    public String getTargetCurrency() {
        return targetCurrency;
    }
}
