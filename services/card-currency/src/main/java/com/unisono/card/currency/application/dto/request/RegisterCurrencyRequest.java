package com.unisono.card.currency.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterCurrencyRequest(
        @Schema(example = "BRL", description = "Código ISO 4217")
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "Currency must be 3 letters (ISO 4217)")
        String code,

        @Schema(example = "Real brasileño")
        @NotBlank @Size(max = 100)
        String name
) {}
