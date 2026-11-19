package com.unisono.card.currency.domain.model;

import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.InvalidValueObjectException;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Cotización de un par de monedas con su vigencia {@code [validFrom, validTo)}. {@code validTo} nulo significa
 * vigente hasta que se publique la siguiente. Para cada par las vigencias no se superponen: publicar una
 * cotización nueva cierra la anterior en el instante en que empieza la nueva.
 */
@Getter
public class ExchangeRate {

    /** Escala de las tasas (columna numeric(19,8)). */
    public static final int RATE_SCALE = 8;

    private final String rateId;
    private final CurrencyPair pair;
    private final BigDecimal rate;
    private final Instant validFrom;
    private Instant validTo;
    private final RateSource source;
    private final Instant createdAt;

    private ExchangeRate(String rateId, CurrencyPair pair, BigDecimal rate, Instant validFrom, Instant validTo,
                         RateSource source, Instant createdAt) {
        if (pair == null) {
            throw new InvalidValueObjectException("Currency pair is required");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new InvalidValueObjectException("Exchange rate must be positive");
        }
        BigDecimal scaled = rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0) {
            throw new InvalidValueObjectException("Exchange rate is below the supported precision");
        }
        if (scaled.precision() - scaled.scale() > 11) {
            throw new InvalidValueObjectException("Exchange rate is too large");
        }
        if (validFrom == null) {
            throw new InvalidValueObjectException("Valid from is required");
        }
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new InvalidValueObjectException("Valid to must be after valid from");
        }
        this.rateId = rateId;
        this.pair = pair;
        this.rate = scaled;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.source = source == null ? RateSource.MANUAL : source;
        this.createdAt = createdAt;
    }

    public static ExchangeRate publish(CurrencyPair pair, BigDecimal rate, Instant validFrom, RateSource source,
                                      Instant now) {
        return new ExchangeRate(UUID.randomUUID().toString(), pair, rate, validFrom, null, source, now);
    }

    public static ExchangeRate restore(String rateId, CurrencyPair pair, BigDecimal rate, Instant validFrom,
                                       Instant validTo, RateSource source, Instant createdAt) {
        return new ExchangeRate(rateId, pair, rate, validFrom, validTo, source, createdAt);
    }

    /**
     * Cierra la vigencia cuando la reemplaza {@code next}, que tiene que empezar después de esta.
     */
    public void supersededBy(ExchangeRate next) {
        if (!pair.equals(next.pair)) {
            throw new BusinessRuleException("Cannot supersede " + pair + " with a rate for " + next.pair);
        }
        if (validTo != null) {
            throw new BusinessRuleException("Exchange rate " + rateId + " is already closed");
        }
        if (!next.validFrom.isAfter(validFrom)) {
            throw new BusinessRuleException(String.format(
                    "The new %s rate must start after the current one (%s)", pair, validFrom));
        }
        this.validTo = next.validFrom;
    }

    /**
     * Solo se puede cancelar una cotización programada: si ya empezó, alguna conversión pudo haberla usado.
     */
    public void ensureCancellable(Instant now) {
        if (!validFrom.isAfter(now)) {
            throw new BusinessRuleException(String.format(
                    "Exchange rate %s already started at %s: it can only be superseded by a new one",
                    rateId, validFrom));
        }
    }

    /**
     * Extiende la vigencia de esta cotización (la anterior de {@code cancelled}) hasta donde llegaba la cancelada:
     * si la cancelada era la abierta, esta vuelve a quedar abierta.
     */
    public void resumeAfter(ExchangeRate cancelled) {
        if (!pair.equals(cancelled.pair)) {
            throw new BusinessRuleException("Cannot resume " + pair + " after a rate for " + cancelled.pair);
        }
        if (validTo == null || !validTo.equals(cancelled.validFrom)) {
            throw new BusinessRuleException(
                    "Exchange rate " + rateId + " does not end where " + cancelled.rateId + " starts");
        }
        this.validTo = cancelled.validTo;
    }

    public boolean isOpen() {
        return validTo == null;
    }

    public boolean isValidAt(Instant instant) {
        return !instant.isBefore(validFrom) && (validTo == null || instant.isBefore(validTo));
    }

    /**
     * Cuánto hace que rige: desde {@code validFrom} hasta {@code now} (cero si todavía no empezó)
     */
    public Duration ageAt(Instant now) {
        return now.isBefore(validFrom) ? Duration.ZERO : Duration.between(validFrom, now);
    }
}
