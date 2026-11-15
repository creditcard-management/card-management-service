package com.unisono.card.currency.domain.service;

import com.unisono.card.currency.domain.exception.CurrencyAlreadyExistsException;
import com.unisono.card.currency.domain.exception.CurrencyNotSupportedException;
import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.SupportedCurrency;
import com.unisono.card.currency.domain.port.input.ManageCurrenciesUseCase;
import com.unisono.card.currency.domain.port.output.CurrencyRepository;
import com.unisono.card.shared.domain.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Currency;
import java.util.List;

/**
 * Catálogo de monedas aceptadas. Implementa: ManageCurrenciesUseCase
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class CurrencyCatalogService implements ManageCurrenciesUseCase {

    private final CurrencyRepository currencyRepository;
    private final Clock clock;
    private final Currency systemCurrency;

    public CurrencyCatalogService(CurrencyRepository currencyRepository, Clock clock,
                                  @Value("${card.currency.system-currency:ARS}") String systemCurrency) {
        this.currencyRepository = currencyRepository;
        this.clock = clock;
        this.systemCurrency = CurrencyPair.currency(systemCurrency);
    }

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
        SupportedCurrency currency = get(code);
        currency.deactivate(systemCurrency, clock.instant());
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
     * Verifica que la moneda del sistema ({@code card.currency.system-currency}) esté dada de alta y activa. Si no,
     * ninguna conversión hacia la moneda de los límites funcionaría: se llama al arrancar para fallar enseguida.
     *
     * @throws IllegalStateException si la moneda no está en el catálogo o está inactiva
     */
    public void verifySystemCurrency() {
        String code = systemCurrency.getCurrencyCode();
        SupportedCurrency currency = currencyRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException(String.format(
                        "System currency %s (card.currency.system-currency) is not registered in the currencies "
                                + "table: apply migration V19 or register it", code)));
        if (!currency.isActive()) {
            throw new IllegalStateException(String.format(
                    "System currency %s (card.currency.system-currency) is inactive: set active = true in the "
                            + "currencies table", code));
        }
        log.info("System currency {} is registered and active", code);
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
