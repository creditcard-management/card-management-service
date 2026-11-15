package com.unisono.card.currency.domain.service;

import com.unisono.card.currency.domain.exception.ExchangeRateNotFoundException;
import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.model.RateSource;
import com.unisono.card.currency.domain.port.input.ExchangeRateUseCase;
import com.unisono.card.currency.domain.port.output.ExchangeRateRepository;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Publicación y consulta de cotizaciones. Implementa: ExchangeRateUseCase
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ExchangeRateService implements ExchangeRateUseCase {

    private final ExchangeRateRepository exchangeRateRepository;
    private final CurrencyCatalogService currencyCatalog;
    private final Clock clock;
    private final int historyMaxSize;

    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository, CurrencyCatalogService currencyCatalog,
                               Clock clock, @Value("${card.currency.history-max-size:100}") int historyMaxSize) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.currencyCatalog = currencyCatalog;
        this.clock = clock;
        this.historyMaxSize = Math.max(1, historyMaxSize);
    }

    @Override
    @Transactional
    public ExchangeRate publish(PublishRateCommand command) {
        CurrencyPair pair = command.pair();
        currencyCatalog.requireActive(pair.base());
        currencyCatalog.requireActive(pair.quote());

        // Primero el lock del par y después la hora: si otra publicación del par estaba en curso, la nueva
        // arranca después de la que acaba de entrar.
        Optional<ExchangeRate> current = exchangeRateRepository.findOpenForUpdate(pair);
        if (current.isEmpty() && exchangeRateRepository.existsForPair(pair.inverse())) {
            // Cada par se cotiza en un solo sentido: dos tasas independientes (USD/ARS y ARS/USD) podrían
            // contradecirse. La conversión usa la inversa sola.
            throw new BusinessRuleException(String.format(
                    "%s is already quoted as %s: publish %s instead (conversions use the inverse rate)",
                    pair, pair.inverse(), pair.inverse()));
        }
        Instant now = clock.instant();
        Instant effectiveFrom = command.effectiveFrom() != null ? command.effectiveFrom() : now;
        if (effectiveFrom.isBefore(now)) {
            // Una cotización retroactiva cambiaría la tasa de conversiones que ya se hicieron
            throw new BusinessRuleException("An exchange rate cannot start in the past: " + effectiveFrom);
        }

        ExchangeRate next = ExchangeRate.publish(pair, command.rate(), effectiveFrom, RateSource.MANUAL, now);
        current.ifPresent(open -> {
            open.supersededBy(next);
            exchangeRateRepository.save(open);
        });

        ExchangeRate saved = exchangeRateRepository.save(next);
        log.info("Published {} rate {} from {} (replaces {})", pair, saved.getRate(), effectiveFrom,
                current.map(ExchangeRate::getRateId).orElse("none"));
        return saved;
    }

    @Override
    @Transactional
    public ExchangeRate cancelScheduled(String rateId) {
        ExchangeRate scheduled = exchangeRateRepository.findByIdForUpdate(rateId)
                .orElseThrow(() -> new NotFoundException("Exchange rate", rateId));
        scheduled.ensureCancellable(clock.instant());

        Optional<ExchangeRate> previous = exchangeRateRepository.findEndingAt(scheduled.getPair(),
                scheduled.getValidFrom());
        // Primero el DELETE: si la cancelada es la abierta, la anterior no puede reabrirse antes (índice único)
        exchangeRateRepository.delete(scheduled);
        previous.ifPresent(rate -> {
            rate.resumeAfter(scheduled);
            exchangeRateRepository.save(rate);
        });

        log.info("Cancelled scheduled {} rate {} from {} ({} extended)", scheduled.getPair(), rateId,
                scheduled.getValidFrom(), previous.map(ExchangeRate::getRateId).orElse("none"));
        return scheduled;
    }

    @Override
    public ExchangeRate getRate(CurrencyPair pair, Instant at) {
        currencyCatalog.requireActive(pair.base());
        currencyCatalog.requireActive(pair.quote());
        Instant instant = at != null ? at : clock.instant();
        return exchangeRateRepository.findValidAt(pair, instant)
                .orElseThrow(() -> new ExchangeRateNotFoundException(pair, instant));
    }

    @Override
    public List<ExchangeRate> getHistory(CurrencyPair pair, int limit) {
        int size = Math.min(Math.max(1, limit), historyMaxSize);
        return exchangeRateRepository.findHistory(pair, size);
    }
}
