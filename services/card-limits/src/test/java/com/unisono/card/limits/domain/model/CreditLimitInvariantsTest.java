package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariantes del aggregate: liberación ≤ consumido, adelantos ≤ compras, diario ≤ mensual y montos positivos
 */
class CreditLimitInvariantsTest {

    private static final CardId CARD_ID = CardId.of("550e8400-e29b-41d4-a716-446655440000");

    private CreditLimit creditLimit;

    @BeforeEach
    void setUp() {
        // Compras 500.000, adelantos 150.000 (30%), diario 100.000, mensual 500.000
        creditLimit = CreditLimit.assign(CARD_ID, Money.ofARS(500_000), 0.3, TestLimitPolicies.ars(), "test");
        creditLimit.consumeForPurchase(Money.ofARS(20_000));
    }

    // releaseCredit

    @Test
    void releaseUpToTheConsumedCreditIsAllowed() {
        creditLimit.releaseCredit(Money.ofARS(20_000));

        assertThat(creditLimit.getConsumedCredit().getAmount().isZero()).isTrue();
        assertThat(creditLimit.getAvailableCredit().getAmount()).isEqualTo(Money.ofARS(500_000));
    }

    @Test
    void releaseAboveTheConsumedCreditCreditsTheExcessAsBalance() {
        Money credited = creditLimit.releaseCredit(Money.ofARS(new java.math.BigDecimal("20000.01")));

        assertThat(credited).isEqualTo(Money.ofARS(new java.math.BigDecimal("0.01")));
        assertThat(creditLimit.getConsumedCredit().getAmount().isZero()).isTrue();
        assertThat(creditLimit.getCreditBalance().getAmount()).isEqualTo(Money.ofARS(new java.math.BigDecimal("0.01")));
    }

    @Test
    void negativeReleaseIsInvalid() {
        assertThatThrownBy(() -> creditLimit.releaseCredit(Money.ofARS(-1)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void consumedCreditCannotBeNegative() {
        assertThatThrownBy(() -> ConsumedCredit.of(Money.ofARS(-1)))
                .isInstanceOf(ValidationException.class);
    }

    // decreasePurchaseLimit

    @Test
    void decreaseBelowTheCashAdvanceLimitIsRejectedWithoutChangingTheLimit() {
        assertThatThrownBy(() -> creditLimit.decreasePurchaseLimit(Money.ofARS(400_000), TestLimitPolicies.ars(), "Riesgo", "analista"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("límite de adelantos");

        assertThat(creditLimit.getPurchaseLimit().getAmount()).isEqualTo(Money.ofARS(500_000));
        assertThat(creditLimit.getHistory()).hasSize(1); // solo la asignación
    }

    @Test
    void decreaseDownToTheCashAdvanceLimitIsAllowed() {
        creditLimit.decreasePurchaseLimit(Money.ofARS(350_000), TestLimitPolicies.ars(), "Riesgo", "analista");

        assertThat(creditLimit.getPurchaseLimit().getAmount()).isEqualTo(Money.ofARS(150_000));
    }

    @Test
    void decreaseBelowTheConsumedCreditIsRejectedWithoutChangingTheLimit() {
        CreditLimit limit = CreditLimit.assign(CARD_ID, Money.ofARS(500_000), 0.01, TestLimitPolicies.ars(), "test"); // adelantos 5.000
        limit.consumeForPurchase(Money.ofARS(90_000));

        assertThatThrownBy(() -> limit.decreasePurchaseLimit(Money.ofARS(420_000), TestLimitPolicies.ars(), "Riesgo", "analista"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("crédito consumido");
        assertThat(limit.getPurchaseLimit().getAmount()).isEqualTo(Money.ofARS(500_000));
    }

    // updateTemporalLimits

    @Test
    void dailyAboveMonthlyIsRejected() {
        assertThatThrownBy(() -> creditLimit.updateDailyLimit(Money.ofARS(new java.math.BigDecimal("500000.01")), "r", "a"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> creditLimit.updateMonthlyLimit(Money.ofARS(99_999), "r", "a"))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(creditLimit.getDailyLimit().getAmount()).isEqualTo(Money.ofARS(100_000));
        assertThat(creditLimit.getMonthlyLimit().getAmount()).isEqualTo(Money.ofARS(500_000));
    }

    @Test
    void dailyEqualToMonthlyIsAllowed() {
        creditLimit.updateTemporalLimits(Money.ofARS(200_000), Money.ofARS(200_000), "r", "a");

        assertThat(creditLimit.getDailyLimit().getAmount()).isEqualTo(Money.ofARS(200_000));
        assertThat(creditLimit.getMonthlyLimit().getAmount()).isEqualTo(Money.ofARS(200_000));
        assertThat(creditLimit.getHistory()).extracting(CreditLimitHistory::getLimitType)
                .containsExactly(LimitType.PURCHASE, LimitType.DAILY, LimitType.MONTHLY);
    }

    @Test
    void zeroOrNegativeTemporalLimitsAreInvalid() {
        assertThatThrownBy(() -> creditLimit.updateDailyLimit(Money.ofARS(0), "r", "a"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> creditLimit.updateMonthlyLimit(Money.ofARS(-1), "r", "a"))
                .isInstanceOf(ValidationException.class);
    }
}
