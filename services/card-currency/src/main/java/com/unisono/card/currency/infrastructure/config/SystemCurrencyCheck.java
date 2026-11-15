package com.unisono.card.currency.infrastructure.config;

import com.unisono.card.currency.domain.service.CurrencyCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Al arrancar, verifica que la moneda del sistema esté dada de alta y activa. Si no, la aplicación no arranca:
 * mejor fallar acá que rechazar con 422 cada pago en otra moneda.
 * <p>
 * Que coincida con la moneda de los límites de card-limits (ARS, fija en sus value objects) no se puede verificar
 * desde acá: se configura por separado.
 */
@Component
@RequiredArgsConstructor
public class SystemCurrencyCheck implements ApplicationRunner {

    private final CurrencyCatalogService currencyCatalog;

    @Override
    public void run(ApplicationArguments args) {
        currencyCatalog.verifySystemCurrency();
    }
}
