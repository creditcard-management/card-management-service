package com.unisono.card.currency.application.dto.response;

import java.time.Instant;

public record CurrencyResponse(
        String code,
        String name,
        int fractionDigits,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {}
