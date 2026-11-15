package com.unisono.card.currency.infrastructure.adapter.output.persistence;

import com.unisono.card.currency.domain.model.CurrencyPair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.unisono.card.currency.infrastructure.adapter.output.persistence.adapter.ExchangeRateRepositoryAdapter.pairLockKey;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clave del advisory lock por par: sin colisiones, igual para los dos sentidos y dentro de un int.
 */
class PairLockKeyTest {

    @Test
    void isTheSameForBothDirections() {
        assertThat(pairLockKey(CurrencyPair.of("USD", "ARS"))).isEqualTo(pairLockKey(CurrencyPair.of("ARS", "USD")));
    }

    @Test
    void isDistinctAndInRangeForEveryPairOfIsoCurrencies() {
        List<String> codes = new ArrayList<>();
        for (Currency currency : Currency.getAvailableCurrencies()) {
            codes.add(currency.getCurrencyCode());
        }
        Map<Integer, String> seen = new HashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            for (int j = i + 1; j < codes.size(); j++) {
                CurrencyPair pair = CurrencyPair.of(codes.get(i), codes.get(j));
                int key = pairLockKey(pair);
                // Entre 0 y 17.575 × 17.576 + 17.575 = 308.915.775: entra en un int positivo
                assertThat(key).isBetween(0, 17_575 * 17_576 + 17_575);
                String previous = seen.put(key, pair.toString());
                assertThat(previous).as("collision between %s and %s", previous, pair).isNull();
            }
        }
        assertThat(seen).hasSizeGreaterThan(10_000);
    }
}
