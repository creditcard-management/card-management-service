package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saldo a favor: lo pagado o devuelto de más se acredita, se suma al disponible y lo usan primero los consumos.
 * Nunca convive con deuda.
 */
class CreditBalanceTest {

    private static final CardId CARD_ID = CardId.of("550e8400-e29b-41d4-a716-446655440000");

    /** Límite 100.000, adelantos 30.000, diario 100.000, mensual 500.000 */
    private CreditLimit limit;

    @BeforeEach
    void setUp() {
        limit = CreditLimit.assign(CARD_ID, ars(100_000), 0.3, TestLimitPolicies.ars(), "test");
    }

    private static Money ars(long amount) {
        return Money.ofARS(amount);
    }

    @Test
    void aNewLimitHasNoBalance() {
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(ars(0));
        assertThat(limit.getNetDebt()).isEqualTo(ars(0));
    }

    @Test
    void aPaymentAboveTheDebtCreditsTheExcess() {
        limit.consumeForPurchase(ars(10_000));

        Money credited = limit.releaseCredit(ars(12_000));

        assertThat(credited).isEqualTo(ars(2_000));
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(0));
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(ars(2_000));
        assertThat(limit.getAvailableCredit().getAmount()).isEqualTo(ars(102_000));
        assertThat(limit.getNetDebt()).isEqualTo(ars(-2_000));
    }

    @Test
    void aPaymentWithoutDebtIsCreditedEntirely() {
        assertThat(limit.releaseCredit(ars(5_000))).isEqualTo(ars(5_000));
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(ars(5_000));
    }

    @Test
    void aPaymentUpToTheDebtCreditsNothing() {
        limit.consumeForPurchase(ars(10_000));

        assertThat(limit.releaseCredit(ars(10_000))).isEqualTo(ars(0));
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(ars(0));
    }

    @Test
    void purchasesUseTheBalanceFirst() {
        limit.releaseCredit(ars(5_000));

        limit.consumeForPurchase(ars(3_000));
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(0));
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(ars(2_000));

        limit.consumeForPurchase(ars(7_000));
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(5_000));
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(ars(0));
        assertThat(limit.getAvailableCredit().getAmount()).isEqualTo(ars(95_000));
    }

    @Test
    void theBalanceAllowsSpendingAboveThePurchaseLimit() {
        limit.releaseCredit(ars(5_000));
        limit.updateTemporalLimits(ars(200_000), ars(500_000), "r", "a"); // que no frene el diario

        // Disponible = 100.000 de límite + 5.000 de saldo
        assertThat(limit.canPurchase(ars(105_000))).isTrue();
        assertThat(limit.canPurchase(ars(105_001))).isFalse();

        limit.consumeForPurchase(ars(105_000));
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(100_000));
        assertThat(limit.getAvailableCredit().getAmount()).isEqualTo(ars(0));
    }

    @Test
    void purchasesPaidWithTheBalanceStillCountForTheDailyAndMonthlyLimits() {
        limit.releaseCredit(ars(5_000));

        limit.consumeForPurchase(ars(3_000));

        assertThat(limit.getDailyLimit().getConsumed()).isEqualTo(ars(3_000));
        assertThat(limit.getMonthlyLimit().getConsumed()).isEqualTo(ars(3_000));
    }

    @Test
    void onlyTheDebtPartOfACashAdvanceCountsTowardsItsCap() {
        limit.releaseCredit(ars(10_000));

        // 35.000 > tope de 30.000, pero 10.000 los cubre el saldo: la deuda en adelantos es 25.000
        limit.consumeForCashAdvance(ars(35_000));

        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(25_000));
        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(25_000));
        assertThat(limit.getCashAdvanceAvailable()).isEqualTo(ars(5_000));
    }

    @Test
    void aCashAdvancePaidEntirelyWithTheBalanceDoesNotUseTheCap() {
        limit.releaseCredit(ars(10_000));

        limit.consumeForCashAdvance(ars(4_000));

        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(0));
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(ars(6_000));
    }

    @Test
    void aCashAdvanceWhoseDebtPartExceedsTheCapIsRejected() {
        limit.releaseCredit(ars(10_000));

        assertThat(limit.canCashAdvance(ars(40_001))).isFalse();
        assertThatThrownBy(() -> limit.consumeForCashAdvance(ars(40_001)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("saldo a favor");
        assertThat(limit.canCashAdvance(ars(40_000))).isTrue();
    }

    @Test
    void theBalanceDoesNotEnableCashAdvancesOnACardWithoutThem() {
        CreditLimit withoutAdvances = CreditLimit.assign(CARD_ID, ars(100_000), 0, TestLimitPolicies.ars(), "test");
        withoutAdvances.releaseCredit(ars(10_000));

        assertThat(withoutAdvances.canCashAdvance(ars(1_000))).isFalse();
        assertThatThrownBy(() -> withoutAdvances.consumeForCashAdvance(ars(1_000)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(CreditLimit.NO_CASH_ADVANCE);
    }

    @Test
    void aRefundOfACashAdvanceAlreadyPaidCreditsTheBalance() {
        limit.consumeForCashAdvance(ars(10_000));
        limit.releaseCredit(ars(10_000));

        Money credited = limit.restoreConsumption(ars(10_000), LocalDate.now(), true);

        assertThat(credited).isEqualTo(ars(10_000));
        assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(ars(0));
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(ars(10_000));
    }

    @Test
    void undoingARefundThatCreatedBalanceTakesItBackAndTheRestBecomesDebt() {
        limit.consumeForPurchase(ars(10_000));
        CreditLimit.ConsumptionSnapshot before = limit.consumptionSnapshot();
        limit.restoreConsumption(ars(15_000), LocalDate.now()); // 10.000 de deuda + 5.000 de saldo
        CreditOperation refund = CreditOperation.applied("op", limit.getId(), CreditOperation.Type.RESTORE,
                before, limit.consumptionSnapshot(), "devolución");
        limit.consumeForPurchase(ars(2_000)); // usa 2.000 del saldo

        limit.revertOperation(refund);

        // Antes de deshacer: saldo 3.000. Deshacer suma 15.000 a la posición neta: deuda 12.000
        assertThat(limit.getCreditBalance().getAmount()).isEqualTo(ars(0));
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(ars(12_000));
        assertThat(refund.getBalanceDelta()).isEqualTo(ars(5_000));
        assertThat(refund.getCreditDelta()).isEqualTo(ars(-10_000));
    }

    @Test
    void consumedAndBalanceAreNeverBothPositive() {
        limit.consumeForPurchase(ars(10_000));
        limit.releaseCredit(ars(25_000));
        limit.consumeForCashAdvance(ars(20_000));
        limit.restoreConsumption(ars(1_000), LocalDate.now());
        limit.releaseCredit(ars(3_000));

        assertThat(limit.getConsumedCredit().getAmount().isPositive()
                && limit.getCreditBalance().getAmount().isPositive()).isFalse();
        assertThat(limit.getNetDebt()).isEqualTo(ars(10_000 - 25_000 + 20_000 - 1_000 - 3_000));
    }

    @Test
    void aNegativeBalanceIsInvalid() {
        assertThatThrownBy(() -> CreditBalance.of(ars(-1))).isInstanceOf(ValidationException.class);
    }
}
