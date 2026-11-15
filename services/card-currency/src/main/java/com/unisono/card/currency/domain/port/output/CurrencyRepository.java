package com.unisono.card.currency.domain.port.output;

import com.unisono.card.currency.domain.model.SupportedCurrency;

import java.util.List;
import java.util.Optional;

public interface CurrencyRepository {

    SupportedCurrency save(SupportedCurrency currency);

    Optional<SupportedCurrency> findByCode(String code);

    boolean existsByCode(String code);

    List<SupportedCurrency> findAll(boolean activeOnly);
}
