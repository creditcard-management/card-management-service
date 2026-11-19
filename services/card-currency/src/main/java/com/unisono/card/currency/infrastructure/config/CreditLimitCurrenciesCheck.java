package com.unisono.card.currency.infrastructure.config;

import com.unisono.card.currency.domain.service.CurrencyCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Al arrancar, avisa (WARN) si hay límites de crédito en monedas que no están dadas de alta o están inactivas. No
 * impide el arranque: es un problema de datos (límites anteriores a este control), y los pagos en otras monedas
 * de esas tarjetas fallan con 422 hasta que se corrija el catálogo.
 */
@Component
@RequiredArgsConstructor
public class CreditLimitCurrenciesCheck implements ApplicationRunner {

    private final CurrencyCatalogService currencyCatalog;

    @Override
    public void run(ApplicationArguments args) {
        currencyCatalog.warnAboutCreditLimitsInUnavailableCurrencies();
    }
}
