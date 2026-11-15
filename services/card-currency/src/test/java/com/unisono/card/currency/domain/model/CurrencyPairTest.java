package com.unisono.card.currency.domain.model;

import com.unisono.card.shared.domain.exception.InvalidValueObjectException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyPairTest {

    @Test
    void normalizesCodesAndInverts() {
        CurrencyPair pair = CurrencyPair.of("usd", " ars ");

        assertThat(pair.baseCode()).isEqualTo("USD");
        assertThat(pair.quoteCode()).isEqualTo("ARS");
        assertThat(pair.inverse()).isEqualTo(CurrencyPair.of("ARS", "USD"));
        assertThat(pair).hasToString("USD/ARS");
    }

    @Test
    void rejectsSameCurrency() {
        assertThatThrownBy(() -> CurrencyPair.of("USD", "usd"))
                .isInstanceOf(InvalidValueObjectException.class)
                .hasMessageContaining("two different currencies");
    }

    @Test
    void rejectsUnknownOrBlankCodes() {
        assertThatThrownBy(() -> CurrencyPair.of("XXZ", "ARS")).isInstanceOf(InvalidValueObjectException.class);
        assertThatThrownBy(() -> CurrencyPair.of(" ", "ARS")).isInstanceOf(InvalidValueObjectException.class);
        assertThatThrownBy(() -> CurrencyPair.of(null, "ARS")).isInstanceOf(InvalidValueObjectException.class);
    }
}
