package com.unisono.card.currency.infrastructure.observability;

import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.service.ExchangeRateFreshnessService;
import com.unisono.card.currency.domain.service.ExchangeRateFreshnessService.RateAge;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Métricas tal como las exporta /actuator/prometheus: los nombres son los que usan las reglas de
 * deploy/prometheus/card-currency-alerts.yml.
 */
class ExchangeRateFreshnessMonitorTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");

    private final ExchangeRateFreshnessService freshness = mock(ExchangeRateFreshnessService.class);
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private ExchangeRateFreshnessMonitor monitor;

    @BeforeEach
    void setUp() {
        when(freshness.getMaxAge()).thenReturn(Duration.ofHours(24));
        monitor = new ExchangeRateFreshnessMonitor(freshness, registry);
    }

    private static RateAge rate(String base, String quote, Duration age, boolean stale) {
        return new RateAge(CurrencyPair.of(base, quote), "rate-" + base, T0, age, stale);
    }

    @Test
    void exportsTheStalePairsAndTheAgeOfEachPair() {
        when(freshness.currentRates()).thenReturn(List.of(
                rate("USD", "ARS", Duration.ofHours(30), true),
                rate("EUR", "ARS", Duration.ofHours(2), false)));

        monitor.refresh();

        String scrape = registry.scrape();
        assertThat(scrape).contains("card_currency_exchange_rates_stale 1.0");
        assertThat(scrape).contains("card_currency_exchange_rate_age_seconds{pair=\"USD/ARS\"} 108000.0");
        assertThat(scrape).contains("card_currency_exchange_rate_age_seconds{pair=\"EUR/ARS\"} 7200.0");
        assertThat(scrape).contains("card_currency_exchange_rates_max_age_seconds 86400.0");
    }

    @Test
    void aPairThatIsNoLongerCurrentStopsBeingExported() {
        when(freshness.currentRates()).thenReturn(List.of(rate("USD", "ARS", Duration.ofHours(30), true)),
                List.of());

        monitor.refresh();
        monitor.refresh();

        String scrape = registry.scrape();
        assertThat(scrape).contains("card_currency_exchange_rates_stale 0.0");
        assertThat(scrape).doesNotContain("pair=\"USD/ARS\"");
    }

    @Test
    void aFailedCheckIsCountedAndKeepsTheLastValues() {
        when(freshness.currentRates()).thenReturn(List.of(rate("USD", "ARS", Duration.ofHours(30), true)))
                .thenThrow(new IllegalStateException("database down"));

        monitor.refresh();
        monitor.refresh();

        String scrape = registry.scrape();
        assertThat(scrape).contains("card_currency_exchange_rates_freshness_check_failures_total 1.0");
        assertThat(scrape).contains("card_currency_exchange_rates_stale 1.0");
    }
}
