package com.unisono.card.currency.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public record ConvertAmountRequest(
        @Schema(example = "100.00")
        @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2)
        BigDecimal amount,

        @Schema(example = "USD")
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "Currency must be 3 letters (ISO 4217)")
        String fromCurrency,

        @Schema(example = "ARS")
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "Currency must be 3 letters (ISO 4217)")
        String toCurrency,

        @Schema(description = "Instante de la cotización (ISO-8601). Si se omite, la vigente ahora.")
        Instant at
) {}
