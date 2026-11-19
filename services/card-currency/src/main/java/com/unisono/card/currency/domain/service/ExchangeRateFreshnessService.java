package com.unisono.card.currency.domain.service;

import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.model.SupportedCurrency;
import com.unisono.card.currency.domain.port.output.ExchangeRateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Antigüedad de las cotizaciones vigentes. Una cotización abierta rige hasta que se publica la siguiente del par,
 * sin vencimiento: si nadie la actualiza, las conversiones siguen usándola. Las que superan
 * {@code card.currency.rates.max-age} se reportan como desactualizadas para alertar; la conversión no se bloquea.
 */
@Service
@Transactional(readOnly = true)
public class ExchangeRateFreshnessService {

    private final ExchangeRateRepository exchangeRateRepository;
    private final CurrencyCatalogService currencyCatalog;
    private final Clock clock;
    private final Duration maxAge;

    public ExchangeRateFreshnessService(ExchangeRateRepository exchangeRateRepository,
                                        CurrencyCatalogService currencyCatalog, Clock clock,
                                        @Value("${card.currency.rates.max-age:PT24H}") Duration maxAge) {
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("card.currency.rates.max-age must be positive: " + maxAge);
        }
        this.exchangeRateRepository = exchangeRateRepository;
        this.currencyCatalog = currencyCatalog;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /**
     * La cotización vigente de cada par cuyas dos monedas están activas (las demás no se pueden usar para
     * convertir), con su antigüedad. Un par sin cotización vigente (solo programadas) no aparece.
     */
    public List<RateAge> currentRates() {
        Instant now = clock.instant();
        Set<String> active = currencyCatalog.list(true).stream()
                .map(SupportedCurrency::getCode)
                .collect(Collectors.toSet());
        return exchangeRateRepository.findAllValidAt(now).stream()
                .filter(rate -> active.contains(rate.getPair().baseCode()) && active.contains(rate.getPair().quoteCode()))
                .map(rate -> new RateAge(rate.getPair(), rate.getRateId(), rate.getValidFrom(), rate.ageAt(now),
                        rate.ageAt(now).compareTo(maxAge) > 0))
                .toList();
    }

    public Duration getMaxAge() {
        return maxAge;
    }

    /**
     * @param stale si rige hace más de {@code max-age}
     */
    public record RateAge(CurrencyPair pair, String rateId, Instant validFrom, Duration age, boolean stale) {
    }
}
