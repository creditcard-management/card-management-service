package com.unisono.card.limits.domain.service;

import com.unisono.card.limits.domain.model.TestLimitPolicies;
import com.unisono.card.limits.domain.exception.CreditOperationCancelledException;
import com.unisono.card.limits.domain.model.CreditLimit;
import com.unisono.card.limits.domain.model.CreditLimitHistory;
import com.unisono.card.limits.domain.model.CreditOperation;
import com.unisono.card.limits.domain.port.input.RecalculateAvailableCreditUseCase.CancelOperationCommand;
import com.unisono.card.limits.domain.port.input.RecalculateAvailableCreditUseCase.CancelOperationResult;
import com.unisono.card.limits.domain.port.input.RecalculateAvailableCreditUseCase.CancelOutcome;
import com.unisono.card.limits.domain.port.input.RecalculateAvailableCreditUseCase.ConsumeCreditCommand;
import com.unisono.card.limits.domain.port.input.RecalculateAvailableCreditUseCase.RestoreCreditCommand;
import com.unisono.card.limits.domain.port.output.CardValidationPort;
import com.unisono.card.limits.domain.port.output.CreditLimitRepository;
import com.unisono.card.limits.domain.port.output.CreditOperationRepository;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import com.unisono.card.events.config.KafkaTopics;
import com.unisono.card.events.domain.CreditLimitConsumedEvent;
import com.unisono.card.events.domain.CreditLimitReleasedEvent;
import com.unisono.card.events.domain.DomainEvent;
import com.unisono.card.limits.domain.port.input.RecalculateAvailableCreditUseCase.ReleaseCreditCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Operaciones de crédito con operationId: idempotencia, cancelación exacta y anulación antes de llegar,
 * y los eventos credit-limit.consumed / credit-limit.released que publica cada una
 */
class CreditOperationIdempotencyTest {

    private static final CardId CARD_ID = CardId.of("550e8400-e29b-41d4-a716-446655440000");

    private final InMemoryCreditLimits creditLimits = new InMemoryCreditLimits();
    private final InMemoryOperations operations = new InMemoryOperations();
    /** Eventos encolados en el outbox (topic → evento) */
    private final List<Map.Entry<String, DomainEvent>> outbox = new java.util.ArrayList<>();
    private final CardValidationPort cardValidationPort = mock(CardValidationPort.class);
    private CreditLimitManagementService service;
    private CreditLimit creditLimit;

    @BeforeEach
    void setUp() {
        when(cardValidationPort.isCardActive(CARD_ID)).thenReturn(true);
        service = new CreditLimitManagementService(creditLimits, cardValidationPort, operations,
                new CreditLimitEventEmitter((topic, key, event) -> outbox.add(Map.entry(topic, event))),
                TestLimitPolicies.port(), currency -> true);
        creditLimit = creditLimits.save(CreditLimit.assign(CARD_ID, Money.ofARS(500_000), 0.3, TestLimitPolicies.ars(), "test"));
    }

    @Test
    void shouldApplyAConsumptionOnlyOnceForTheSameOperationId() {
        service.consumeForPurchase(consume("op-1", 20_000));
        service.consumeForPurchase(consume("op-1", 20_000));

        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(20_000));
        CreditOperation operation = operations.findById("op-1").orElseThrow();
        assertThat(operation.getStatus()).isEqualTo(CreditOperation.Status.APPLIED);
        assertThat(operation.getType()).isEqualTo(CreditOperation.Type.CONSUME_PURCHASE);
        assertThat(operation.getCreditDelta()).isEqualTo(Money.ofARS(20_000));
        assertThat(operation.getDailyDelta()).isEqualTo(Money.ofARS(20_000));
        assertThat(operation.getMonthlyDelta()).isEqualTo(Money.ofARS(20_000));
    }

    @Test
    void shouldRecordOperationsWithoutIdUnderAGeneratedIdThatCanBeCancelled() {
        ConsumeCreditCommand first = new ConsumeCreditCommand(creditLimit.getId(), Money.ofARS(20_000), "compra");
        ConsumeCreditCommand second = new ConsumeCreditCommand(creditLimit.getId(), Money.ofARS(20_000), "compra");
        service.consumeForPurchase(first);
        service.consumeForPurchase(second);

        // Sin id del cliente no hay idempotencia: son dos operaciones, cada una con su id generado
        assertThat(first.operationId()).startsWith(CreditOperation.GENERATED_ID_PREFIX);
        assertThat(second.operationId()).startsWith(CreditOperation.GENERATED_ID_PREFIX).isNotEqualTo(first.operationId());
        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(40_000));
        assertThat(operations.store).containsOnlyKeys(first.operationId(), second.operationId());
        assertThat(operations.findById(first.operationId()).orElseThrow().getDescription()).isEqualTo("compra");

        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), first.operationId()));
        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(20_000));
    }

    @Test
    void blankOperationIdIsTreatedAsMissing() {
        assertThat(new ConsumeCreditCommand(creditLimit.getId(), Money.ofARS(1), "c", "  ").operationId())
                .startsWith(CreditOperation.GENERATED_ID_PREFIX);
        assertThat(new RestoreCreditCommand(creditLimit.getId(), Money.ofARS(1), LocalDate.now(), "r").operationId())
                .startsWith(CreditOperation.GENERATED_ID_PREFIX);
        assertThat(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(1), "p").operationId())
                .startsWith(CreditOperation.GENERATED_ID_PREFIX);
    }

    // ========================================
    // Adelantos acumulativos
    // ========================================

    @Test
    void cancellingACashAdvanceGivesBackItsCashAdvanceUsage() {
        service.consumeForCashAdvance(consume("adv-1", 20_000));
        assertThat(creditLimit.getCashAdvanceConsumed().getAmount()).isEqualTo(Money.ofARS(20_000));
        assertThat(operations.findById("adv-1").orElseThrow().getCashAdvanceDelta()).isEqualTo(Money.ofARS(20_000));

        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "adv-1"));

        assertThat(creditLimit.getConsumedCredit().getAmount().isZero()).isTrue();
        assertThat(creditLimit.getCashAdvanceConsumed().getAmount().isZero()).isTrue();
    }

    @Test
    void restoreFlaggedAsCashAdvanceGivesBackTheUsageAndCancellingItTakesItAgain() {
        service.consumeForCashAdvance(consume("adv-1", 20_000));

        service.restoreCredit(new RestoreCreditCommand(creditLimit.getId(), Money.ofARS(20_000), LocalDate.now(),
                "reverso del adelanto", "rev-1", true));
        assertThat(creditLimit.getCashAdvanceConsumed().getAmount().isZero()).isTrue();
        assertThat(operations.findById("rev-1").orElseThrow().getCashAdvanceDelta()).isEqualTo(Money.ofARS(-20_000));

        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "rev-1"));
        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(20_000));
        assertThat(creditLimit.getCashAdvanceConsumed().getAmount()).isEqualTo(Money.ofARS(20_000));
    }

    @Test
    void cancellingAPaymentGivesBackTheCashAdvancesItHadPaid() {
        service.consumeForCashAdvance(consume("adv-1", 20_000));
        service.consumeForPurchase(consume("op-1", 30_000));
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(25_000), "pago", "pay-1"));
        assertThat(creditLimit.getCashAdvanceConsumed().getAmount().isZero()).isTrue();

        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "pay-1"));

        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(50_000));
        assertThat(creditLimit.getCashAdvanceConsumed().getAmount()).isEqualTo(Money.ofARS(20_000));
    }

    // ========================================
    // Liberaciones (pago del resumen)
    // ========================================

    @Test
    void releaseIsRecordedAndAppliedOnlyOnceForTheSameOperationId() {
        service.consumeForPurchase(consume("op-1", 30_000));

        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(10_000), "pago", "pay-1"));
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(10_000), "pago", "pay-1"));

        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(20_000));
        CreditOperation payment = operations.findById("pay-1").orElseThrow();
        assertThat(payment.getType()).isEqualTo(CreditOperation.Type.RELEASE);
        assertThat(payment.getCreditDelta()).isEqualTo(Money.ofARS(-10_000));
        // El pago no toca el cupo diario ni el mensual
        assertThat(payment.getDailyDelta().isZero()).isTrue();
        assertThat(payment.getMonthlyDelta().isZero()).isTrue();
        assertThat(payment.getDescription()).isEqualTo("pago");
        assertThat(outbox).extracting(Map.Entry::getKey)
                .containsExactly(KafkaTopics.CREDIT_LIMIT_CONSUMED, KafkaTopics.CREDIT_LIMIT_RELEASED);
    }

    @Test
    void cancellingAReleaseConsumesThePaymentAgainAndPublishesPaymentCancelled() {
        service.consumeForPurchase(consume("op-1", 30_000));
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(10_000), "pago", "pay-1"));

        CancelOperationResult result = service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "pay-1"));

        assertThat(result.outcome()).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(30_000));
        assertThat(creditLimit.getDailyLimit().getConsumed()).isEqualTo(Money.ofARS(30_000));
        CreditLimitConsumedEvent undone = (CreditLimitConsumedEvent) outbox.get(outbox.size() - 1).getValue();
        assertThat(undone.getOperationType()).isEqualTo("PAYMENT_CANCELLED");
        assertThat(undone.getOperationId()).isEqualTo("pay-1");
        assertThat(undone.getAmount()).isEqualByComparingTo("10000");
    }

    @Test
    void releaseWithTheIdOfAnotherOperationTypeIsRejected() {
        service.consumeForPurchase(consume("op-1", 30_000));

        assertThatThrownBy(() -> service.releaseCredit(
                new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(10_000), "pago", "op-1")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("otro tipo");
        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(30_000));
    }

    @Test
    void voidedReleaseIsRejectedWhenItArrivesLate() {
        service.consumeForPurchase(consume("op-1", 30_000));
        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "pay-late"));

        assertThatThrownBy(() -> service.releaseCredit(
                new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(10_000), "pago", "pay-late")))
                .isInstanceOf(CreditOperationCancelledException.class);
        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(30_000));
    }

    @Test
    void shouldUndoAConsumptionExactlyAndOnlyOnce() {
        service.consumeForPurchase(consume("op-1", 20_000));

        CancelOperationResult first = service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-1"));
        CancelOperationResult second = service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-1"));

        assertThat(first.outcome()).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(second.outcome()).isEqualTo(CancelOutcome.ALREADY_CANCELLED);
        assertThat(creditLimit.getConsumedCredit().getAmount().isZero()).isTrue();
        assertThat(creditLimit.getAvailableCredit().getAmount()).isEqualTo(Money.ofARS(500_000));
        assertThat(creditLimit.getDailyLimit().getConsumed().isZero()).isTrue();
        assertThat(creditLimit.getMonthlyLimit().getConsumed().isZero()).isTrue();
        assertThat(operations.findById("op-1").orElseThrow().getStatus()).isEqualTo(CreditOperation.Status.CANCELLED);
    }

    @Test
    void shouldVoidAnOperationThatHasNotArrivedAndRejectItLater() {
        CancelOperationResult result = service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-late"));

        assertThat(result.outcome()).isEqualTo(CancelOutcome.VOIDED);
        assertThatThrownBy(() -> service.consumeForPurchase(consume("op-late", 20_000)))
                .isInstanceOf(CreditOperationCancelledException.class);
        assertThat(creditLimit.getConsumedCredit().getAmount().isZero()).isTrue();
    }

    @Test
    void shouldUndoARestorationByConsumingBackExactlyWhatWasRestored() {
        service.consumeForPurchase(consume("op-1", 20_000));
        creditLimit.releaseCredit(Money.ofARS(15_000)); // pago del resumen: consumido = 5.000

        // Restaura 20.000 pero lo consumido solo baja 5.000 (no hay saldo a favor); el cupo diario baja 20.000
        service.restoreCredit(new RestoreCreditCommand(creditLimit.getId(), Money.ofARS(20_000), LocalDate.now(),
                "reverso", "op-2"));
        assertThat(creditLimit.getConsumedCredit().getAmount().isZero()).isTrue();

        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-2"));

        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(5_000));
        assertThat(creditLimit.getDailyLimit().getConsumed()).isEqualTo(Money.ofARS(20_000));
        assertThat(creditLimit.getMonthlyLimit().getConsumed()).isEqualTo(Money.ofARS(20_000));
    }

    @Test
    void shouldUndoARestorationEvenIfTheCardSpentThatCreditMeanwhileLeavingItOverLimit() {
        service.consumeForPurchase(consume("op-1", 100_000));
        // Reverso de esa compra (op-2) y, con el disponible recuperado, otra compra por casi todo el límite
        service.restoreCredit(new RestoreCreditCommand(creditLimit.getId(), Money.ofARS(100_000), LocalDate.now(),
                "reverso", "op-2"));
        creditLimit.updateMonthlyLimit(Money.ofARS(1_000_000), "test", "test");
        creditLimit.updateDailyLimit(Money.ofARS(1_000_000), "test", "test");
        service.consumeForPurchase(consume("op-3", 450_000));

        // El reverso no llegó a guardarse en payment-processing: se deshace aunque el crédito ya se gastó
        CancelOperationResult result = service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-2"));

        assertThat(result.outcome()).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(550_000));
        assertThat(creditLimit.getAvailableCredit().getAmount()).isEqualTo(Money.ofARS(-50_000));
        assertThat(result.creditLimit().getOverLimitAmount()).isEqualTo(Money.ofARS(50_000));
        // Excedida: las compras siguientes se rechazan
        assertThatThrownBy(() -> service.consumeForPurchase(consume("op-4", 1)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Crédito insuficiente");
    }

    @Test
    void shouldReportNoExcessWhenTheCancellationFitsTheLimit() {
        service.consumeForPurchase(consume("op-1", 20_000));

        CancelOperationResult result = service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-1"));

        assertThat(result.creditLimit().getOverLimitAmount().isZero()).isTrue();
    }

    @Test
    void shouldRejectReplayingAnOperationIdWithAnotherType() {
        service.consumeForPurchase(consume("op-1", 20_000));

        assertThatThrownBy(() -> service.restoreCredit(new RestoreCreditCommand(creditLimit.getId(),
                Money.ofARS(20_000), LocalDate.now(), "reverso", "op-1")))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(20_000));
    }

    @Test
    void shouldRejectCancellingAnOperationOfAnotherCreditLimit() {
        service.consumeForPurchase(consume("op-1", 20_000));
        CreditLimit other = creditLimits.save(CreditLimit.assign(
                CardId.of("660e8400-e29b-41d4-a716-446655440000"), Money.ofARS(100_000), 0.3, TestLimitPolicies.ars(), "test"));

        assertThatThrownBy(() -> service.cancelOperation(new CancelOperationCommand(other.getId(), "op-1")))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(20_000));
    }

    // ========================================
    // Estado de la tarjeta
    // ========================================

    @Test
    void consumptionOnAnInactiveCardIsRejectedAndLeavesNoTrace() {
        when(cardValidationPort.isCardActive(CARD_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.consumeForPurchase(consume("op-1", 20_000)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no está activa");
        assertThatThrownBy(() -> service.consumeForCashAdvance(consume("op-2", 1_000)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(creditLimit.getConsumedCredit().getAmount().isZero()).isTrue();
        assertThat(operations.findById("op-1")).isEmpty();
        assertThat(outbox).isEmpty();
    }

    @Test
    void repeatingAnAppliedConsumptionAfterTheCardIsBlockedIsStillIdempotent() {
        service.consumeForPurchase(consume("op-1", 20_000));
        when(cardValidationPort.isCardActive(CARD_ID)).thenReturn(false);

        CreditLimit result = service.consumeForPurchase(consume("op-1", 20_000));

        assertThat(result.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(20_000));
    }

    @Test
    void restoreReleaseAndCancelStillWorkOnAnInactiveCard() {
        service.consumeForPurchase(consume("op-1", 20_000));
        service.consumeForPurchase(consume("op-2", 10_000));
        when(cardValidationPort.isCardActive(CARD_ID)).thenReturn(false);

        service.restoreCredit(new RestoreCreditCommand(creditLimit.getId(), Money.ofARS(5_000), LocalDate.now(),
                "devolución", "op-3"));
        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-2"));
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(5_000), "pago del resumen"));

        assertThat(creditLimit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(10_000));
    }

    private ConsumeCreditCommand consume(String operationId, long amount) {
        return new ConsumeCreditCommand(creditLimit.getId(), Money.ofARS(amount), "compra", operationId);
    }

    /**
     * Guarda las mismas instancias: lo que el servicio modifica se ve en el test
     */
    // ========== Eventos ==========

    @Test
    void consumptionPublishesConsumedOnceEvenIfReplayed() {
        service.consumeForPurchase(consume("op-1", 20_000));
        service.consumeForPurchase(consume("op-1", 20_000));

        assertThat(outbox).extracting(Map.Entry::getKey).containsExactly(KafkaTopics.CREDIT_LIMIT_CONSUMED);
        CreditLimitConsumedEvent consumed = (CreditLimitConsumedEvent) outbox.get(0).getValue();
        assertThat(consumed.getCardId()).isEqualTo(CARD_ID.getValue());
        assertThat(consumed.getCreditLimitId()).isEqualTo(creditLimit.getId());
        assertThat(consumed.getOperationId()).isEqualTo("op-1");
        assertThat(consumed.getOperationType()).isEqualTo("PURCHASE");
        assertThat(consumed.getAmount()).isEqualByComparingTo("20000");
        assertThat(consumed.getConsumedAmount()).isEqualByComparingTo("20000");
        assertThat(consumed.getAvailableAmount()).isEqualByComparingTo("480000");
        assertThat(consumed.getCurrency()).isEqualTo("ARS");
    }

    @Test
    void cashAdvanceIsPublishedWithItsType() {
        service.consumeForCashAdvance(new ConsumeCreditCommand(creditLimit.getId(), Money.ofARS(10_000), "adelanto",
                "op-ca"));

        assertThat(((CreditLimitConsumedEvent) outbox.get(0).getValue()).getOperationType()).isEqualTo("CASH_ADVANCE");
    }

    @Test
    void cancellingAConsumptionPublishesReleasedOnlyOnce() {
        service.consumeForPurchase(consume("op-1", 20_000));
        outbox.clear();

        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-1"));
        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-1"));
        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-never-arrived"));

        assertThat(outbox).extracting(Map.Entry::getKey).containsExactly(KafkaTopics.CREDIT_LIMIT_RELEASED);
        CreditLimitReleasedEvent released = (CreditLimitReleasedEvent) outbox.get(0).getValue();
        assertThat(released.getOperationType()).isEqualTo("CONSUMPTION_CANCELLED");
        assertThat(released.getOperationId()).isEqualTo("op-1");
        assertThat(released.getAmount()).isEqualByComparingTo("20000");
        assertThat(released.getConsumedAmount()).isEqualByComparingTo("0");
        assertThat(released.getAvailableAmount()).isEqualByComparingTo("500000");
    }

    @Test
    void paymentAndRefundPublishReleasedAndUndoingTheRefundPublishesConsumed() {
        service.consumeForPurchase(consume("op-1", 30_000));
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(10_000), "pago del resumen"));
        service.restoreCredit(new RestoreCreditCommand(creditLimit.getId(), Money.ofARS(5_000), LocalDate.now(),
                "devolución", "op-2"));
        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-2"));

        assertThat(outbox).extracting(Map.Entry::getKey).containsExactly(KafkaTopics.CREDIT_LIMIT_CONSUMED,
                KafkaTopics.CREDIT_LIMIT_RELEASED, KafkaTopics.CREDIT_LIMIT_RELEASED, KafkaTopics.CREDIT_LIMIT_CONSUMED);

        CreditLimitReleasedEvent payment = (CreditLimitReleasedEvent) outbox.get(1).getValue();
        assertThat(payment.getOperationType()).isEqualTo("PAYMENT");
        assertThat(payment.getOperationId()).startsWith(CreditOperation.GENERATED_ID_PREFIX);
        assertThat(payment.getAmount()).isEqualByComparingTo("10000");
        assertThat(payment.getConsumedAmount()).isEqualByComparingTo("20000");

        CreditLimitReleasedEvent refund = (CreditLimitReleasedEvent) outbox.get(2).getValue();
        assertThat(refund.getOperationType()).isEqualTo("REFUND");
        assertThat(refund.getOperationId()).isEqualTo("op-2");
        assertThat(refund.getAmount()).isEqualByComparingTo("5000");

        CreditLimitConsumedEvent undoneRefund = (CreditLimitConsumedEvent) outbox.get(3).getValue();
        assertThat(undoneRefund.getOperationType()).isEqualTo("REFUND_CANCELLED");
        assertThat(undoneRefund.getAmount()).isEqualByComparingTo("5000");
        assertThat(undoneRefund.getConsumedAmount()).isEqualByComparingTo("20000");
    }

    @Test
    void refundOfSomethingAlreadyPaidCreditsTheBalanceAndPublishesReleased() {
        service.consumeForPurchase(consume("op-1", 20_000));
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(20_000), "pago del resumen"));
        outbox.clear();

        // Lo consumido ya es cero: la devolución queda como saldo a favor
        service.restoreCredit(new RestoreCreditCommand(creditLimit.getId(), Money.ofARS(20_000), LocalDate.now(),
                "devolución", "op-2"));

        assertThat(outbox).extracting(Map.Entry::getKey).containsExactly(KafkaTopics.CREDIT_LIMIT_RELEASED);
        CreditLimitReleasedEvent refund = (CreditLimitReleasedEvent) outbox.get(0).getValue();
        assertThat(refund.getOperationType()).isEqualTo("REFUND");
        assertThat(refund.getAmount()).isEqualByComparingTo("20000");
        assertThat(refund.getConsumedAmount()).isEqualByComparingTo("0");
        assertThat(refund.getCreditBalanceAmount()).isEqualByComparingTo("20000");
        assertThat(refund.getAvailableAmount()).isEqualByComparingTo("520000");
    }

    @Test
    void aPurchasePaidWithTheBalancePublishesConsumedAndRecordsTheBalanceDelta() {
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(5_000), "pago anticipado"));
        outbox.clear();

        service.consumeForPurchase(consume("op-1", 3_000));

        CreditLimit limit = creditLimits.findById(creditLimit.getId()).orElseThrow();
        assertThat(limit.getConsumedCredit().getAmount().isZero()).isTrue();
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(Money.ofARS(2_000));
        CreditOperation operation = operations.findById("op-1").orElseThrow();
        assertThat(operation.getCreditDelta().isZero()).isTrue();
        assertThat(operation.getBalanceDelta()).isEqualTo(Money.ofARS(-3_000));
        CreditLimitConsumedEvent consumed = (CreditLimitConsumedEvent) outbox.get(0).getValue();
        assertThat(consumed.getAmount()).isEqualByComparingTo("3000");
        assertThat(consumed.getCreditBalanceAmount()).isEqualByComparingTo("2000");
    }

    @Test
    void cancellingAPurchasePaidWithTheBalanceGivesTheBalanceBack() {
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(5_000), "pago anticipado"));
        service.consumeForPurchase(consume("op-1", 8_000)); // 5.000 del saldo + 3.000 de deuda

        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-1"));

        CreditLimit limit = creditLimits.findById(creditLimit.getId()).orElseThrow();
        assertThat(limit.getConsumedCredit().getAmount().isZero()).isTrue();
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(Money.ofARS(5_000));
        assertThat(limit.getAvailableCredit().getAmount()).isEqualTo(Money.ofARS(505_000));
    }

    @Test
    void cancellingAPurchaseThatWasAlreadyPaidCreditsItsAmount() {
        service.consumeForPurchase(consume("op-1", 20_000));
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(20_000), "pago del resumen"));

        // La compra se anula después de pagada: el cliente pagó algo que no existió
        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "op-1"));

        CreditLimit limit = creditLimits.findById(creditLimit.getId()).orElseThrow();
        assertThat(limit.getConsumedCredit().getAmount().isZero()).isTrue();
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(Money.ofARS(20_000));
    }

    @Test
    void cancellingAnOverpaymentAfterTheBalanceWasSpentLeavesDebt() {
        service.consumeForPurchase(consume("op-1", 10_000));
        service.releaseCredit(new ReleaseCreditCommand(creditLimit.getId(), Money.ofARS(15_000), "pago", "pay-1"));
        service.consumeForPurchase(consume("op-2", 5_000)); // usa los 5.000 de saldo a favor

        // El pago se rechazó después: vuelve toda la deuda que cancelaba
        service.cancelOperation(new CancelOperationCommand(creditLimit.getId(), "pay-1"));

        CreditLimit limit = creditLimits.findById(creditLimit.getId()).orElseThrow();
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(15_000));
        assertThat(limit.getCreditBalance().getAmount().isZero()).isTrue();
        assertThat(limit.getAvailableCredit().getAmount()).isEqualTo(Money.ofARS(485_000));
    }

    private static class InMemoryCreditLimits implements CreditLimitRepository {
        private final Map<String, CreditLimit> store = new HashMap<>();

        @Override
        public CreditLimit save(CreditLimit creditLimit) {
            store.put(creditLimit.getId(), creditLimit);
            return creditLimit;
        }

        @Override
        public Optional<CreditLimit> findById(String creditLimitId) {
            return Optional.ofNullable(store.get(creditLimitId));
        }

        @Override
        public Optional<CreditLimit> findByIdForUpdate(String creditLimitId) {
            return findById(creditLimitId);
        }

        @Override
        public Optional<CreditLimit> findByCardId(CardId cardId) {
            return store.values().stream().filter(c -> c.getCardId().equals(cardId)).findFirst();
        }

        @Override
        public boolean existsByCardId(CardId cardId) {
            return findByCardId(cardId).isPresent();
        }

        @Override
        public void delete(String creditLimitId) {
            store.remove(creditLimitId);
        }

        @Override
        public CreditLimitHistory saveHistory(CreditLimitHistory history) {
            return history;
        }

        @Override
        public List<CreditLimitHistory> findHistoryByCreditLimitId(String creditLimitId) {
            return List.of();
        }
    }

    private static class InMemoryOperations implements CreditOperationRepository {
        private final Map<String, CreditOperation> store = new HashMap<>();

        @Override
        public Optional<CreditOperation> findById(String operationId) {
            return Optional.ofNullable(store.get(operationId));
        }

        @Override
        public CreditOperation save(CreditOperation operation) {
            store.put(operation.getOperationId(), operation);
            return operation;
        }
    }
}
