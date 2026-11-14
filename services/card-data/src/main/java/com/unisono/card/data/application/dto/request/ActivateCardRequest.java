package com.unisono.card.data.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO para activar una tarjeta (usado por card-lifecycle). El PIN se valida antes,
 * con POST /{cardId}/validate-pin.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Activación de una tarjeta emitida")
public class ActivateCardRequest {

    @Size(max = 500, message = "La razón no puede exceder 500 caracteres")
    @Schema(description = "Motivo de la activación; se publica en el evento", example = "Cliente activó vía app")
    private String reason;

    @Size(max = 100, message = "El autor no puede exceder 100 caracteres")
    @Schema(description = "Quién ejecuta el cambio; se publica en el evento", example = "cliente@email.com")
    private String performedBy;
}
