package com.unisono.card.limits.domain.model;

import com.unisono.card.limits.domain.port.input.AssignCreditLimitUseCase.AssignCreditLimitCommand;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tope de adelantos en cero = la tarjeta no tiene adelantos habilitados
 */
class CashAdvanceDisabledTest {

    private static final CardId CARD_ID = CardId.of("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void zeroPercentageIsExplicitAndNullFallsBackToTheDefault() {
        assertThat(new AssignCreditLimitCommand(CARD_ID, Money.ofARS(5_000), 0.0, null, null, "a")
                .cashAdvancePercentage()).isZero();
        assertThat(new AssignCreditLimitCommand(CARD_ID, Money.ofARS(5_000), null, null, null, "a")
                .cashAdvancePercentage()).isEqualTo(0.30);
    }

    @Test
    void assignmentWithZeroPercentageHasNoCashAdvancesButCanPurchase() {
        CreditLimit limit = CreditLimit.assign(CARD_ID, Money.ofARS(500_000), 0, "test");

        assertThat(limit.getCashAdvanceLimit().getAmount().isZero()).isTrue();
        assertThat(limit.getCashAdvanceLimit().isEnabled()).isFalse();
        assertThat(limit.canCashAdvance(Money.ofARS(1_000))).isFalse();
        assertThatThrownBy(() -> limit.consumeForCashAdvance(Money.ofARS(1_000)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(CreditLimit.NO_CASH_ADVANCE);

        limit.consumeForPurchase(Money.ofARS(1_000));
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(1_000));
    }

    @Test
    void lowPurchaseLimitCanBeAssignedWithoutCashAdvances() {
        CreditLimit limit = CreditLimit.assign(CARD_ID, Money.ofARS(1_000), 0, "test");

        assertThat(limit.getPurchaseLimit().getAmount()).isEqualTo(Money.ofARS(1_000));
    }

    @Test
    void positivePercentageBelowTheMinimumIsStillRejected() {
        // 30% de 1.000 = 300 < 500
        assertThatThrownBy(() -> CreditLimit.assign(CARD_ID, Money.ofARS(1_000), 0.3, "test"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cero para no habilitar adelantos");
    }

    @Test
    void cashAdvancesCanBeDisabledAndEnabledAgain() {
        CreditLimit limit = CreditLimit.assign(CARD_ID, Money.ofARS(500_000), 0.3, "test");

        limit.updateCashAdvanceLimit(Money.ofARS(0), "Riesgo", "analista");
        assertThat(limit.getCashAdvanceLimit().isEnabled()).isFalse();

        limit.updateCashAdvanceLimit(Money.ofARS(10_000), "Reactivación", "analista");
        assertThat(limit.canCashAdvance(Money.ofARS(10_000))).isTrue();
    }

    @Test
    void cashAdvanceLimitBetweenZeroAndTheMinimumOrNegativeIsInvalid() {
        CreditLimit limit = CreditLimit.assign(CARD_ID, Money.ofARS(500_000), 0.3, "test");

        assertThatThrownBy(() -> limit.updateCashAdvanceLimit(Money.ofARS(300), "r", "a"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> CashAdvanceLimit.of(Money.ofARS(-1)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void purchaseLimitChangesRequireAPositiveAmount() {
        CreditLimit limit = CreditLimit.assign(CARD_ID, Money.ofARS(500_000), 0.3, "test");

        assertThatThrownBy(() -> limit.increasePurchaseLimit(Money.ofARS(0), "r", "a"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> limit.decreasePurchaseLimit(Money.ofARS(0), "r", "a"))
                .isInstanceOf(ValidationException.class);
    }
}
