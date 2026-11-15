package com.unisono.card.payment.domain.model;

import com.unisono.card.shared.domain.exception.InvalidValueObjectException;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Value Object representing a transaction amount with currency
 */
@Getter
@EqualsAndHashCode
public class TransactionAmount implements Serializable {

    private final Money amount;
    private final Money localAmount; // Monto en moneda local si aplica
    private final BigDecimal exchangeRate; // Tasa de cambio si hay conversión: unidades locales por unidad original
    private final String exchangeRateId;   // Cotización de card-currency usada (nula en transacciones viejas)

    private TransactionAmount(Money amount, Money localAmount, BigDecimal exchangeRate, String exchangeRateId) {
        if (amount == null) {
            throw new InvalidValueObjectException("Transaction amount cannot be null");
        }
        if (amount.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidValueObjectException("Transaction amount must be positive");
        }
        this.amount = amount;
        this.localAmount = localAmount;
        this.exchangeRate = exchangeRate;
        this.exchangeRateId = exchangeRateId;
    }

    public static TransactionAmount of(Money amount) {
        return new TransactionAmount(amount, null, null, null);
    }

    public static TransactionAmount withConversion(Money amount, Money localAmount, BigDecimal exchangeRate) {
        return withConversion(amount, localAmount, exchangeRate, null);
    }

    /**
     * Monto original convertido a la moneda local (la del límite de la tarjeta) con una cotización de card-currency.
     */
    public static TransactionAmount withConversion(Money amount, Money localAmount, BigDecimal exchangeRate,
                                                   String exchangeRateId) {
        if (localAmount == null || exchangeRate == null) {
            throw new InvalidValueObjectException("Local amount and exchange rate are required for conversion");
        }
        if (exchangeRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidValueObjectException("Exchange rate must be positive");
        }
        return new TransactionAmount(amount, localAmount, exchangeRate, exchangeRateId);
    }

    public boolean hasCurrencyConversion() {
        return localAmount != null && exchangeRate != null;
    }

    public Money getEffectiveAmount() {
        return hasCurrencyConversion() ? localAmount : amount;
    }

    @Override
    public String toString() {
        if (hasCurrencyConversion()) {
            return String.format("%s (local: %s, rate: %s)", amount, localAmount, exchangeRate);
        }
        return amount.toString();
    }
}
