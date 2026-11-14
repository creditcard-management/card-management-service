package com.unisono.card.lifecycle.infrastructure.adapter.input.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request para desbloquear una tarjeta")
public class UnblockCardRequest {

    @Schema(description = "Razón del desbloqueo", example = "El cliente recuperó la tarjeta")
    @NotBlank(message = "La razón es obligatoria")
    private String reason;

    @Schema(description = "Usuario que desbloquea", example = "usuario@email.com")
    @NotBlank(message = "El usuario que desbloquea es obligatorio")
    private String unblockedBy;
}
