package com.unisono.card.currency.domain.model;

import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.InvalidValueObjectException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportedCurrencyTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");
    private static final Currency ARS = Currency.getInstance("ARS");

    @Test
    void registersActiveWithIsoData() {
        SupportedCurrency jpy = SupportedCurrency.register("jpy", " Yen ", T0);

        assertThat(jpy.getCode()).isEqualTo("JPY");
        assertThat(jpy.getName()).isEqualTo("Yen");
        assertThat(jpy.getFractionDigits()).isZero();
        assertThat(jpy.isActive()).isTrue();
    }

    @Test
    void deactivatesAndActivatesUpdatingTimestamp() {
        SupportedCurrency usd = SupportedCurrency.register("USD", "Dólar", T0);

        usd.deactivate(ARS, T0.plusSeconds(5));
        assertThat(usd.isActive()).isFalse();
        assertThat(usd.getUpdatedAt()).isEqualTo(T0.plusSeconds(5));

        usd.activate(T0.plusSeconds(10));
        assertThat(usd.isActive()).isTrue();
        assertThat(usd.getUpdatedAt()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void systemCurrencyCannotBeDeactivated() {
        SupportedCurrency ars = SupportedCurrency.register("ARS", "Peso", T0);

        assertThatThrownBy(() -> ars.deactivate(ARS, T0)).isInstanceOf(BusinessRuleException.class);
        assertThat(ars.isActive()).isTrue();
    }

    @Test
    void requiresName() {
        assertThatThrownBy(() -> SupportedCurrency.register("USD", " ", T0))
                .isInstanceOf(InvalidValueObjectException.class);
    }
}
