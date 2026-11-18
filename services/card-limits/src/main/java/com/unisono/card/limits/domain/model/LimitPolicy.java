package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reglas de los límites de crédito en una moneda: rango del límite de compras, rango del tope de adelantos
 * (cero siempre está permitido: sin adelantos) y los límites diario y mensual que se asignan por defecto.
 * <p>
 * Los rangos se aplican al asignar y al modificar un límite, no al reconstruirlo desde la base: un límite
 * asignado con reglas anteriores sigue cargando, consumiendo y liberando aunque quede fuera del rango actual.
 */
public record LimitPolicy(
        String currencyCode,
        Money minPurchaseLimit,
        Money maxPurchaseLimit,
        Money minCashAdvanceLimit,
        Money maxCashAdvanceLimit,
        Money defaultDailyLimit,
        Money defaultMonthlyLimit
) {

    public LimitPolicy {
        Objects.requireNonNull(currencyCode, "currencyCode");
        requireSameCurrency(currencyCode, minPurchaseLimit, "minPurchaseLimit");
        requireSameCurrency(currencyCode, maxPurchaseLimit, "maxPurchaseLimit");
        requireSameCurrency(currencyCode, minCashAdvanceLimit, "minCashAdvanceLimit");
        requireSameCurrency(currencyCode, maxCashAdvanceLimit, "maxCashAdvanceLimit");
        requireSameCurrency(currencyCode, defaultDailyLimit, "defaultDailyLimit");
        requireSameCurrency(currencyCode, defaultMonthlyLimit, "defaultMonthlyLimit");

        requireConsistent(minPurchaseLimit.isPositive(), "el mínimo de compras debe ser mayor a cero");
        requireConsistent(!minPurchaseLimit.isGreaterThan(maxPurchaseLimit),
                "el mínimo de compras no puede superar al máximo");
        requireConsistent(minCashAdvanceLimit.isPositive(), "el mínimo de adelantos debe ser mayor a cero");
        requireConsistent(!minCashAdvanceLimit.isGreaterThan(maxCashAdvanceLimit),
                "el mínimo de adelantos no puede superar al máximo");
        requireConsistent(defaultDailyLimit.isPositive(), "el límite diario por defecto debe ser mayor a cero");
        requireConsistent(!defaultDailyLimit.isGreaterThan(defaultMonthlyLimit),
                "el límite diario por defecto no puede superar al mensual");
    }

    /**
     * Valida que los valores por defecto funcionen con cualquier límite de compras del rango:
     * <ul>
     *   <li>con el porcentaje de adelantos por defecto, el mínimo de compras da un tope de adelantos ≥ al mínimo y
     *   el máximo de compras uno ≤ al máximo (si no, una asignación sin porcentaje explícito se rechazaría);</li>
     *   <li>el mensual por defecto (y por lo tanto el diario) no supera al mínimo de compras (si no, un límite
     *   nuevo podría tener un cupo mensual mayor a su límite de compras).</li>
     * </ul>
     * No se valida en el constructor para poder armar políticas parciales en tests; la aplica la configuración
     * al arrancar.
     *
     * @throws IllegalArgumentException con todas las inconsistencias encontradas
     */
    public void checkCoherentWithDefaults(double defaultCashAdvancePercentage) {
        List<String> problems = new ArrayList<>();
        if (defaultCashAdvancePercentage > 0) {
            BigDecimal percentage = BigDecimal.valueOf(defaultCashAdvancePercentage);
            Money fromMin = minPurchaseLimit.multiply(percentage);
            Money fromMax = maxPurchaseLimit.multiply(percentage);
            if (fromMin.isLessThan(minCashAdvanceLimit)) {
                problems.add(String.format(
                        "con el %s%% por defecto, el mínimo de compras (%s) da un tope de adelantos de %s, menor al mínimo de adelantos (%s)",
                        percent(percentage), minPurchaseLimit, fromMin, minCashAdvanceLimit));
            }
            if (fromMax.isGreaterThan(maxCashAdvanceLimit)) {
                problems.add(String.format(
                        "con el %s%% por defecto, el máximo de compras (%s) da un tope de adelantos de %s, mayor al máximo de adelantos (%s)",
                        percent(percentage), maxPurchaseLimit, fromMax, maxCashAdvanceLimit));
            }
        }
        if (defaultMonthlyLimit.isGreaterThan(minPurchaseLimit)) {
            problems.add(String.format(
                    "el límite mensual por defecto (%s) supera al mínimo de compras (%s)",
                    defaultMonthlyLimit, minPurchaseLimit));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "Política de límites %s: %s", currencyCode, String.join("; ", problems)));
        }
    }

    /**
     * Un porcentaje como fracción (0,30) expresado para mensajes (30)
     */
    static String percent(BigDecimal fraction) {
        return fraction.movePointRight(2).stripTrailingZeros().toPlainString();
    }

    /**
     * Valida que un límite de compras esté dentro del rango de la política
     */
    public void checkPurchaseLimit(Money amount) {
        checkCurrency(amount);
        if (amount.isLessThan(minPurchaseLimit)) {
            throw new ValidationException(
                    String.format("El límite de compras debe ser al menos %s", minPurchaseLimit));
        }
        if (amount.isGreaterThan(maxPurchaseLimit)) {
            throw new ValidationException(
                    String.format("El límite de compras no puede exceder %s", maxPurchaseLimit));
        }
    }

    /**
     * Valida que un tope de adelantos esté dentro del rango de la política. Cero (sin adelantos) siempre vale.
     */
    public void checkCashAdvanceLimit(Money amount) {
        checkCurrency(amount);
        if (amount.isZero()) {
            return;
        }
        if (amount.isLessThan(minCashAdvanceLimit)) {
            throw new ValidationException(String.format(
                    "El límite de adelantos debe ser al menos %s (o cero para no habilitar adelantos)",
                    minCashAdvanceLimit));
        }
        if (amount.isGreaterThan(maxCashAdvanceLimit)) {
            throw new ValidationException(
                    String.format("El límite de adelantos no puede exceder %s", maxCashAdvanceLimit));
        }
    }

    private void checkCurrency(Money amount) {
        if (amount == null) {
            throw new ValidationException("El monto del límite es obligatorio");
        }
        if (!currencyCode.equals(amount.getCurrencyCode())) {
            throw new ValidationException(String.format(
                    "El monto está en %s pero la política de límites es de %s",
                    amount.getCurrencyCode(), currencyCode));
        }
    }

    private static void requireSameCurrency(String currencyCode, Money amount, String field) {
        if (amount == null) {
            throw new IllegalArgumentException(
                    String.format("Política de límites %s: falta %s", currencyCode, field));
        }
        if (!currencyCode.equals(amount.getCurrencyCode())) {
            throw new IllegalArgumentException(String.format(
                    "Política de límites %s: %s está en %s", currencyCode, field, amount.getCurrencyCode()));
        }
    }

    private void requireConsistent(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(String.format("Política de límites %s: %s", currencyCode, message));
        }
    }
}
