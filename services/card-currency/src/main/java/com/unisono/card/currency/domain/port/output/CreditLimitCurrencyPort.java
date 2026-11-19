package com.unisono.card.currency.domain.port.output;

import java.util.Set;

/**
 * Monedas en las que hay límites de crédito (card-limits). Los pagos en otra moneda de esas tarjetas convierten
 * hacia la moneda del límite, así que esas monedas tienen que seguir activas.
 */
public interface CreditLimitCurrencyPort {

    /**
     * Si hay algún límite de crédito (no eliminado) en la moneda
     */
    boolean hasCreditLimitsIn(String currencyCode);

    /**
     * Las monedas de todos los límites de crédito no eliminados
     */
    Set<String> currenciesWithCreditLimits();
}
