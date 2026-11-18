package com.unisono.card.limits.domain.service;

import com.unisono.card.events.config.KafkaTopics;
import com.unisono.card.events.domain.CreditLimitAssignedEvent;
import com.unisono.card.events.domain.CreditLimitConsumedEvent;
import com.unisono.card.events.domain.CreditLimitReleasedEvent;
import com.unisono.card.events.domain.CreditLimitUpdatedEvent;
import com.unisono.card.limits.domain.model.CreditLimit;
import com.unisono.card.limits.domain.model.CreditLimitHistory;
import com.unisono.card.limits.domain.port.output.EventOutboxPort;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Traduce los cambios de un límite de crédito a eventos credit-limit.* y los encola en el outbox.
 *
 * Se invoca dentro de la transacción del caso de uso: el evento se confirma junto con el cambio.
 * La key de cada mensaje es el cardId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditLimitEventEmitter {

    /** Qué movió el crédito consumido (operationType de los eventos consumed/released). */
    public enum Movement {
        PURCHASE, CASH_ADVANCE, PAYMENT, REFUND, CONSUMPTION_CANCELLED, REFUND_CANCELLED, PAYMENT_CANCELLED
    }

    private final EventOutboxPort eventOutbox;

    public void assigned(CreditLimit limit, String assignedBy) {
        String cardId = limit.getCardId().getValue();
        eventOutbox.enqueue(KafkaTopics.CREDIT_LIMIT_ASSIGNED, cardId, new CreditLimitAssignedEvent(
                cardId,
                limit.getId(),
                limit.getPurchaseLimit().getAmount().getAmount(),
                limit.getCashAdvanceLimit().getAmount().getAmount(),
                limit.getDailyLimit().getAmount().getAmount(),
                limit.getMonthlyLimit().getAmount().getAmount(),
                limit.getCurrencyCode(),
                assignedBy
        ));
    }

    /**
     * Un evento por cada cambio de límite registrado en el historial
     */
    public void limitChanged(CreditLimit limit, CreditLimitHistory change) {
        String cardId = limit.getCardId().getValue();
        eventOutbox.enqueue(KafkaTopics.CREDIT_LIMIT_UPDATED, cardId, new CreditLimitUpdatedEvent(
                cardId,
                limit.getId(),
                change.getLimitType().name(),
                amountOf(change.getPreviousValue()),
                amountOf(change.getNewValue()),
                limit.getCurrencyCode(),
                change.getReason(),
                change.getChangedBy()
        ));
    }

    /**
     * Publica consumed o released según cómo cambió la posición neta (consumido − saldo a favor): una compra
     * pagada con saldo a favor consume aunque no suba lo consumido, y un pago de más libera aunque lo consumido
     * ya esté en cero. Sin cambio no publica nada.
     *
     * @param netDebtBefore posición neta antes de la operación ({@link CreditLimit#getNetDebt()})
     */
    public void creditMoved(CreditLimit limit, Money netDebtBefore, Movement movement, String operationId) {
        BigDecimal before = netDebtBefore.getAmount();
        BigDecimal after = limit.getNetDebt().getAmount();
        int direction = after.compareTo(before);
        if (direction == 0) {
            log.debug("Credit limit {}: {} did not change the credit position, no event", limit.getId(), movement);
            return;
        }

        String cardId = limit.getCardId().getValue();
        BigDecimal consumed = limit.getConsumedCredit().getAmount().getAmount();
        BigDecimal balance = limit.getCreditBalance().getAmount().getAmount();
        BigDecimal available = limit.getAvailableCredit().getAmount().getAmount();
        String currency = limit.getCurrencyCode();
        if (direction > 0) {
            eventOutbox.enqueue(KafkaTopics.CREDIT_LIMIT_CONSUMED, cardId, new CreditLimitConsumedEvent(
                    cardId, limit.getId(), operationId, movement.name(), after.subtract(before), consumed, balance,
                    available, currency));
        } else {
            eventOutbox.enqueue(KafkaTopics.CREDIT_LIMIT_RELEASED, cardId, new CreditLimitReleasedEvent(
                    cardId, limit.getId(), operationId, movement.name(), before.subtract(after), consumed, balance,
                    available, currency));
        }
    }

    private static BigDecimal amountOf(Money money) {
        return money != null ? money.getAmount() : null;
    }
}
