package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.io.Serializable;

/**
 * Value Object que representa el límite de compras.
 * El rango depende de la moneda ({@link LimitPolicy}) y se valida al asignar o modificar el límite.
 */
@Getter
@EqualsAndHashCode
public class PurchaseLimit implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Money amount;

    private PurchaseLimit(Money amount) {
        this.amount = amount;
    }

    /**
     * Crea un límite nuevo validando el rango de la política de su moneda
     */
    public static PurchaseLimit of(Money amount, LimitPolicy policy) {
        requireNonNull(amount);
        policy.checkPurchaseLimit(amount);
        return new PurchaseLimit(amount);
    }

    /**
     * Reconstruye desde persistencia, sin validar el rango: el límite pudo asignarse con reglas anteriores
     */
    public static PurchaseLimit reconstituteFromPersistence(Money amount) {
        requireNonNull(amount);
        return new PurchaseLimit(amount);
    }

    private static void requireNonNull(Money amount) {
        if (amount == null) {
            throw new ValidationException("El límite de compras no puede ser nulo");
        }
    }

    /**
     * Aumenta el límite (el resultado debe quedar dentro del rango de la política)
     */
    public PurchaseLimit increase(Money additionalAmount, LimitPolicy policy) {
        return of(this.amount.add(additionalAmount), policy);
    }

    /**
     * Disminuye el límite (el resultado debe quedar dentro del rango de la política)
     */
    public PurchaseLimit decrease(Money reductionAmount, LimitPolicy policy) {
        return of(this.amount.subtract(reductionAmount), policy);
    }

    @Override
    public String toString() {
        return amount.toString();
    }
}
