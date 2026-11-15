package com.unisono.card.currency.domain.port.output;

import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExchangeRateRepository {

    ExchangeRate save(ExchangeRate rate);

    /**
     * Cotización abierta (sin {@code validTo}) del par. Bloquea el par hasta el fin de la transacción (aunque no
     * tenga cotización abierta) para que dos publicaciones simultáneas no se pisen.
     */
    Optional<ExchangeRate> findOpenForUpdate(CurrencyPair pair);

    /**
     * Cotización del par vigente en {@code at}.
     */
    Optional<ExchangeRate> findValidAt(CurrencyPair pair, Instant at);

    /**
     * Historial del par, de la más nueva a la más vieja.
     */
    List<ExchangeRate> findHistory(CurrencyPair pair, int limit);
}
