package com.unisono.card.currency.domain.model;

import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.InvalidValueObjectException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportedCurrencyTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");

    @Test
    void registersActiveWithIsoData() {
        SupportedCurrency brl = SupportedCurrency.register("brl", " Real ", T0);

        assertThat(brl.getCode()).isEqualTo("BRL");
        assertThat(brl.getName()).isEqualTo("Real");
        assertThat(brl.getFractionDigits()).isEqualTo(2);
        assertThat(brl.isActive()).isTrue();
    }

    @Test
    void rejectsCurrenciesWithoutTwoDecimals() {
        assertThatThrownBy(() -> SupportedCurrency.register("JPY", "Yen", T0))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("0 decimals");
        assertThatThrownBy(() -> SupportedCurrency.register("BHD", "Dinar bareiní", T0))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("3 decimals");
    }

    @Test
    void restoreDoesNotCheckDecimals() {
        // Una moneda cargada antes de la regla (o a mano en la base) se sigue pudiendo leer
        assertThat(SupportedCurrency.restore("JPY", "Yen", true, T0, T0).getFractionDigits()).isZero();
    }

    @Test
    void deactivatesAndActivatesUpdatingTimestamp() {
        SupportedCurrency usd = SupportedCurrency.register("USD", "Dólar", T0);

        usd.deactivate(false, T0.plusSeconds(5));
        assertThat(usd.isActive()).isFalse();
        assertThat(usd.getUpdatedAt()).isEqualTo(T0.plusSeconds(5));

        usd.activate(T0.plusSeconds(10));
        assertThat(usd.isActive()).isTrue();
        assertThat(usd.getUpdatedAt()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void aCurrencyWithCreditLimitsCannotBeDeactivated() {
        SupportedCurrency ars = SupportedCurrency.register("ARS", "Peso", T0);

        assertThatThrownBy(() -> ars.deactivate(true, T0))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("credit limits");
        assertThat(ars.isActive()).isTrue();
    }

    @Test
    void requiresName() {
        assertThatThrownBy(() -> SupportedCurrency.register("USD", " ", T0))
                .isInstanceOf(InvalidValueObjectException.class);
    }
}
