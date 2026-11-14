package com.unisono.card.lifecycle.domain.port.output;

import com.unisono.card.shared.domain.valueobject.*;

/**
 * Puerto de salida hacia card-data (dueño de la tarjeta).
 *
 * Las implementaciones traducen los errores HTTP a excepciones de dominio:
 * 404 → NotFoundException, 400 → ValidationException,
 * 409/422 → BusinessRuleException, resto → CardDataIntegrationException.
 */
public interface CardDataClient {

    CardStatus getStatus(CardId cardId);

    /** card-data publica el evento del cambio con {@code reason} y {@code performedBy}. */
    void updateStatus(CardId cardId, String status, String reason, String performedBy);

    /** Bloqueo temporal (POST /block): card-data registra block_reason y blocked_at. */
    void block(CardId cardId, String reason, String performedBy);

    /** Bloqueo por fraude (POST /block-fraud). */
    void blockForFraud(CardId cardId, String reason, String performedBy);

    /** Desbloqueo (POST /unblock): card-data vuelve a ACTIVE y limpia block_reason, blocked_at e intentos de PIN. */
    void unblock(CardId cardId, String reason, String performedBy);

    /** Cancelación (POST /cancel): card-data registra cancelled_reason y cancelled_at. */
    void cancel(CardId cardId, String reason, String performedBy);

    /**
     * Emisión (POST /issue): card-data carga PAN, CVV, PIN y vencimiento y pasa a ISSUED en una sola
     * transacción, así una falla no deja la tarjeta con datos pero sin emitir.
     */
    void issue(
            CardId cardId,
            CardNumber cardNumber,
            CVV cvv,
            PIN pin,
            ExpirationDate expirationDate,
            String reason,
            String performedBy
    );

    /** Activación (POST /activate): card-data pasa a ACTIVE y registra activated_at en una sola transacción. */
    void activate(CardId cardId, String reason, String performedBy);

    /**
     * Verifica el PIN contra el hash guardado en card-data.
     * Un PIN incorrecto suma un intento fallido en card-data.
     */
    boolean validatePin(CardId cardId, PIN pin);
}
