package com.unisono.card.currency.application.dto.response;

import java.math.BigDecimal;

/**
 * {@code rateId} es nulo cuando las monedas son iguales. {@code inverse} = se usó la cotización destino/origen.
 */
public record ConversionResponse(
        BigDecimal sourceAmount,
        String sourceCurrency,
        BigDecimal targetAmount,
        String targetCurrency,
        BigDecimal appliedRate,
        String rateId,
        boolean inverse
) {}
