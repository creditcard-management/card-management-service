package com.unisono.card.events.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Evento publicado cuando baja la posición neta de una tarjeta (consumido − saldo a favor):
 * baja lo consumido o se acredita saldo a favor (credit-limit.released)
 */
@Getter
@ToString(callSuper = true)
public class CreditLimitReleasedEvent extends DomainEvent {

    private final String cardId;
    private final String creditLimitId;
    /** Id de la operación de crédito; nulo en el pago del resumen, que no tiene */
    private final String operationId;
    /** PAYMENT (pago del resumen), REFUND (devolución) o CONSUMPTION_CANCELLED (se deshizo un consumo) */
    private final String operationType;
    /** Monto liberado por este movimiento */
    private final BigDecimal amount;
    /** Crédito consumido total después del movimiento */
    private final BigDecimal consumedAmount;
    /** Saldo a favor después del movimiento (mayor a cero solo si consumedAmount es cero) */
    private final BigDecimal creditBalanceAmount;
    /** Crédito disponible después del movimiento */
    private final BigDecimal availableAmount;
    private final String currency;

    @JsonCreator
    public CreditLimitReleasedEvent(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("occurredOn") LocalDateTime occurredOn,
            @JsonProperty("cardId") String cardId,
            @JsonProperty("creditLimitId") String creditLimitId,
            @JsonProperty("operationId") String operationId,
            @JsonProperty("operationType") String operationType,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("consumedAmount") BigDecimal consumedAmount,
            @JsonProperty("creditBalanceAmount") BigDecimal creditBalanceAmount,
            @JsonProperty("availableAmount") BigDecimal availableAmount,
            @JsonProperty("currency") String currency
    ) {
        super(eventId, occurredOn);
        this.cardId = cardId;
        this.creditLimitId = creditLimitId;
        this.operationId = operationId;
        this.operationType = operationType;
        this.amount = amount;
        this.consumedAmount = consumedAmount;
        this.creditBalanceAmount = creditBalanceAmount;
        this.availableAmount = availableAmount;
        this.currency = currency;
    }

    public CreditLimitReleasedEvent(
            String cardId,
            String creditLimitId,
            String operationId,
            String operationType,
            BigDecimal amount,
            BigDecimal consumedAmount,
            BigDecimal creditBalanceAmount,
            BigDecimal availableAmount,
            String currency
    ) {
        super();
        this.cardId = cardId;
        this.creditLimitId = creditLimitId;
        this.operationId = operationId;
        this.operationType = operationType;
        this.amount = amount;
        this.consumedAmount = consumedAmount;
        this.creditBalanceAmount = creditBalanceAmount;
        this.availableAmount = availableAmount;
        this.currency = currency;
    }
}
