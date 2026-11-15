package com.unisono.card.payment.domain.port.output;

import com.unisono.card.shared.domain.valueobject.Money;

import java.math.BigDecimal;

/**
 * Port for converting amounts between currencies (card-currency)
 */
public interface CurrencyConversionPort {

    /**
     * Convert {@code amount} to {@code targetCurrency} with the rate valid now.
     *
     * @throws com.unisono.card.payment.domain.exception.CurrencyConversionException if the currency is not
     *         supported or there is no rate for the pair
     */
    ConvertedAmount convert(Money amount, String targetCurrency);

    /**
     * @param converted amount in the target currency
     * @param rate      target units per source unit
     * @param rateId    card-currency exchange rate used
     */
    record ConvertedAmount(Money converted, BigDecimal rate, String rateId) {
    }
}
