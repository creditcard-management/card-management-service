package com.unisono.card.lifecycle.infrastructure.adapter.output.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity JPA para state_transitions
 */
@Entity
@Table(name = "state_transitions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StateTransitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_id", nullable = false, length = 36)
    private String cardId;

    @Column(name = "from_state", length = 20)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 20)
    private String toState;

    @Column(name = "event", nullable = false, length = 20)
    private String event;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "performed_by", nullable = false, length = 100)
    private String performedBy;

    /**
     * Cuándo ocurrió la transición: el changedAt del evento card.* (o el occurredOn de card.created),
     * no cuándo se consumió el mensaje. Sin @CreationTimestamp, que lo pisaba con la hora del insert y
     * desordenaba el historial (una transición fallida quedaba antes del cambio que la causó).
     */
    @Column(name = "transitioned_at", nullable = false)
    private LocalDateTime transitionedAt;

    @Column(name = "successful", nullable = false)
    @Builder.Default
    private Boolean successful = true;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * eventId del evento card.* que originó la transición (único): evita registrarla dos veces.
     * Nulo en las transiciones fallidas, que no tienen evento.
     */
    @Column(name = "event_id", length = 36, unique = true)
    private String eventId;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata; // Para guardar datos adicionales en formato JSON

    /** Solo si nadie la informó (por ejemplo, un evento viejo sin fecha): nunca pisa la del evento. */
    @PrePersist
    void defaultTransitionedAt() {
        if (transitionedAt == null) {
            transitionedAt = LocalDateTime.now();
        }
    }
}
