package com.unisono.card.currency.domain.service;

import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.model.SupportedCurrency;
import com.unisono.card.currency.domain.port.output.CreditLimitCurrencyPort;
import com.unisono.card.currency.domain.port.output.CurrencyRepository;
import com.unisono.card.currency.domain.port.output.ExchangeRateRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fakes en memoria de los repositorios para testear los servicios sin JPA.
 */
final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    static final class Currencies implements CurrencyRepository {
        final Map<String, SupportedCurrency> byCode = new LinkedHashMap<>();

        @Override
        public SupportedCurrency save(SupportedCurrency currency) {
            byCode.put(currency.getCode(), currency);
            return currency;
        }

        @Override
        public Optional<SupportedCurrency> findByCode(String code) {
            return Optional.ofNullable(byCode.get(code));
        }

        @Override
        public Optional<SupportedCurrency> findByCodeForUpdate(String code) {
            return findByCode(code);
        }

        @Override
        public boolean existsByCode(String code) {
            return byCode.containsKey(code);
        }

        @Override
        public List<SupportedCurrency> findAll(boolean activeOnly) {
            return byCode.values().stream()
                    .filter(c -> !activeOnly || c.isActive())
                    .sorted(Comparator.comparing(SupportedCurrency::getCode))
                    .toList();
        }
    }

    /** Monedas de los límites de crédito de card-limits: las que el test agrega */
    static final class CreditLimitCurrencies implements CreditLimitCurrencyPort {
        final Set<String> codes = new TreeSet<>();

        @Override
        public boolean hasCreditLimitsIn(String currencyCode) {
            return codes.contains(currencyCode);
        }

        @Override
        public Set<String> currenciesWithCreditLimits() {
            return Set.copyOf(codes);
        }
    }

    static final class Rates implements ExchangeRateRepository {
        final List<ExchangeRate> rates = new ArrayList<>();

        @Override
        public ExchangeRate save(ExchangeRate rate) {
            rates.removeIf(r -> r.getRateId().equals(rate.getRateId()));
            if (rate.isOpen() && rates.stream().anyMatch(r -> r.isOpen() && r.getPair().equals(rate.getPair()))) {
                throw new IllegalStateException("Two open rates for " + rate.getPair());
            }
            rates.add(rate);
            return rate;
        }

        @Override
        public Optional<ExchangeRate> findOpenForUpdate(CurrencyPair pair) {
            return rates.stream().filter(r -> r.getPair().equals(pair) && r.isOpen()).findFirst();
        }

        @Override
        public Optional<ExchangeRate> findByIdForUpdate(String rateId) {
            return rates.stream().filter(r -> r.getRateId().equals(rateId)).findFirst();
        }

        @Override
        public Optional<ExchangeRate> findEndingAt(CurrencyPair pair, Instant validTo) {
            return rates.stream().filter(r -> r.getPair().equals(pair) && validTo.equals(r.getValidTo())).findFirst();
        }

        @Override
        public boolean existsForPair(CurrencyPair pair) {
            return rates.stream().anyMatch(r -> r.getPair().equals(pair));
        }

        @Override
        public void delete(ExchangeRate rate) {
            rates.removeIf(r -> r.getRateId().equals(rate.getRateId()));
        }

        @Override
        public Optional<ExchangeRate> findValidAt(CurrencyPair pair, Instant at) {
            return rates.stream().filter(r -> r.getPair().equals(pair) && r.isValidAt(at)).findFirst();
        }

        @Override
        public List<ExchangeRate> findAllValidAt(Instant at) {
            return rates.stream().filter(r -> r.isValidAt(at)).toList();
        }

        @Override
        public List<ExchangeRate> findHistory(CurrencyPair pair, int limit) {
            return rates.stream()
                    .filter(r -> r.getPair().equals(pair))
                    .sorted(Comparator.comparing(ExchangeRate::getValidFrom).reversed())
                    .limit(limit)
                    .toList();
        }
    }
}
