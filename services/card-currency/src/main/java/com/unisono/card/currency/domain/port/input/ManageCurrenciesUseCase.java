package com.unisono.card.currency.domain.port.input;

import com.unisono.card.currency.domain.model.SupportedCurrency;

import java.util.List;

/**
 * Use Case: catálogo de monedas aceptadas.
 */
public interface ManageCurrenciesUseCase {

    SupportedCurrency register(String code, String name);

    SupportedCurrency activate(String code);

    SupportedCurrency deactivate(String code);

    SupportedCurrency get(String code);

    List<SupportedCurrency> list(boolean activeOnly);
}
