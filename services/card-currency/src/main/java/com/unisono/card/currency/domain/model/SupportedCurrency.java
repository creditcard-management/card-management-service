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
     * Decimales que soporta el sistema: {@code Money} (card-shared) trabaja siempre con escala 2.
     */
    public static final int SUPPORTED_FRACTION_DIGITS = 2;

    /**
     * Alta de una moneda nueva: queda activa. Solo se aceptan monedas de 2 decimales (ISO 4217), porque
     * {@code Money} redondea todo a 2: una moneda sin decimales (JPY) o con 3 (BHD) quedaría mal representada.
     */
    public static SupportedCurrency register(String code, String name, Instant now) {
        Currency currency = CurrencyPair.currency(code);
        if (currency.getDefaultFractionDigits() != SUPPORTED_FRACTION_DIGITS) {
            throw new BusinessRuleException(String.format(
                    "Currency %s uses %d decimals; only currencies with %d decimals are supported",
                    currency.getCurrencyCode(), currency.getDefaultFractionDigits(), SUPPORTED_FRACTION_DIGITS));
        }
        return new SupportedCurrency(currency, name, true, now, now);
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
     * Desactiva la moneda. Una moneda con límites de crédito no se puede desactivar: los pagos en otra moneda de
     * esas tarjetas necesitan convertir hacia ella.
     *
     * @param hasCreditLimits si hay límites de crédito (no eliminados) en esta moneda
     */
    public void deactivate(boolean hasCreditLimits, Instant now) {
        if (hasCreditLimits) {
            throw new BusinessRuleException(String.format(
                    "Currency %s cannot be deactivated: there are credit limits in it", getCode()));
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
