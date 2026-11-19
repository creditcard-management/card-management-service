package com.unisono.card.currency.domain.service;

import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.model.RateSource;
import com.unisono.card.currency.domain.service.ExchangeRateFreshnessService.RateAge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Antigüedad de las cotizaciones vigentes: solo se reportan, no bloquean conversiones.
 */
class ExchangeRateFreshnessServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");
    private static final CurrencyPair USD_ARS = CurrencyPair.of("USD", "ARS");
    private static final CurrencyPair EUR_ARS = CurrencyPair.of("EUR", "ARS");

    private final CurrencyServicesTest.MutableClock clock = new CurrencyServicesTest.MutableClock(T0);
    private InMemoryRepositories.Rates rates;
    private CurrencyCatalogService catalog;
    private ExchangeRateFreshnessService freshness;

    @BeforeEach
    void setUp() {
        InMemoryRepositories.Currencies currencies = new InMemoryRepositories.Currencies();
        rates = new InMemoryRepositories.Rates();
        catalog = new CurrencyCatalogService(currencies, new InMemoryRepositories.CreditLimitCurrencies(), clock);
        freshness = new ExchangeRateFreshnessService(rates, catalog, clock, Duration.ofHours(24));
        catalog.register("ARS", "Peso argentino");
        catalog.register("USD", "Dólar");
        catalog.register("EUR", "Euro");
    }

    private ExchangeRate rate(CurrencyPair pair, Instant from) {
        return rates.save(ExchangeRate.publish(pair, new BigDecimal("1000"), from, RateSource.MANUAL, from));
    }

    @Test
    void aRateYoungerThanTheMaximumAgeIsNotStale() {
        ExchangeRate usd = rate(USD_ARS, T0);
        clock.advance(Duration.ofHours(24));

        assertThat(freshness.currentRates()).singleElement().satisfies(current -> {
            assertThat(current.pair()).isEqualTo(USD_ARS);
            assertThat(current.rateId()).isEqualTo(usd.getRateId());
            assertThat(current.age()).isEqualTo(Duration.ofHours(24));
            assertThat(current.stale()).isFalse();
        });
    }

    @Test
    void aRateOlderThanTheMaximumAgeIsStale() {
        rate(USD_ARS, T0);
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(freshness.currentRates()).singleElement().extracting(RateAge::stale).isEqualTo(true);
    }

    @Test
    void publishingANewRateMakesThePairFreshAgain() {
        ExchangeRate old = rate(USD_ARS, T0);
        clock.advance(Duration.ofHours(30));
        ExchangeRate fresh = ExchangeRate.publish(USD_ARS, new BigDecimal("1100"), clock.instant(), RateSource.MANUAL,
                clock.instant());
        old.supersededBy(fresh);
        rates.save(old);
        rates.save(fresh);

        assertThat(freshness.currentRates()).singleElement().satisfies(current -> {
            assertThat(current.rateId()).isEqualTo(fresh.getRateId());
            assertThat(current.stale()).isFalse();
        });
    }

    @Test
    void eachPairIsCheckedOnItsOwn() {
        rate(USD_ARS, T0);
        clock.advance(Duration.ofHours(20));
        rate(EUR_ARS, clock.instant());
        clock.advance(Duration.ofHours(10));

        assertThat(freshness.currentRates())
                .extracting(current -> current.pair().toString(), RateAge::stale)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("USD/ARS", true),
                        org.assertj.core.groups.Tuple.tuple("EUR/ARS", false));
    }

    @Test
    void pairsWithAnInactiveCurrencyAreNotReported() {
        rate(USD_ARS, T0);
        rate(EUR_ARS, T0);
        catalog.deactivate("EUR");
        clock.advance(Duration.ofHours(48));

        assertThat(freshness.currentRates()).extracting(RateAge::pair).containsExactly(USD_ARS);
    }

    @Test
    void aScheduledRateIsNotCurrentYet() {
        rate(USD_ARS, T0.plus(Duration.ofHours(1)));

        assertThat(freshness.currentRates()).isEmpty();
    }

    @Test
    void theMaximumAgeMustBePositive() {
        assertThatThrownBy(() -> new ExchangeRateFreshnessService(rates, catalog, clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("card.currency.rates.max-age");
    }
}
