package com.unisono.card.limits.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO para liberar crédito (pago)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Solicitud para liberar crédito después de un pago")
public class ReleaseCreditRequest {

    @NotNull(message = "El monto del pago es obligatorio")
    @DecimalMin(value = "0.01", message = "El monto debe ser mayor a cero")
    @Schema(description = "Monto del pago", example = "10000.00")
    private BigDecimal paymentAmount;

    @NotBlank(message = "La moneda es obligatoria")
    @Schema(description = "Código de moneda ISO", example = "ARS")
    private String currency;

    @NotBlank(message = "La descripción es obligatoria")
    @Schema(description = "Descripción del pago", example = "Pago de resumen")
    private String description;

    @Size(max = 64, message = "El id de operación no puede superar 64 caracteres")
    @Schema(description = "Id de la operación, opcional. Con un id la operación es idempotente. Si no viene, card-limits genera uno "
            + "(auto-…) y lo devuelve en la respuesta. Con cualquiera de los dos se puede cancelar con "
            + "POST /{creditLimitId}/operations/{operationId}/cancel",
            example = "pago-2026-11-0001")
    private String operationId;
}
