package com.unisono.card.data.infrastructure.adapter.input.rest;

import com.unisono.card.data.application.dto.request.ActivateCardRequest;
import com.unisono.card.data.application.dto.request.CancelCardRequest;
import com.unisono.card.data.application.dto.request.CreateCardRequest;
import com.unisono.card.data.application.dto.request.IssueCardRequest;
import com.unisono.card.data.application.dto.request.UpdateCardStatusRequest;
import com.unisono.card.data.application.dto.response.CardDetailResponse;
import com.unisono.card.data.application.dto.response.CardResponse;
import com.unisono.card.data.application.mapper.CardDtoMapper;
import com.unisono.card.data.domain.model.Card;
import com.unisono.card.data.domain.port.input.CreateCardUseCase;
import com.unisono.card.data.domain.port.input.GetCardUseCase;
import com.unisono.card.data.domain.port.input.UpdateCardStatusUseCase;
import com.unisono.card.data.domain.service.CardManagementService;
import com.unisono.card.shared.domain.exception.NotFoundException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.ClientId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST Controller para gestión de tarjetas
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
@Tag(name = "Cards", description = "Endpoints para gestión de tarjetas")
public class CardController {

    private final CardManagementService cardManagementService;
    private final CreateCardUseCase createCardUseCase;
    private final GetCardUseCase getCardUseCase;
    private final UpdateCardStatusUseCase updateCardStatusUseCase;
    private final CardDtoMapper mapper;

    /**
     * Crear una nueva tarjeta
     */
    @PostMapping
    @Operation(summary = "Crear nueva tarjeta", description = "Crea una solicitud de tarjeta en estado REQUESTED")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Tarjeta creada exitosamente"),
            @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos"),
            @ApiResponse(responseCode = "500", description = "Error interno del servidor")
    })
    public ResponseEntity<CardResponse> createCard(
            @Valid @RequestBody CreateCardRequest request
    ) {
        log.info("Creating card for client: {}", request.getClientId());

        CreateCardUseCase.CreateCardCommand command = mapper.toCommand(request);
        Card card = createCardUseCase.execute(command);

        CardResponse response = mapper.toResponse(card);

        log.info("Card created successfully: {}", card.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Obtener tarjeta por ID
     */
    @GetMapping("/{cardId}")
    @Operation(summary = "Obtener tarjeta por ID", description = "Retorna información detallada de una tarjeta")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tarjeta encontrada"),
            @ApiResponse(responseCode = "404", description = "Tarjeta no encontrada")
    })
    public ResponseEntity<CardDetailResponse> getCardById(
            @Parameter(description = "ID de la tarjeta")
            @PathVariable String cardId
    ) {
        log.info("Getting card by ID: {}", cardId);

        Card card = getCardUseCase.findById(CardId.of(cardId))
                .orElseThrow(() -> new NotFoundException("Card", cardId));

        CardDetailResponse response = mapper.toDetailResponse(card);

        return ResponseEntity.ok(response);
    }

    /**
     * Obtener todas las tarjetas de un cliente
     */
    @GetMapping("/client/{clientId}")
    @Operation(summary = "Obtener tarjetas por cliente", description = "Retorna todas las tarjetas de un cliente")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tarjetas encontradas"),
            @ApiResponse(responseCode = "404", description = "Cliente no encontrado")
    })
    public ResponseEntity<List<CardResponse>> getCardsByClient(
            @Parameter(description = "ID del cliente")
            @PathVariable String clientId
    ) {
        log.info("Getting cards for client: {}", clientId);

        List<Card> cards = getCardUseCase.findByClientId(ClientId.of(clientId));

        List<CardResponse> response = cards.stream()
                .map(mapper::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Buscar tarjeta por últimos 4 dígitos
     */
    @GetMapping("/search")
    @Operation(summary = "Buscar tarjeta por últimos 4 dígitos",
            description = "Retorna una tarjeta específica del cliente por sus últimos 4 dígitos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tarjeta encontrada"),
            @ApiResponse(responseCode = "404", description = "Tarjeta no encontrada")
    })
    public ResponseEntity<CardDetailResponse> searchCard(
            @Parameter(description = "Últimos 4 dígitos de la tarjeta", example = "1234")
            @RequestParam String lastFourDigits,

            @Parameter(description = "ID del cliente")
            @RequestParam String clientId
    ) {
        log.info("Searching card with last 4 digits: {} for client: {}", lastFourDigits, clientId);

        Card card = getCardUseCase.findByLastFourDigits(lastFourDigits, ClientId.of(clientId))
                .orElseThrow(() -> new NotFoundException(
                        "Card",
                        String.format("with last 4 digits %s for client %s", lastFourDigits, clientId)
                ));

        CardDetailResponse response = mapper.toDetailResponse(card);

        return ResponseEntity.ok(response);
    }

    /**
     * Cambiar el estado de una tarjeta (usado por card-lifecycle)
     */
    @PutMapping("/{cardId}/status")
    @Operation(summary = "Cambiar estado de tarjeta",
            description = "Cambia el estado validando que la transición esté permitida")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado actualizado"),
            @ApiResponse(responseCode = "400", description = "Estado ausente o inexistente"),
            @ApiResponse(responseCode = "404", description = "Tarjeta no encontrada"),
            @ApiResponse(responseCode = "422", description = "Transición de estado no permitida")
    })
    public ResponseEntity<Void> updateStatus(
            @Parameter(description = "ID de la tarjeta")
            @PathVariable String cardId,

            @Valid @RequestBody UpdateCardStatusRequest request
    ) {
        log.info("Actualizando estado de tarjeta: {} -> {}", cardId, request.getStatus());

        updateCardStatusUseCase.execute(new UpdateCardStatusUseCase.UpdateStatusCommand(
                CardId.of(cardId),
                request.getStatus(),
                request.getReason(),
                request.getPerformedBy()
        ));

        return ResponseEntity.ok().build();
    }

    /**
     * Cancelar tarjeta (usado por card-lifecycle)
     */
    @PostMapping("/{cardId}/cancel")
    @Operation(summary = "Cancelar tarjeta",
            description = "Cancela la tarjeta y registra motivo y fecha de cancelación")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tarjeta cancelada"),
            @ApiResponse(responseCode = "400", description = "Razón ausente"),
            @ApiResponse(responseCode = "404", description = "Tarjeta no encontrada"),
            @ApiResponse(responseCode = "422", description = "Transición a CANCELLED no permitida")
    })
    public ResponseEntity<CardResponse> cancelCard(
            @Parameter(description = "ID de la tarjeta")
            @PathVariable String cardId,

            @Valid @RequestBody CancelCardRequest request
    ) {
        log.info("Cancelando tarjeta: {}", cardId);

        Card card = cardManagementService.cancel(CardId.of(cardId), request.getReason(), request.getPerformedBy());

        return ResponseEntity.ok(mapper.toResponse(card));
    }

    /**
     * Emitir tarjeta (usado por card-lifecycle)
     */
    @PostMapping("/{cardId}/issue")
    @Operation(summary = "Emitir tarjeta",
            description = "Carga PAN, CVV, PIN y vencimiento y pasa la tarjeta a ISSUED en una sola transacción. "
                    + "El PAN y el CVV se cifran y el PIN se hashea al persistir")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tarjeta emitida"),
            @ApiResponse(responseCode = "400", description = "Datos ausentes o inválidos"),
            @ApiResponse(responseCode = "404", description = "Tarjeta no encontrada"),
            @ApiResponse(responseCode = "409", description = "La tarjeta cambió en paralelo"),
            @ApiResponse(responseCode = "422", description = "La tarjeta no está aprobada")
    })
    public ResponseEntity<CardResponse> issueCard(
            @Parameter(description = "ID de la tarjeta")
            @PathVariable String cardId,

            @Valid @RequestBody IssueCardRequest request
    ) {
        log.info("Emitiendo tarjeta: {}", cardId);

        Card card = cardManagementService.issue(
                CardId.of(cardId),
                request.getCardNumber(),
                request.getCvv(),
                request.getPin(),
                request.getExpirationMonth(),
                request.getExpirationYear(),
                request.getReason(),
                request.getPerformedBy()
        );

        return ResponseEntity.ok(mapper.toResponse(card));
    }

    /**
     * Activar tarjeta (usado por card-lifecycle, después de validar el PIN)
     */
    @PostMapping("/{cardId}/activate")
    @Operation(summary = "Activar tarjeta",
            description = "Pasa la tarjeta de ISSUED a ACTIVE y registra la fecha de activación en una sola transacción")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tarjeta activada"),
            @ApiResponse(responseCode = "404", description = "Tarjeta no encontrada"),
            @ApiResponse(responseCode = "409", description = "La tarjeta cambió en paralelo"),
            @ApiResponse(responseCode = "422", description = "La tarjeta no está emitida")
    })
    public ResponseEntity<CardResponse> activateCard(
            @Parameter(description = "ID de la tarjeta")
            @PathVariable String cardId,

            @Valid @RequestBody(required = false) ActivateCardRequest request
    ) {
        log.info("Activando tarjeta: {}", cardId);

        Card card = cardManagementService.activate(
                CardId.of(cardId),
                request != null ? request.getReason() : null,
                request != null ? request.getPerformedBy() : null
        );

        return ResponseEntity.ok(mapper.toResponse(card));
    }
}
