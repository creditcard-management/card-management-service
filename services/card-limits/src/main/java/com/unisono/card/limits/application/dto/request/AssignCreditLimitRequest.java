package com.unisono.card.limits.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO para asignar límite de crédito
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Solicitud para asignar límite de crédito a una tarjeta")
public class AssignCreditLimitRequest {

    @NotBlank(message = "El ID de la tarjeta es obligatorio")
    @Schema(description = "ID de la tarjeta", example = "550e8400-e29b-41d4-a716-446655440000")
    private String cardId;

    @NotNull(message = "El límite de compras es obligatorio")
    @DecimalMin(value = "0.01", message = "El límite de compras debe ser mayor a cero")
    @Schema(description = "Límite de compras. El rango depende de la moneda (card.limits.policies; ARS: 2.500.000 a 25.000.000)",
            example = "5000000.00")
    private BigDecimal purchaseLimitAmount;

    @NotBlank(message = "La moneda es obligatoria")
    @Size(min = 3, max = 3, message = "La moneda debe ser un código ISO de 3 letras")
    @Schema(description = "Código de moneda ISO", example = "ARS")
    private String currency;

    @DecimalMin(value = "0.0", message = "El porcentaje debe ser al menos 0")
    @DecimalMax(value = "1.0", message = "El porcentaje no puede exceder 1 (100%)")
    @Schema(description = "Porcentaje del límite de compras para adelantos (0 = sin adelantos)", example = "0.30", defaultValue = "0.30")
    @Builder.Default
    private Double cashAdvancePercentage = 0.30; // 30% por defecto

    @DecimalMin(value = "0.01", message = "El límite diario debe ser mayor a cero")
    @Schema(description = "Límite diario de transacciones (opcional; por defecto el de la moneda, ARS 1.250.000)", example = "1250000.00")
    private BigDecimal dailyLimitAmount;

    @DecimalMin(value = "0.01", message = "El límite mensual debe ser mayor a cero")
    @Schema(description = "Límite mensual de transacciones (opcional; por defecto el de la moneda, ARS 1.250.000)", example = "1250000.00")
    private BigDecimal monthlyLimitAmount;

    @NotBlank(message = "El usuario que asigna el límite es obligatorio")
    @Schema(description = "Usuario que asigna el límite", example = "admin@unisono.com")
    private String assignedBy;
}
