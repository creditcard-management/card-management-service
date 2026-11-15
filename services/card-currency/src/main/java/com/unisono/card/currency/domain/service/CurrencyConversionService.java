package com.unisono.card.currency.domain.service;

import com.unisono.card.currency.domain.exception.ExchangeRateNotFoundException;
import com.unisono.card.currency.domain.model.Conversion;
import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.port.input.ConvertAmountUseCase;
import com.unisono.card.currency.domain.port.output.ExchangeRateRepository;
import com.unisono.card.shared.domain.exception.InvalidValueObjectException;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;

/**
 * Conversión de montos entre monedas. Implementa: ConvertAmountUseCase
 * <p>
 * Usa la cotización directa del par (origen/destino) y, si no hay, la del par inverso dividiendo. El monto
 * convertido se redondea a 2 decimales HALF_UP, como todo {@link Money}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrencyConversionService implements ConvertAmountUseCase {

    private final ExchangeRateRepository exchangeRateRepository;
    private final CurrencyCatalogService currencyCatalog;
    private final Clock clock;

    @Override
    public Conversion convert(Money amount, String targetCurrency, Instant at) {
        if (amount == null) {
            throw new InvalidValueObjectException("Amount is required");
        }
        if (amount.isNegative()) {
            throw new InvalidValueObjectException("Amount to convert cannot be negative");
        }
        Currency source = currencyCatalog.requireActive(amount.getCurrency());
        Currency target = currencyCatalog.requireActive(CurrencyPair.currency(targetCurrency));

        if (source.equals(target)) {
            return new Conversion(amount, amount, BigDecimal.ONE, null, false);
        }

        CurrencyPair pair = new CurrencyPair(source, target);
        Instant instant = at != null ? at : clock.instant();

        Optional<ExchangeRate> direct = exchangeRateRepository.findValidAt(pair, instant);
        if (direct.isPresent()) {
            ExchangeRate rate = direct.get();
            Money converted = Money.of(amount.getAmount().multiply(rate.getRate()), target);
            return new Conversion(amount, converted, rate.getRate(), rate, false);
        }

        ExchangeRate inverse = exchangeRateRepository.findValidAt(pair.inverse(), instant)
                .orElseThrow(() -> new ExchangeRateNotFoundException(pair, instant));
        Money converted = Money.of(amount.getAmount().divide(inverse.getRate(), 2, RoundingMode.HALF_UP), target);
        BigDecimal applied = BigDecimal.ONE.divide(inverse.getRate(), ExchangeRate.RATE_SCALE, RoundingMode.HALF_UP);
        log.debug("Converted {} to {} with inverse rate {}", amount, converted, inverse.getPair());
        return new Conversion(amount, converted, applied, inverse, true);
    }
}
