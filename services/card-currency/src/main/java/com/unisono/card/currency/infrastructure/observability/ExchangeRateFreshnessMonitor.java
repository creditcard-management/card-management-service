package com.unisono.card.currency.infrastructure.observability;

import com.unisono.card.currency.domain.service.ExchangeRateFreshnessService;
import com.unisono.card.currency.domain.service.ExchangeRateFreshnessService.RateAge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alerta de cotizaciones desactualizadas (vigentes hace más de {@code card.currency.rates.max-age}). No bloquea
 * conversiones: las publica como métricas y las avisa en el log.
 * <p>
 * Gauges (se recalculan cada {@code card.currency.rates.freshness-check-interval-ms} con una consulta, no en cada
 * scrape):
 * <ul>
 *   <li>{@code card_currency.exchange_rates.stale}: pares con monedas activas cuya cotización vigente supera la
 *       antigüedad máxima.</li>
 *   <li>{@code card_currency.exchange_rate.age} (segundos, tag {@code pair}): antigüedad de la vigente de cada par.</li>
 *   <li>{@code card_currency.exchange_rates.max_age} (segundos): el umbral configurado.</li>
 * </ul>
 * Contador {@code card_currency.exchange_rates.freshness_check_failures}: corridas que fallaron (los gauges quedan
 * con el último valor). En el log, un WARN cuando un par pasa a estar desactualizado y un INFO cuando se actualiza:
 * no se repite en cada corrida. Las reglas de alerta de ejemplo están en
 * {@code deploy/prometheus/card-currency-alerts.yml}.
 */
@Slf4j
@Component
public class ExchangeRateFreshnessMonitor {

    private final ExchangeRateFreshnessService freshness;
    private final AtomicLong stale = new AtomicLong();
    private final MultiGauge ages;
    private final Counter failures;
    private final Set<String> stalePairs = new HashSet<>();

    public ExchangeRateFreshnessMonitor(ExchangeRateFreshnessService freshness, MeterRegistry registry) {
        this.freshness = freshness;
        Gauge.builder("card_currency.exchange_rates.stale", stale, AtomicLong::get)
                .description("Currency pairs whose current exchange rate is older than the maximum age")
                .register(registry);
        Gauge.builder("card_currency.exchange_rates.max_age", freshness, f -> f.getMaxAge().toSeconds())
                .description("Maximum age of a current exchange rate before it is reported as stale")
                .baseUnit("seconds")
                .register(registry);
        this.ages = MultiGauge.builder("card_currency.exchange_rate.age")
                .description("Age of the current exchange rate of each currency pair")
                .baseUnit("seconds")
                .register(registry);
        this.failures = Counter.builder("card_currency.exchange_rates.freshness_check_failures")
                .description("Exchange rate freshness checks that could not run")
                .register(registry);
    }

    @Scheduled(
            initialDelayString = "${card.currency.rates.freshness-check-initial-delay-ms:15000}",
            fixedDelayString = "${card.currency.rates.freshness-check-interval-ms:300000}"
    )
    public void refresh() {
        List<RateAge> current;
        try {
            current = freshness.currentRates();
        } catch (Exception ex) {
            failures.increment();
            log.error("Could not check the age of the exchange rates", ex);
            return;
        }

        List<MultiGauge.Row<?>> rows = current.stream()
                .<MultiGauge.Row<?>>map(rate -> MultiGauge.Row.of(Tags.of("pair", rate.pair().toString()),
                        rate.age().toSeconds()))
                .toList();
        ages.register(rows, true);
        stale.set(current.stream().filter(RateAge::stale).count());
        logTransitions(current);
    }

    private synchronized void logTransitions(List<RateAge> current) {
        Set<String> nowStale = new HashSet<>();
        for (RateAge rate : current) {
            String pair = rate.pair().toString();
            if (!rate.stale()) {
                continue;
            }
            nowStale.add(pair);
            if (!stalePairs.contains(pair)) {
                log.warn("⚠️ Exchange rate {} is stale: rate {} has been current since {} ({} h, max {} h). "
                                + "Conversions keep using it until a new one is published",
                        pair, rate.rateId(), rate.validFrom(), rate.age().toHours(), freshness.getMaxAge().toHours());
            }
        }
        for (String pair : stalePairs) {
            if (!nowStale.contains(pair)) {
                log.info("Exchange rate {} is no longer stale", pair);
            }
        }
        stalePairs.clear();
        stalePairs.addAll(nowStale);
    }
}
