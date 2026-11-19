package com.unisono.card.currency.infrastructure.adapter.output.creditlimit;

import com.unisono.card.currency.domain.port.output.CreditLimitCurrencyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * Lee {@code credit_limits} de card-limits en la base compartida (solo lectura, como card-limits lee {@code cards}).
 * La moneda de un límite es la de su límite de compras; los límites eliminados (soft delete) no cuentan.
 */
@Component
@RequiredArgsConstructor
public class CreditLimitCurrencyAdapter implements CreditLimitCurrencyPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean hasCreditLimitsIn(String currencyCode) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM credit_limits WHERE purchase_limit_currency = ? AND deleted = false)",
                Boolean.class, currencyCode);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Set<String> currenciesWithCreditLimits() {
        return new TreeSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT purchase_limit_currency FROM credit_limits WHERE deleted = false", String.class));
    }
}
