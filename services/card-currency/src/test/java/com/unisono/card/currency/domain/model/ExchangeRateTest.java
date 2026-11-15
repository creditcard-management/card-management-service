package com.unisono.card.currency.domain.model;

import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.InvalidValueObjectException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeRateTest {

    private static final CurrencyPair USD_ARS = CurrencyPair.of("USD", "ARS");
    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");

    private static ExchangeRate rate(String value, Instant from) {
        return ExchangeRate.publish(USD_ARS, new BigDecimal(value), from, RateSource.MANUAL, from);
    }

    @Test
    void scalesRateAndStartsOpen() {
        ExchangeRate rate = rate("1150.5", T0);

        assertThat(rate.getRate()).isEqualByComparingTo("1150.50000000");
        assertThat(rate.getRate().scale()).isEqualTo(ExchangeRate.RATE_SCALE);
        assertThat(rate.isOpen()).isTrue();
        assertThat(rate.getRateId()).isNotBlank();
    }

    @Test
    void rejectsNonPositiveTinyOrHugeRates() {
        assertThatThrownBy(() -> rate("0", T0)).isInstanceOf(InvalidValueObjectException.class);
        assertThatThrownBy(() -> rate("-1", T0)).isInstanceOf(InvalidValueObjectException.class);
        assertThatThrownBy(() -> rate("0.000000001", T0)).isInstanceOf(InvalidValueObjectException.class);
        assertThatThrownBy(() -> rate("100000000000", T0)).isInstanceOf(InvalidValueObjectException.class);
    }

    @Test
    void validityIsHalfOpenInterval() {
        ExchangeRate current = rate("1000", T0);
        ExchangeRate next = rate("1100", T0.plusSeconds(3600));
        current.supersededBy(next);

        assertThat(current.getValidTo()).isEqualTo(next.getValidFrom());
        assertThat(current.isValidAt(T0)).isTrue();
        assertThat(current.isValidAt(T0.plusSeconds(3599))).isTrue();
        assertThat(current.isValidAt(T0.plusSeconds(3600))).isFalse();
        assertThat(current.isValidAt(T0.minusSeconds(1))).isFalse();
        assertThat(next.isValidAt(T0.plusSeconds(3600))).isTrue();
    }

    @Test
    void supersedeRequiresSamePairLaterStartAndOpenRate() {
        ExchangeRate current = rate("1000", T0);

        assertThatThrownBy(() -> current.supersededBy(rate("1000", T0)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> current.supersededBy(
                ExchangeRate.publish(CurrencyPair.of("EUR", "ARS"), BigDecimal.TEN, T0.plusSeconds(1),
                        RateSource.MANUAL, T0)))
                .isInstanceOf(BusinessRuleException.class);

        current.supersededBy(rate("1100", T0.plusSeconds(10)));
        assertThatThrownBy(() -> current.supersededBy(rate("1200", T0.plusSeconds(20))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already closed");
    }

    @Test
    void restoreRejectsInvertedValidity() {
        assertThatThrownBy(() -> ExchangeRate.restore("r1", USD_ARS, BigDecimal.ONE, T0, T0, RateSource.MANUAL, T0))
                .isInstanceOf(InvalidValueObjectException.class);
    }

    @Test
    void onlyScheduledRatesCanBeCancelled() {
        ExchangeRate scheduled = rate("1000", T0.plusSeconds(60));

        scheduled.ensureCancellable(T0);
        assertThatThrownBy(() -> scheduled.ensureCancellable(T0.plusSeconds(60)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already started");
    }

    @Test
    void resumeAfterTakesTheValidityOfTheCancelledRate() {
        ExchangeRate current = rate("1000", T0);
        ExchangeRate middle = rate("1100", T0.plusSeconds(600));
        ExchangeRate last = rate("1200", T0.plusSeconds(1200));
        current.supersededBy(middle);
        middle.supersededBy(last);

        current.resumeAfter(middle);
        assertThat(current.getValidTo()).isEqualTo(T0.plusSeconds(1200));

        ExchangeRate open = rate("900", T0);
        ExchangeRate scheduled = rate("950", T0.plusSeconds(60));
        open.supersededBy(scheduled);
        open.resumeAfter(scheduled);
        assertThat(open.isOpen()).isTrue();
    }

    @Test
    void resumeAfterRequiresTheAdjacentRateOfThePair() {
        ExchangeRate current = rate("1000", T0);
        ExchangeRate other = rate("1100", T0.plusSeconds(600));

        assertThatThrownBy(() -> current.resumeAfter(other)).isInstanceOf(BusinessRuleException.class);
        ExchangeRate eurArs = ExchangeRate.publish(CurrencyPair.of("EUR", "ARS"), BigDecimal.TEN,
                T0.plusSeconds(600), RateSource.MANUAL, T0);
        current.supersededBy(other);
        assertThatThrownBy(() -> current.resumeAfter(eurArs)).isInstanceOf(BusinessRuleException.class);
    }
}
