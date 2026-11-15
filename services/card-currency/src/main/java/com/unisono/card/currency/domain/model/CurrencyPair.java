package com.unisono.card.currency.domain.model;

import com.unisono.card.shared.domain.exception.InvalidValueObjectException;

import java.util.Currency;

/**
 * Par de monedas de una cotización: 1 unidad de {@code base} vale {@code rate} unidades de {@code quote}.
 * Las dos monedas son códigos ISO 4217 válidos y distintos entre sí.
 */
public record CurrencyPair(Currency base, Currency quote) {

    public CurrencyPair {
        if (base == null || quote == null) {
            throw new InvalidValueObjectException("Both currencies of the pair are required");
        }
        if (base.equals(quote)) {
            throw new InvalidValueObjectException(
                    "A currency pair needs two different currencies: " + base.getCurrencyCode());
        }
    }

    public static CurrencyPair of(String baseCode, String quoteCode) {
        return new CurrencyPair(currency(baseCode), currency(quoteCode));
    }

    public CurrencyPair inverse() {
        return new CurrencyPair(quote, base);
    }

    public String baseCode() {
        return base.getCurrencyCode();
    }

    public String quoteCode() {
        return quote.getCurrencyCode();
    }

    /**
     * Valida y normaliza un código ISO 4217 (acepta minúsculas).
     */
    public static Currency currency(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidValueObjectException("Currency code cannot be null or blank");
        }
        try {
            return Currency.getInstance(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidValueObjectException("Invalid currency code: " + code, e);
        }
    }

    @Override
    public String toString() {
        return baseCode() + "/" + quoteCode();
    }
}
