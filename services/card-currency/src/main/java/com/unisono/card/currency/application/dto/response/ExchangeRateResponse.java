package com.unisono.card.currency.application.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeRateResponse(
        String rateId,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        Instant validFrom,
        Instant validTo,
        String source,
        Instant createdAt
) {}
