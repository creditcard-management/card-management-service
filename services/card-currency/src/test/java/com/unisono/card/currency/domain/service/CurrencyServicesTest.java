package com.unisono.card.currency.domain.service;

import com.unisono.card.currency.domain.exception.CurrencyAlreadyExistsException;
import com.unisono.card.currency.domain.exception.CurrencyNotSupportedException;
import com.unisono.card.currency.domain.exception.ExchangeRateNotFoundException;
import com.unisono.card.currency.domain.model.Conversion;
import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.model.SupportedCurrency;
import com.unisono.card.currency.domain.port.input.ExchangeRateUseCase.PublishRateCommand;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.InvalidValueObjectException;
import com.unisono.card.shared.domain.exception.NotFoundException;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Servicios de card-currency contra repositorios en memoria y un reloj controlado.
 */
class CurrencyServicesTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");
    private static final CurrencyPair USD_ARS = CurrencyPair.of("USD", "ARS");

    private final MutableClock clock = new MutableClock(T0);
    private InMemoryRepositories.Currencies currencies;
    private InMemoryRepositories.CreditLimitCurrencies creditLimitCurrencies;
    private InMemoryRepositories.Rates rates;
    private CurrencyCatalogService catalog;
    private ExchangeRateService exchangeRates;
    private CurrencyConversionService conversions;

    @BeforeEach
    void setUp() {
        currencies = new InMemoryRepositories.Currencies();
        rates = new InMemoryRepositories.Rates();
        creditLimitCurrencies = new InMemoryRepositories.CreditLimitCurrencies();
        creditLimitCurrencies.codes.add("ARS");
        catalog = new CurrencyCatalogService(currencies, creditLimitCurrencies, clock);
        exchangeRates = new ExchangeRateService(rates, catalog, clock, 3);
        conversions = new CurrencyConversionService(rates, catalog, clock);
        catalog.register("ARS", "Peso argentino");
        catalog.register("USD", "Dólar");
        catalog.register("EUR", "Euro");
    }

    private ExchangeRate publish(String value) {
        return exchangeRates.publish(new PublishRateCommand(USD_ARS, new BigDecimal(value), null));
    }

    @Nested
    @DisplayName("Catálogo")
    class Catalog {

        @Test
        void registerRejectsDuplicatesCaseInsensitive() {
            assertThatThrownBy(() -> catalog.register("usd", "Otra"))
                    .isInstanceOf(CurrencyAlreadyExistsException.class);
        }

        @Test
        void deactivateAndListActiveOnly() {
            catalog.deactivate("eur");

            assertThat(catalog.list(true)).extracting(SupportedCurrency::getCode).containsExactly("ARS", "USD");
            assertThat(catalog.list(false)).hasSize(3);
            assertThat(catalog.activate("EUR").isActive()).isTrue();
        }

        @Test
        void aCurrencyWithCreditLimitsCannotBeDeactivated() {
            assertThatThrownBy(() -> catalog.deactivate("ars"))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("ARS").hasMessageContaining("credit limits");
            assertThat(catalog.get("ARS").isActive()).isTrue();
        }

        @Test
        void anyCurrencyWithCreditLimitsIsProtectedNotOnlyArs() {
            creditLimitCurrencies.codes.add("USD");

            assertThatThrownBy(() -> catalog.deactivate("USD")).isInstanceOf(BusinessRuleException.class);
            creditLimitCurrencies.codes.remove("ARS");
            assertThat(catalog.deactivate("ARS").isActive()).isFalse();
        }

        @Test
        void deactivatingAnUnknownCurrencyIsNotFound() {
            assertThatThrownBy(() -> catalog.deactivate("BRL")).isInstanceOf(NotFoundException.class);
        }

        @Test
        void startupCheckReportsNothingWhenEveryCreditLimitCurrencyIsAvailable() {
            assertThat(catalog.warnAboutCreditLimitsInUnavailableCurrencies()).isEmpty();
        }

        @Test
        void startupCheckReportsCreditLimitCurrenciesMissingOrInactive() {
            creditLimitCurrencies.codes.add("BRL"); // no registrada
            // Inactiva solo puede quedar por datos anteriores o un cambio directo en la base
            currencies.save(SupportedCurrency.restore("ARS", "Peso argentino", false, T0, T0));

            assertThat(catalog.warnAboutCreditLimitsInUnavailableCurrencies()).containsExactly("ARS", "BRL");
        }

        @Test
        void unknownCurrencyIsNotFound() {
            assertThatThrownBy(() -> catalog.get("BRL")).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Cotizaciones")
    class Rates {

        @Test
        void publishClosesTheOpenRateOfThePair() {
            ExchangeRate first = publish("1000");
            clock.advance(Duration.ofHours(1));
            ExchangeRate second = publish("1100");

            ExchangeRate closed = rates.rates.stream()
                    .filter(r -> r.getRateId().equals(first.getRateId())).findFirst().orElseThrow();
            assertThat(closed.getValidTo()).isEqualTo(second.getValidFrom());
            assertThat(exchangeRates.getRate(USD_ARS, null).getRateId()).isEqualTo(second.getRateId());
            assertThat(exchangeRates.getRate(USD_ARS, T0).getRateId()).isEqualTo(first.getRateId());
        }

        @Test
        void futureRateDoesNotApplyUntilItStarts() {
            ExchangeRate current = publish("1000");
            exchangeRates.publish(new PublishRateCommand(USD_ARS, new BigDecimal("1200"), T0.plusSeconds(600)));

            assertThat(exchangeRates.getRate(USD_ARS, null).getRateId()).isEqualTo(current.getRateId());
            clock.advance(Duration.ofSeconds(600));
            assertThat(exchangeRates.getRate(USD_ARS, null).getRate()).isEqualByComparingTo("1200");
        }

        @Test
        void rejectsRetroactiveRates() {
            assertThatThrownBy(() -> exchangeRates.publish(
                    new PublishRateCommand(USD_ARS, BigDecimal.TEN, T0.minusSeconds(1))))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("past");
        }

        @Test
        void rejectsRateStartingBeforeTheScheduledOne() {
            exchangeRates.publish(new PublishRateCommand(USD_ARS, BigDecimal.TEN, T0.plusSeconds(600)));

            assertThatThrownBy(() -> exchangeRates.publish(
                    new PublishRateCommand(USD_ARS, BigDecimal.ONE, T0.plusSeconds(300))))
                    .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void rejectsInactiveOrUnknownCurrencies() {
            catalog.deactivate("USD");
            assertThatThrownBy(() -> publish("1000")).isInstanceOf(CurrencyNotSupportedException.class);
            assertThatThrownBy(() -> exchangeRates.publish(
                    new PublishRateCommand(CurrencyPair.of("BRL", "ARS"), BigDecimal.ONE, null)))
                    .isInstanceOf(CurrencyNotSupportedException.class);
        }

        @Test
        void eachPairIsQuotedInASingleDirection() {
            publish("1000");

            assertThatThrownBy(() -> exchangeRates.publish(
                    new PublishRateCommand(USD_ARS.inverse(), new BigDecimal("0.001"), null)))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("already quoted as USD/ARS");
            clock.advance(Duration.ofMinutes(1));
            assertThat(publish("1100").getRate()).isEqualByComparingTo("1100");
        }

        @Test
        void getRateRequiresActiveCurrencies() {
            publish("1000");
            catalog.deactivate("USD");

            assertThatThrownBy(() -> exchangeRates.getRate(USD_ARS, null))
                    .isInstanceOf(CurrencyNotSupportedException.class);
            assertThat(exchangeRates.getHistory(USD_ARS, 10)).hasSize(1);
        }

        @Test
        void missingRateIsNotFound() {
            assertThatThrownBy(() -> exchangeRates.getRate(USD_ARS, null))
                    .isInstanceOf(ExchangeRateNotFoundException.class);
        }

        @Test
        void historyIsNewestFirstAndCapped() {
            for (int i = 1; i <= 5; i++) {
                publish(String.valueOf(1000 + i));
                clock.advance(Duration.ofMinutes(1));
            }

            List<ExchangeRate> history = exchangeRates.getHistory(USD_ARS, 50);
            assertThat(history).hasSize(3);
            assertThat(history.get(0).getRate()).isEqualByComparingTo("1005");
        }
    }

    @Nested
    @DisplayName("Cancelación de cotizaciones programadas")
    class Cancellation {

        private ExchangeRate schedule(String value, long secondsFromNow) {
            return exchangeRates.publish(new PublishRateCommand(USD_ARS, new BigDecimal(value),
                    clock.instant().plusSeconds(secondsFromNow)));
        }

        private ExchangeRate stored(ExchangeRate rate) {
            return rates.findByIdForUpdate(rate.getRateId()).orElseThrow();
        }

        @Test
        void cancellingTheOpenScheduledRateReopensThePreviousOne() {
            ExchangeRate current = publish("1000");
            ExchangeRate scheduled = schedule("1200", 600);

            exchangeRates.cancelScheduled(scheduled.getRateId());

            assertThat(rates.findByIdForUpdate(scheduled.getRateId())).isEmpty();
            assertThat(stored(current).isOpen()).isTrue();
            clock.advance(Duration.ofSeconds(600));
            assertThat(exchangeRates.getRate(USD_ARS, null).getRateId()).isEqualTo(current.getRateId());
            // Se puede volver a programar otra en cualquier momento posterior a la vigente
            assertThat(schedule("1150", 60).getRate()).isEqualByComparingTo("1150");
        }

        @Test
        void cancellingAScheduledRateInTheMiddleExtendsThePreviousOne() {
            ExchangeRate current = publish("1000");
            ExchangeRate first = schedule("1100", 600);
            ExchangeRate second = schedule("1200", 1200);

            exchangeRates.cancelScheduled(first.getRateId());

            assertThat(stored(current).getValidTo()).isEqualTo(second.getValidFrom());
            assertThat(exchangeRates.getRate(USD_ARS, T0.plusSeconds(900)).getRateId())
                    .isEqualTo(current.getRateId());
            assertThat(stored(second).isOpen()).isTrue();
        }

        @Test
        void cancellingTheOnlyRateOfThePairLeavesItWithoutRates() {
            ExchangeRate scheduled = schedule("1000", 60);

            exchangeRates.cancelScheduled(scheduled.getRateId());

            assertThat(exchangeRates.getHistory(USD_ARS, 10)).isEmpty();
        }

        @Test
        void rejectsRatesThatAlreadyStartedAndUnknownIds() {
            ExchangeRate current = publish("1000");

            assertThatThrownBy(() -> exchangeRates.cancelScheduled(current.getRateId()))
                    .isInstanceOf(BusinessRuleException.class);
            assertThatThrownBy(() -> exchangeRates.cancelScheduled("missing"))
                    .isInstanceOf(NotFoundException.class);

            ExchangeRate scheduled = schedule("1100", 60);
            clock.advance(Duration.ofSeconds(60));
            assertThatThrownBy(() -> exchangeRates.cancelScheduled(scheduled.getRateId()))
                    .isInstanceOf(BusinessRuleException.class);
            assertThat(stored(scheduled)).isNotNull();
        }
    }

    @Nested
    @DisplayName("Conversión")
    class Conversions {

        @Test
        void convertsWithDirectRate() {
            ExchangeRate rate = publish("1150.50");

            Conversion conversion = conversions.convert(Money.of(new BigDecimal("10.00"), "USD"), "ARS", null);

            assertThat(conversion.target()).isEqualTo(Money.of(new BigDecimal("11505.00"), "ARS"));
            assertThat(conversion.appliedRate()).isEqualByComparingTo("1150.50");
            assertThat(conversion.exchangeRate().getRateId()).isEqualTo(rate.getRateId());
            assertThat(conversion.inverse()).isFalse();
        }

        @Test
        void convertsWithInverseRateRoundingHalfUp() {
            publish("1000");

            Conversion conversion = conversions.convert(Money.of(new BigDecimal("1234.56"), "ARS"), "usd", null);

            assertThat(conversion.target()).isEqualTo(Money.of(new BigDecimal("1.23"), "USD"));
            assertThat(conversion.appliedRate()).isEqualByComparingTo("0.00100000");
            assertThat(conversion.inverse()).isTrue();
        }

        @Test
        void sameCurrencyIsIdentity() {
            Conversion conversion = conversions.convert(Money.ofARS(500), "ARS", null);

            assertThat(conversion.isIdentity()).isTrue();
            assertThat(conversion.target()).isEqualTo(Money.ofARS(500));
        }

        @Test
        void usesTheRateValidAtTheRequestedInstant() {
            publish("1000");
            clock.advance(Duration.ofHours(1));
            publish("2000");

            Conversion past = conversions.convert(Money.of(BigDecimal.ONE, "USD"), "ARS", T0.plusSeconds(60));
            assertThat(past.target().getAmount()).isEqualByComparingTo("1000");
        }

        @Test
        void failsWithoutRateOrWithInactiveCurrency() {
            assertThatThrownBy(() -> conversions.convert(Money.of(BigDecimal.ONE, "EUR"), "ARS", null))
                    .isInstanceOf(ExchangeRateNotFoundException.class);

            catalog.deactivate("EUR");
            assertThatThrownBy(() -> conversions.convert(Money.of(BigDecimal.ONE, "EUR"), "ARS", null))
                    .isInstanceOf(CurrencyNotSupportedException.class);
        }

        @Test
        void rejectsNegativeAmounts() {
            assertThatThrownBy(() -> conversions.convert(Money.of(new BigDecimal("-1"), "USD"), "ARS", null))
                    .isInstanceOf(InvalidValueObjectException.class);
        }
    }

    /** Reloj que avanza a mano. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
