package com.unisono.card.currency.domain.port.output;

import com.unisono.card.currency.domain.model.SupportedCurrency;

import java.util.List;
import java.util.Optional;

public interface CurrencyRepository {

    SupportedCurrency save(SupportedCurrency currency);

    Optional<SupportedCurrency> findByCode(String code);

    /**
     * Igual que {@link #findByCode}, bloqueando la fila hasta el fin de la transacción (FOR UPDATE). card-limits
     * lee la misma fila con FOR SHARE al asignar un límite: así una baja y una asignación en esa moneda no se cruzan.
     */
    Optional<SupportedCurrency> findByCodeForUpdate(String code);

    boolean existsByCode(String code);

    List<SupportedCurrency> findAll(boolean activeOnly);
}
