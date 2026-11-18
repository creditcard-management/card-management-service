package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.io.Serializable;

/**
 * Value Object: saldo a favor de la tarjeta (lo que el cliente pagó o le devolvieron de más).
 * Nunca negativo, y solo es mayor a cero cuando lo consumido es cero: el saldo cancela la deuda antes de acumularse.
 * Se suma al crédito disponible y lo usan primero los consumos siguientes.
 */
@Getter
@EqualsAndHashCode
public class CreditBalance implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Money amount;

    private CreditBalance(Money amount) {
        this.amount = amount;
    }

    public static CreditBalance of(Money amount) {
        if (amount == null) {
            throw new ValidationException("El saldo a favor no puede ser nulo");
        }
        // La base lo rechaza también (chk_credit_balance)
        if (amount.isNegative()) {
            throw new ValidationException(String.format("El saldo a favor no puede ser negativo: %s", amount));
        }
        return new CreditBalance(amount);
    }

    public static CreditBalance zero(String currencyCode) {
        return of(Money.zero(currencyCode));
    }

    public boolean isPositive() {
        return amount.isPositive();
    }

    @Override
    public String toString() {
        return amount.toString();
    }
}
