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
     * Cancela una cotización programada (que todavía no empezó) y extiende la anterior del par hasta donde llegaba
     * la cancelada. Devuelve la cotización cancelada.
     */
    ExchangeRate cancelScheduled(String rateId);

    /**
     * Cotización del par vigente en {@code at} (nulo = ahora). Solo el par pedido, sin invertir. Las dos monedas
     * tienen que estar activas.
     */
    ExchangeRate getRate(CurrencyPair pair, Instant at);

    List<ExchangeRate> getHistory(CurrencyPair pair, int limit);

    record PublishRateCommand(CurrencyPair pair, BigDecimal rate, Instant effectiveFrom) {}
}
