package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Value Object que representa el límite de adelantos en efectivo
 * Generalmente es un porcentaje del límite de compras (ej: 30%).
 * Cero significa que la tarjeta no tiene adelantos habilitados; si es mayor a cero, debe estar dentro del
 * rango de la política de su moneda ({@link LimitPolicy}), que se valida al asignar o modificar el tope.
 */
@Getter
@EqualsAndHashCode
public class CashAdvanceLimit implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Money amount;

    private CashAdvanceLimit(Money amount) {
        this.amount = amount;
    }

    /**
     * Crea un tope nuevo validando el rango de la política de su moneda
     */
    public static CashAdvanceLimit of(Money amount, LimitPolicy policy) {
        validate(amount);
        policy.checkCashAdvanceLimit(amount);
        return new CashAdvanceLimit(amount);
    }

    /**
     * Reconstruye desde persistencia, sin validar el rango: el tope pudo asignarse con reglas anteriores
     */
    public static CashAdvanceLimit reconstituteFromPersistence(Money amount) {
        validate(amount);
        return new CashAdvanceLimit(amount);
    }

    /**
     * Sin adelantos, en la moneda indicada
     */
    public static CashAdvanceLimit zero(String currencyCode) {
        return new CashAdvanceLimit(Money.zero(currencyCode));
    }

    /**
     * Calcula el límite de adelanto como porcentaje del límite de compras
     * Típicamente 30% del límite de compras
     */
    public static CashAdvanceLimit fromPurchaseLimit(PurchaseLimit purchaseLimit, double percentage,
                                                     LimitPolicy policy) {
        if (percentage < 0 || percentage > 1) {
            throw new ValidationException("El porcentaje debe estar entre 0 y 1");
        }
        if (percentage == 0) {
            return zero(purchaseLimit.getAmount().getCurrencyCode()); // sin adelantos
        }

        Money purchase = purchaseLimit.getAmount();
        BigDecimal fraction = BigDecimal.valueOf(percentage);
        Money cashAdvanceAmount = purchase.multiply(fraction);

        // El porcentaje por defecto siempre da un tope dentro del rango (lo controla la política al arrancar);
        // uno explícito puede no darlo: se rechaza diciendo qué porcentajes sirven para este límite
        if (cashAdvanceAmount.isLessThan(policy.minCashAdvanceLimit())) {
            BigDecimal minFraction = policy.minCashAdvanceLimit().getAmount()
                    .divide(purchase.getAmount(), 4, RoundingMode.CEILING);
            throw new ValidationException(String.format(
                    "El %s%% de %s da un tope de adelantos de %s, menor al mínimo de %s. "
                            + "Usar al menos el %s%% o cero para no habilitar adelantos",
                    LimitPolicy.percent(fraction), purchase, cashAdvanceAmount, policy.minCashAdvanceLimit(),
                    LimitPolicy.percent(minFraction)));
        }
        if (cashAdvanceAmount.isGreaterThan(policy.maxCashAdvanceLimit())) {
            BigDecimal maxFraction = policy.maxCashAdvanceLimit().getAmount()
                    .divide(purchase.getAmount(), 4, RoundingMode.FLOOR);
            throw new ValidationException(String.format(
                    "El %s%% de %s da un tope de adelantos de %s, mayor al máximo de %s. Usar como mucho el %s%%",
                    LimitPolicy.percent(fraction), purchase, cashAdvanceAmount, policy.maxCashAdvanceLimit(),
                    LimitPolicy.percent(maxFraction)));
        }

        return of(cashAdvanceAmount, policy);
    }

    private static void validate(Money amount) {
        if (amount == null) {
            throw new ValidationException("El límite de adelantos no puede ser nulo");
        }

        if (amount.isNegative()) {
            throw new ValidationException("El límite de adelantos no puede ser negativo");
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
