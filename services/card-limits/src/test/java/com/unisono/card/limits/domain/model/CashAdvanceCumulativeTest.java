package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adelantos acumulativos: lo usado en adelantos descuenta del tope, los pagos lo bajan primero y los reversos
 * de un adelanto lo devuelven
 */
class CashAdvanceCumulativeTest {

    private static final CardId CARD_ID = CardId.of("550e8400-e29b-41d4-a716-446655440000");

    private CreditLimit limit;

    @BeforeEach
    void setUp() {
        // Compras 100.000, adelantos 30.000 (30%), diario 100.000
        limit = CreditLimit.assign(CARD_ID, Money.ofARS(100_000), 0.3, TestLimitPolicies.ars(), "test");
    }

    private static Money ars(long amount) {
        return Money.ofARS(amount);
    }

    // ========================================
    // Consumo
    // ========================================

    @Test
    void advancesAccumulateAgainstTheCashAdvanceLimit() {
        limit.consumeForCashAdvance(ars(20_000));

        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(20_000));
        assertThat(limit.getCashAdvanceAvailable()).isEqualTo(ars(10_000));
        assertThat(limit.canCashAdvance(ars(15_000))).isFalse();
        assertThatThrownBy(() -> limit.consumeForCashAdvance(ars(15_000)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("disponible para adelantos");

        limit.consumeForCashAdvance(ars(10_000));
        assertThat(limit.getCashAdvanceAvailable().isZero()).isTrue();
        assertThat(limit.canCashAdvance(ars(1))).isFalse();
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(30_000));
    }

    @Test
    void purchasesDoNotUseTheCashAdvanceLimit() {
        limit.consumeForPurchase(ars(50_000));

        assertThat(limit.getCashAdvanceConsumed().getAmount().isZero()).isTrue();
        assertThat(limit.canCashAdvance(ars(30_000))).isTrue();
    }

    @Test
    void anAdvanceIsStillBoundedByTheAvailableCredit() {
        limit.consumeForPurchase(ars(90_000));

        // Quedan 30.000 para adelantos pero solo 10.000 de crédito
        assertThatThrownBy(() -> limit.consumeForCashAdvance(ars(20_000)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Crédito insuficiente");
    }

    // ========================================
    // Pagos: primero adelantos
    // ========================================

    @Test
    void aPaymentGoesFirstToCashAdvances() {
        limit.consumeForCashAdvance(ars(20_000));
        limit.consumeForPurchase(ars(30_000));

        limit.releaseCredit(ars(25_000));

        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(25_000));
        assertThat(limit.getCashAdvanceConsumed().getAmount().isZero()).isTrue();
        assertThat(limit.getCashAdvanceAvailable()).isEqualTo(ars(30_000));
    }

    @Test
    void aPartialPaymentLowersOnlyPartOfTheCashAdvances() {
        limit.consumeForCashAdvance(ars(20_000));
        limit.consumeForPurchase(ars(30_000));

        limit.releaseCredit(ars(5_000));

        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(45_000));
        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(15_000));
    }

    // ========================================
    // Reversos y devoluciones
    // ========================================

    @Test
    void reversingAnAdvanceGivesBackTheCashAdvanceUsage() {
        limit.consumeForCashAdvance(ars(20_000));
        limit.consumeForPurchase(ars(10_000));

        limit.restoreConsumption(ars(20_000), LocalDate.now(), true);

        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(10_000));
        assertThat(limit.getCashAdvanceConsumed().getAmount().isZero()).isTrue();
        assertThat(limit.getDailyLimit().getConsumed()).isEqualTo(ars(10_000));
    }

    @Test
    void reversingAPurchaseDoesNotTouchTheCashAdvanceUsage() {
        limit.consumeForCashAdvance(ars(20_000));
        limit.consumeForPurchase(ars(10_000));

        limit.restoreConsumption(ars(10_000), LocalDate.now());

        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(20_000));
        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(20_000));
    }

    @Test
    void reversingAnAdvanceAlreadyPaidRestoresNothing() {
        limit.consumeForCashAdvance(ars(20_000));
        limit.releaseCredit(ars(20_000));

        Money notRestored = limit.restoreConsumption(ars(20_000), LocalDate.now(), true);

        assertThat(notRestored).isEqualTo(ars(20_000));
        assertThat(limit.getConsumedCredit().getAmount().isZero()).isTrue();
        assertThat(limit.getCashAdvanceConsumed().getAmount().isZero()).isTrue();
    }

    @Test
    void cashAdvanceUsageNeverExceedsTheConsumedCredit() {
        limit.consumeForCashAdvance(ars(20_000));

        // Un reverso informado como compra que baja lo consumido por debajo de lo usado en adelantos
        limit.restoreConsumption(ars(5_000), LocalDate.now());

        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(15_000));
        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(15_000));
    }

    // ========================================
    // Cambio del tope
    // ========================================

    @Test
    void theLimitCanBeLoweredBelowWhatWasUsedAndBlocksNewAdvances() {
        limit.consumeForCashAdvance(ars(20_000));

        limit.updateCashAdvanceLimit(ars(10_000), TestLimitPolicies.ars(), "Riesgo", "analista");

        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(20_000));
        assertThat(limit.getCashAdvanceAvailable().isZero()).isTrue();
        assertThatThrownBy(() -> limit.consumeForCashAdvance(ars(1_000)))
                .isInstanceOf(BusinessRuleException.class);

        // Un pago baja lo usado y vuelve a habilitar adelantos
        limit.releaseCredit(ars(15_000));
        assertThat(limit.getCashAdvanceAvailable()).isEqualTo(ars(5_000));
        limit.consumeForCashAdvance(ars(5_000));
    }

    @Test
    void advancesCanBeDisabledWhileInUse() {
        limit.consumeForCashAdvance(ars(20_000));

        limit.updateCashAdvanceLimit(ars(0), TestLimitPolicies.ars(), "Riesgo", "analista");

        assertThat(limit.getCashAdvanceLimit().isEnabled()).isFalse();
        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(20_000));
        assertThatThrownBy(() -> limit.consumeForCashAdvance(ars(1_000)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(CreditLimit.NO_CASH_ADVANCE);
    }

    // ========================================
    // Operaciones (deltas para cancelar)
    // ========================================

    @Test
    void operationsRecordWhatTheyMovedInCashAdvancesAndCanBeUndoneExactly() {
        limit.consumeForCashAdvance(ars(20_000));
        limit.consumeForPurchase(ars(30_000));

        CreditLimit.ConsumptionSnapshot before = limit.consumptionSnapshot();
        limit.releaseCredit(ars(25_000));
        CreditOperation payment = CreditOperation.applied("pay-1", limit.getId(), CreditOperation.Type.RELEASE,
                before, limit.consumptionSnapshot(), "pago");

        assertThat(payment.getCreditDelta()).isEqualTo(ars(-25_000));
        assertThat(payment.getCashAdvanceDelta()).isEqualTo(ars(-20_000));

        // Deshacer el pago vuelve a como estaba: 50.000 consumidos, 20.000 en adelantos
        limit.revertOperation(payment);
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(50_000));
        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(20_000));
    }
}
