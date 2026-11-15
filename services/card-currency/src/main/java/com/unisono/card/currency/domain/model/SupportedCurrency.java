package com.unisono.card.currency.domain.model;

import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.InvalidValueObjectException;
import lombok.Getter;

import java.time.Instant;
import java.util.Currency;

/**
 * Moneda que el sistema acepta para operar. Solo las monedas activas pueden cotizarse y convertirse.
 */
@Getter
public class SupportedCurrency {

    private final Currency currency;
    private final String name;
    private boolean active;
    private final Instant createdAt;
    private Instant updatedAt;

    private SupportedCurrency(Currency currency, String name, boolean active, Instant createdAt, Instant updatedAt) {
        if (currency == null) {
            throw new InvalidValueObjectException("Currency is required");
        }
        if (name == null || name.isBlank()) {
            throw new InvalidValueObjectException("Currency name is required");
        }
        if (name.length() > 100) {
            throw new InvalidValueObjectException("Currency name must be at most 100 characters");
        }
        this.currency = currency;
        this.name = name.trim();
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Alta de una moneda nueva: queda activa.
     */
    public static SupportedCurrency register(String code, String name, Instant now) {
        return new SupportedCurrency(CurrencyPair.currency(code), name, true, now, now);
    }

    /**
     * Reconstrucción desde persistencia.
     */
    public static SupportedCurrency restore(String code, String name, boolean active, Instant createdAt, Instant updatedAt) {
        return new SupportedCurrency(CurrencyPair.currency(code), name, active, createdAt, updatedAt);
    }

    public void activate(Instant now) {
        if (!active) {
            active = true;
            updatedAt = now;
        }
    }

    /**
     * Desactiva la moneda. La moneda del sistema (en la que están los límites) no se puede desactivar.
     */
    public void deactivate(Currency systemCurrency, Instant now) {
        if (currency.equals(systemCurrency)) {
            throw new BusinessRuleException("The system currency " + getCode() + " cannot be deactivated");
        }
        if (active) {
            active = false;
            updatedAt = now;
        }
    }

    public String getCode() {
        return currency.getCurrencyCode();
    }

    public int getFractionDigits() {
        return currency.getDefaultFractionDigits();
    }
}
