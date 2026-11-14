package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.io.Serializable;

/**
 * Value Object que representa el límite de adelantos en efectivo
 * Generalmente es un porcentaje del límite de compras (ej: 30%).
 * Cero significa que la tarjeta no tiene adelantos habilitados; si es mayor a cero, debe estar entre
 * {@link #MIN_LIMIT} y {@link #MAX_LIMIT}.
 */
@Getter
@EqualsAndHashCode
public class CashAdvanceLimit implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Money MIN_LIMIT = Money.ofARS(500);      // $500 mínimo
    private static final Money MAX_LIMIT = Money.ofARS(5_000_000); // $5M máximo

    private final Money amount;

    private CashAdvanceLimit(Money amount) {
        this.amount = amount;
    }

    public static CashAdvanceLimit of(Money amount) {
        validate(amount);
        return new CashAdvanceLimit(amount);
    }

    public static CashAdvanceLimit zero() {
        return of(Money.zero());
    }

    /**
     * Calcula el límite de adelanto como porcentaje del límite de compras
     * Típicamente 30% del límite de compras
     */
    public static CashAdvanceLimit fromPurchaseLimit(PurchaseLimit purchaseLimit, double percentage) {
        if (percentage < 0 || percentage > 1) {
            throw new ValidationException("El porcentaje debe estar entre 0 y 1");
        }
        if (percentage == 0) {
            return zero(); // sin adelantos
        }

        Money cashAdvanceAmount = purchaseLimit.getAmount()
                .multiply(java.math.BigDecimal.valueOf(percentage));

        return of(cashAdvanceAmount);
    }

    private static void validate(Money amount) {
        if (amount == null) {
            throw new ValidationException("El límite de adelantos no puede ser nulo");
        }

        if (amount.isNegative()) {
            throw new ValidationException("El límite de adelantos no puede ser negativo");
        }

        // Cero = sin adelantos; si hay adelantos, el tope tiene un mínimo
        if (!amount.isZero() && amount.isLessThan(MIN_LIMIT)) {
            throw new ValidationException(
                    String.format("El límite de adelantos debe ser al menos %s (o cero para no habilitar adelantos)",
                            MIN_LIMIT)
            );
        }

        if (amount.isGreaterThan(MAX_LIMIT)) {
            throw new ValidationException(
                    String.format("El límite de adelantos no puede exceder %s", MAX_LIMIT)
            );
        }
    }

    /**
     * La tarjeta tiene adelantos habilitados (tope mayor a cero)
     */
    public boolean isEnabled() {
        return this.amount.isPositive();
    }

    /**
     * Verifica si un adelanto está dentro del límite
     */
    public boolean allows(Money advanceAmount) {
        return isEnabled() && !advanceAmount.isGreaterThan(this.amount);
    }

    @Override
    public String toString() {
        return amount.toString();
    }
}
