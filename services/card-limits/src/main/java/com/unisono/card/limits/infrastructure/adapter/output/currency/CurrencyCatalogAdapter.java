package com.unisono.card.limits.infrastructure.adapter.output.currency;

import com.unisono.card.limits.domain.port.output.CurrencyCatalogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lee {@code currencies} de card-currency en la base compartida (solo lectura, como {@code cards}).
 * <p>
 * Usa FOR SHARE: la fila queda bloqueada hasta el fin de la transacción. Si card-currency está desactivando la
 * moneda (FOR UPDATE), la asignación espera y ve el resultado; si la asignación llegó primero, la baja espera y
 * ve el límite nuevo.
 */
@Component
@RequiredArgsConstructor
public class CurrencyCatalogAdapter implements CurrencyCatalogPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isActive(String currencyCode) {
        List<Boolean> active = jdbcTemplate.queryForList(
                "SELECT active FROM currencies WHERE code = ? FOR SHARE", Boolean.class, currencyCode);
        return !active.isEmpty() && Boolean.TRUE.equals(active.get(0));
    }
}
