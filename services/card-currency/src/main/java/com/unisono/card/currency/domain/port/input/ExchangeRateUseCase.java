package com.unisono.card.currency.domain.port.input;

import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Use Case: publicar y consultar cotizaciones.
 */
public interface ExchangeRateUseCase {

    /**
     * Publica una cotización para el par. {@code effectiveFrom} nulo = ahora. Cierra la cotización abierta del par.
     */
    ExchangeRate publish(PublishRateCommand command);

    /**
     * Cotización del par vigente en {@code at} (nulo = ahora). Solo el par pedido, sin invertir.
     */
    ExchangeRate getRate(CurrencyPair pair, Instant at);

    List<ExchangeRate> getHistory(CurrencyPair pair, int limit);

    record PublishRateCommand(CurrencyPair pair, BigDecimal rate, Instant effectiveFrom) {}
}
