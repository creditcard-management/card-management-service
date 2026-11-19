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
     * tenga cotización abierta) para que dos publicaciones simultáneas no se pisen. El lock es el mismo para el par
     * y su inverso (USD/ARS y ARS/USD).
     */
    Optional<ExchangeRate> findOpenForUpdate(CurrencyPair pair);

    /**
     * Cotización por id, con el mismo lock de par que {@link #findOpenForUpdate}.
     */
    Optional<ExchangeRate> findByIdForUpdate(String rateId);

    /**
     * Cotización del par cuya vigencia termina en {@code validTo} (la anterior a la que empieza ahí).
     */
    Optional<ExchangeRate> findEndingAt(CurrencyPair pair, Instant validTo);

    /**
     * ¿El par tiene alguna cotización (abierta o cerrada)?
     */
    boolean existsForPair(CurrencyPair pair);

    void delete(ExchangeRate rate);

    /**
     * Cotización del par vigente en {@code at}.
     */
    Optional<ExchangeRate> findValidAt(CurrencyPair pair, Instant at);

    /**
     * Las cotizaciones de todos los pares vigentes en {@code at} (una por par).
     */
    List<ExchangeRate> findAllValidAt(Instant at);

    /**
     * Historial del par, de la más nueva a la más vieja.
     */
    List<ExchangeRate> findHistory(CurrencyPair pair, int limit);
}
