package com.unisono.card.currency.domain.service;

import com.unisono.card.currency.domain.exception.CurrencyAlreadyExistsException;
import com.unisono.card.currency.domain.exception.CurrencyNotSupportedException;
import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.SupportedCurrency;
import com.unisono.card.currency.domain.port.input.ManageCurrenciesUseCase;
import com.unisono.card.currency.domain.port.output.CreditLimitCurrencyPort;
import com.unisono.card.currency.domain.port.output.CurrencyRepository;
import com.unisono.card.shared.domain.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Catálogo de monedas aceptadas. Implementa: ManageCurrenciesUseCase
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrencyCatalogService implements ManageCurrenciesUseCase {

    private final CurrencyRepository currencyRepository;
    private final CreditLimitCurrencyPort creditLimitCurrencies;
    private final Clock clock;

    @Override
    @Transactional
    public SupportedCurrency register(String code, String name) {
        SupportedCurrency currency = SupportedCurrency.register(code, name, clock.instant());
        if (currencyRepository.existsByCode(currency.getCode())) {
            throw new CurrencyAlreadyExistsException(currency.getCode());
        }
        log.info("Registering currency {}", currency.getCode());
        return currencyRepository.save(currency);
    }

    @Override
    @Transactional
    public SupportedCurrency activate(String code) {
        SupportedCurrency currency = get(code);
        currency.activate(clock.instant());
        return currencyRepository.save(currency);
    }

    @Override
    @Transactional
    public SupportedCurrency deactivate(String code) {
        // Bloquea la fila antes de mirar los límites: una asignación concurrente en esta moneda (card-limits lee la
        // fila con FOR SHARE) espera a esta baja o esta baja ve el límite ya confirmado
        String normalized = CurrencyPair.currency(code).getCurrencyCode();
        SupportedCurrency currency = currencyRepository.findByCodeForUpdate(normalized)
                .orElseThrow(() -> new NotFoundException("Currency", normalized));
        currency.deactivate(creditLimitCurrencies.hasCreditLimitsIn(normalized), clock.instant());
        log.info("Currency {} deactivated", currency.getCode());
        return currencyRepository.save(currency);
    }

    @Override
    public SupportedCurrency get(String code) {
        String normalized = CurrencyPair.currency(code).getCurrencyCode();
        return currencyRepository.findByCode(normalized)
                .orElseThrow(() -> new NotFoundException("Currency", normalized));
    }

    @Override
    public List<SupportedCurrency> list(boolean activeOnly) {
        return currencyRepository.findAll(activeOnly);
    }

    /**
     * Loguea un WARN por cada moneda con límites de crédito que no está dada de alta o está inactiva: los pagos en
     * otra moneda de esas tarjetas fallan con 422 hasta que se corrija el catálogo.
     *
     * @return las monedas con límites que no están disponibles (vacío si no hay ninguna)
     */
    public Set<String> warnAboutCreditLimitsInUnavailableCurrencies() {
        Set<String> unavailable = new TreeSet<>();
        for (String code : creditLimitCurrencies.currenciesWithCreditLimits()) {
            Optional<SupportedCurrency> currency = currencyRepository.findByCode(code);
            if (currency.isEmpty()) {
                log.warn("There are credit limits in {}, which is not registered: conversions to it fail with 422. "
                        + "Register it with POST /api/v1/currencies", code);
                unavailable.add(code);
            } else if (!currency.get().isActive()) {
                log.warn("There are credit limits in {}, which is inactive: conversions to it fail with 422. "
                        + "Activate it with PUT /api/v1/currencies/{}/activate", code, code);
                unavailable.add(code);
            }
        }
        if (unavailable.isEmpty()) {
            log.info("Every currency with credit limits is registered and active");
        }
        return unavailable;
    }

    /**
     * Devuelve la moneda si está dada de alta y activa.
     */
    public Currency requireActive(Currency currency) {
        boolean active = currencyRepository.findByCode(currency.getCurrencyCode())
                .map(SupportedCurrency::isActive)
                .orElse(false);
        if (!active) {
            throw new CurrencyNotSupportedException(currency.getCurrencyCode());
        }
        return currency;
    }
}
