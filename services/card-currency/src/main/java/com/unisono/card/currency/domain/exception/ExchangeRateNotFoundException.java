package com.unisono.card.currency.domain.exception;

import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.shared.domain.exception.NotFoundException;

import java.time.Instant;

/**
 * No hay cotización vigente para el par (ni para el par inverso) en el instante pedido.
 */
public class ExchangeRateNotFoundException extends NotFoundException {

    public ExchangeRateNotFoundException(CurrencyPair pair, Instant at) {
        super("No exchange rate for " + pair + " at " + at);
    }
}
