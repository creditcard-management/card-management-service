package com.unisono.card.currency.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public record PublishExchangeRateRequest(
        @Schema(example = "USD", description = "Moneda base: 1 unidad de base vale `rate` unidades de quote")
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "Currency must be 3 letters (ISO 4217)")
        String baseCurrency,

        @Schema(example = "ARS")
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "Currency must be 3 letters (ISO 4217)")
        String quoteCurrency,

        @Schema(example = "1150.50")
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 8)
        BigDecimal rate,

        @Schema(description = "Inicio de vigencia (ISO-8601). Si se omite, rige desde ahora. No puede ser pasado.",
                example = "2026-11-16T00:00:00Z")
        Instant effectiveFrom
) {}
