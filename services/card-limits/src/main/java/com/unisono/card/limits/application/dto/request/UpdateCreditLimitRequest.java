package com.unisono.card.limits.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO para actualizar límite de crédito
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Solicitud para actualizar límite de crédito")
public class UpdateCreditLimitRequest {

    @NotNull(message = "El nuevo monto es obligatorio")
    // increase / decrease exigen > 0 (lo valida el dominio); en update-cash-advance-limit, 0 = sin adelantos
    @DecimalMin(value = "0.00", message = "El monto no puede ser negativo")
    @Schema(description = "Diferencia (increase / decrease) o nuevo tope de adelantos (0 = sin adelantos)",
            example = "75000.00")
    private BigDecimal amount;

    @NotBlank(message = "La moneda es obligatoria")
    @Schema(description = "Código de moneda ISO", example = "ARS")
    private String currency;

    @NotBlank(message = "La razón del cambio es obligatoria")
    @Schema(description = "Razón del cambio", example = "Aumento por buen comportamiento crediticio")
    private String reason;

    @NotBlank(message = "El usuario que realiza el cambio es obligatorio")
    @Schema(description = "Usuario que realiza el cambio", example = "admin@unisono.com")
    private String changedBy;
}
