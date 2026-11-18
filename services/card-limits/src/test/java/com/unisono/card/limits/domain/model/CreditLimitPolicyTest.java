package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los rangos y los valores por defecto salen de la política de la moneda (LimitPolicy), no de constantes en ARS.
 */
class CreditLimitPolicyTest {

    private static final CardId CARD_ID = CardId.of("550e8400-e29b-41d4-a716-446655440000");

    /** Compras 2.500.000–25.000.000, adelantos 500–7.500.000, diario y mensual 1.250.000 (application.yml) */
    private static final LimitPolicy ARS = new LimitPolicy("ARS",
            ars(2_500_000), ars(25_000_000), ars(500), ars(7_500_000), ars(1_250_000), ars(1_250_000));

    private static Money ars(long amount) {
        return Money.ofARS(amount);
    }

    private static Money usd(long amount) {
        return Money.of(BigDecimal.valueOf(amount), "USD");
    }

    @Nested
    class Ranges {

        @Test
        void purchaseLimitMustBeWithinThePolicyRange() {
            assertThatThrownBy(() -> CreditLimit.assign(CARD_ID, ars(2_499_999), 0.3, ARS, "test"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("al menos");
            assertThatThrownBy(() -> CreditLimit.assign(CARD_ID, ars(25_000_001), 0.3, ARS, "test"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("no puede exceder");
            assertThatCode(() -> CreditLimit.assign(CARD_ID, ars(2_500_000), 0.3, ARS, "test"))
                    .doesNotThrowAnyException();
        }

        @Test
        void theMaximumPurchaseLimitWithTheDefaultPercentageFitsTheCashAdvanceRange() {
            CreditLimit limit = CreditLimit.assign(CARD_ID, ars(25_000_000), 0.3, ARS, "test");

            assertThat(limit.getCashAdvanceLimit().getAmount()).isEqualTo(ars(7_500_000));
        }

        @Test
        void increaseAndDecreaseKeepTheResultWithinThePolicyRange() {
            CreditLimit limit = CreditLimit.assign(CARD_ID, ars(3_000_000), 0, ARS, "test");

            assertThatThrownBy(() -> limit.decreasePurchaseLimit(ars(600_000), ARS, "r", "a"))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> limit.increasePurchaseLimit(ars(22_000_001), ARS, "r", "a"))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void cashAdvanceLimitMustBeWithinThePolicyRangeOrZero() {
            CreditLimit limit = CreditLimit.assign(CARD_ID, ars(25_000_000), 0.3, ARS, "test");

            assertThatThrownBy(() -> limit.updateCashAdvanceLimit(ars(7_500_001), ARS, "r", "a"))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> limit.updateCashAdvanceLimit(ars(499), ARS, "r", "a"))
                    .isInstanceOf(ValidationException.class);
            assertThatCode(() -> limit.updateCashAdvanceLimit(ars(0), ARS, "r", "a")).doesNotThrowAnyException();
        }

        @Test
        void aPolicyOfAnotherCurrencyIsRejected() {
            assertThatThrownBy(() -> CreditLimit.assign(CARD_ID, usd(5_000), 0.3, ARS, "test"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("USD");
        }
    }

    @Nested
    class Defaults {

        @Test
        void dailyAndMonthlyLimitsComeFromThePolicy() {
            CreditLimit limit = CreditLimit.assign(CARD_ID, ars(5_000_000), 0.3, ARS, "test");

            assertThat(limit.getDailyLimit().getAmount()).isEqualTo(ars(1_250_000));
            assertThat(limit.getMonthlyLimit().getAmount()).isEqualTo(ars(1_250_000));
        }
    }

    @Nested
    class OtherCurrency {

        private final LimitPolicy usdPolicy = TestLimitPolicies.of("USD");

        @Test
        void aLimitInUsdIsCreatedEntirelyInUsd() {
            CreditLimit limit = CreditLimit.assign(CARD_ID, usd(20_000), 0.3, usdPolicy, "test");

            assertThat(limit.getCurrencyCode()).isEqualTo("USD");
            assertThat(limit.getCashAdvanceLimit().getAmount()).isEqualTo(usd(6_000));
            assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(usd(0));
            assertThat(limit.getCashAdvanceConsumed().getAmount()).isEqualTo(usd(0));
            assertThat(limit.getDailyLimit().getConsumed()).isEqualTo(usd(0));
            assertThat(limit.getMonthlyLimit().getConsumed()).isEqualTo(usd(0));
            assertThat(limit.getHistory().get(0).getPreviousValue()).isEqualTo(usd(0));
        }

        @Test
        void aLimitInUsdConsumesReleasesAndRestoresInUsd() {
            CreditLimit limit = CreditLimit.assign(CARD_ID, usd(20_000), 0.3, usdPolicy, "test");

            limit.consumeForPurchase(usd(1_000));
            limit.consumeForCashAdvance(usd(500));
            limit.releaseCredit(usd(700));
            limit.restoreConsumption(usd(200), LocalDate.now());

            assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(usd(600));
            assertThat(limit.getAvailableCredit().getAmount()).isEqualTo(usd(19_400));
            assertThat(limit.getDailyLimit().getAvailable()).isEqualTo(usd(98_700));
        }

        @Test
        void changesInUsdAreRecordedInUsd() {
            CreditLimit limit = CreditLimit.assign(CARD_ID, usd(20_000), 0, usdPolicy, "test");

            limit.increasePurchaseLimit(usd(5_000), usdPolicy, "r", "a");
            limit.updateTemporalLimits(usd(50_000), usd(200_000), "r", "a");

            assertThat(limit.getHistory()).allSatisfy(change -> {
                assertThat(change.getNewValue().getCurrencyCode()).isEqualTo("USD");
                assertThat(change.getDifference().getCurrencyCode()).isEqualTo("USD");
            });
        }

        @Test
        void aTemporalLimitUpdateAfterThePeriodEndedKeepsTheCurrency() {
            CreditLimit limit = CreditLimit.reconstituteFromPersistence("id", CARD_ID,
                    PurchaseLimit.reconstituteFromPersistence(usd(20_000)),
                    CashAdvanceLimit.zero("USD"),
                    ConsumedCredit.zero("USD"), ConsumedCredit.zero("USD"), CreditBalance.zero("USD"), AvailableCredit.of(usd(20_000)),
                    DailyLimit.reconstituteFromPersistence(usd(1_000), usd(300), LocalDate.now().minusDays(1)),
                    MonthlyLimit.reconstituteFromPersistence(usd(5_000), usd(300), YearMonth.now().minusMonths(1)),
                    null, null);

            limit.updateTemporalLimits(usd(2_000), usd(6_000), "r", "a");

            assertThat(limit.getDailyLimit().getConsumed()).isEqualTo(usd(0));
            assertThat(limit.getMonthlyLimit().getConsumed()).isEqualTo(usd(0));
        }
    }

    @Nested
    class StoredLimits {

        @Test
        void aLimitBelowTheCurrentMinimumKeepsOperating() {
            CreditLimit limit = CreditLimit.reconstituteFromPersistence("id", CARD_ID,
                    PurchaseLimit.reconstituteFromPersistence(ars(150_000)),
                    CashAdvanceLimit.reconstituteFromPersistence(ars(45_000)),
                    ConsumedCredit.zero("ARS"), ConsumedCredit.zero("ARS"), CreditBalance.zero("ARS"), AvailableCredit.of(ars(150_000)),
                    DailyLimit.reconstituteFromPersistence(ars(100_000), ars(0), LocalDate.now()),
                    MonthlyLimit.reconstituteFromPersistence(ars(500_000), ars(0), YearMonth.now()),
                    null, null);

            limit.consumeForPurchase(ars(10_000));
            limit.releaseCredit(ars(10_000));
            limit.updateTemporalLimits(ars(50_000), null, "r", "a");

            assertThat(limit.getAvailableCredit().getAmount()).isEqualTo(ars(150_000));
        }

        @Test
        void changingThePurchaseLimitOfAStoredLimitAppliesTheCurrentRange() {
            CreditLimit limit = CreditLimit.reconstituteFromPersistence("id", CARD_ID,
                    PurchaseLimit.reconstituteFromPersistence(ars(150_000)),
                    CashAdvanceLimit.zero("ARS"),
                    ConsumedCredit.zero("ARS"), ConsumedCredit.zero("ARS"), CreditBalance.zero("ARS"), AvailableCredit.of(ars(150_000)),
                    DailyLimit.reconstituteFromPersistence(ars(100_000), ars(0), LocalDate.now()),
                    MonthlyLimit.reconstituteFromPersistence(ars(500_000), ars(0), YearMonth.now()),
                    null, null);

            assertThatThrownBy(() -> limit.increasePurchaseLimit(ars(50_000), ARS, "r", "a"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("al menos");
            limit.increasePurchaseLimit(ars(2_350_000), ARS, "r", "a");

            assertThat(limit.getPurchaseLimit().getAmount()).isEqualTo(ars(2_500_000));
        }
    }

    @Nested
    class ExplicitCashAdvancePercentage {

        @Test
        void aPercentageBelowTheMinimumSaysWhichPercentageWorks() {
            // 0,01% de 2.500.000 = 250 < 500: hace falta al menos el 0,02%
            assertThatThrownBy(() -> CreditLimit.assign(CARD_ID, ars(2_500_000), 0.0001, ARS, "test"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("menor al mínimo")
                    .hasMessageContaining("al menos el 0.02%")
                    .hasMessageContaining("cero para no habilitar adelantos");
            assertThatCode(() -> CreditLimit.assign(CARD_ID, ars(2_500_000), 0.0002, ARS, "test"))
                    .doesNotThrowAnyException();
        }

        @Test
        void aPercentageAboveTheMaximumSaysWhichPercentageWorks() {
            // 50% de 25.000.000 = 12.500.000 > 7.500.000: como mucho el 30%
            assertThatThrownBy(() -> CreditLimit.assign(CARD_ID, ars(25_000_000), 0.5, ARS, "test"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("mayor al máximo")
                    .hasMessageContaining("como mucho el 30%");
        }

        @Test
        void theSuggestedMinimumPercentageIsRoundedUpSoItAlwaysReachesTheMinimum() {
            // 500 / 3.000.000 = 0,01666…% → se sugiere 0,02%, que da 600
            assertThatThrownBy(() -> CreditLimit.assign(CARD_ID, ars(3_000_000), 0.0001, ARS, "test"))
                    .hasMessageContaining("al menos el 0.02%");
            assertThatCode(() -> CreditLimit.assign(CARD_ID, ars(3_000_000), 0.0002, ARS, "test"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class CoherenceWithDefaults {

        @Test
        void theArsPolicyIsCoherentWithTheDefaultPercentage() {
            assertThatCode(() -> ARS.checkCoherentWithDefaults(0.30)).doesNotThrowAnyException();
        }

        @Test
        void rejectsAMinimumPurchaseLimitThatGivesACashAdvanceBelowTheMinimum() {
            // 30% de 1.000 = 300 < 500: asignar el mínimo sin porcentaje explícito fallaría
            LimitPolicy policy = new LimitPolicy("ARS",
                    ars(1_000), ars(1_000_000), ars(500), ars(300_000), ars(1_000), ars(1_000));

            assertThatThrownBy(() -> policy.checkCoherentWithDefaults(0.30))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mínimo de compras (ARS 1000.00) da un tope de adelantos de ARS 300.00");
        }

        @Test
        void rejectsAMaximumPurchaseLimitThatGivesACashAdvanceAboveTheMaximum() {
            // 30% de 25.000.000 = 7.500.000 > 5.000.000
            LimitPolicy policy = new LimitPolicy("ARS",
                    ars(2_500_000), ars(25_000_000), ars(500), ars(5_000_000), ars(1_250_000), ars(1_250_000));

            assertThatThrownBy(() -> policy.checkCoherentWithDefaults(0.30))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mayor al máximo de adelantos");
        }

        @Test
        void rejectsADefaultMonthlyLimitAboveTheMinimumPurchaseLimit() {
            LimitPolicy policy = new LimitPolicy("ARS",
                    ars(2_500_000), ars(25_000_000), ars(500), ars(7_500_000), ars(1_250_000), ars(3_000_000));

            assertThatThrownBy(() -> policy.checkCoherentWithDefaults(0.30))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mensual por defecto");
        }

        @Test
        void withoutDefaultCashAdvancesOnlyTheMonthlyLimitIsChecked() {
            LimitPolicy policy = new LimitPolicy("ARS",
                    ars(1_000), ars(1_000_000), ars(500), ars(300_000), ars(1_000), ars(1_000));

            assertThatCode(() -> policy.checkCoherentWithDefaults(0)).doesNotThrowAnyException();
        }

        @Test
        void reportsEveryProblemAtOnce() {
            LimitPolicy policy = new LimitPolicy("ARS",
                    ars(1_000), ars(25_000_000), ars(500), ars(5_000_000), ars(1_250_000), ars(1_250_000));

            assertThatThrownBy(() -> policy.checkCoherentWithDefaults(0.30))
                    .hasMessageContaining("menor al mínimo de adelantos")
                    .hasMessageContaining("mayor al máximo de adelantos")
                    .hasMessageContaining("mensual por defecto");
        }
    }

    @Nested
    class PolicyConsistency {

        @Test
        void rejectsAMinimumAboveTheMaximum() {
            assertThatThrownBy(() -> new LimitPolicy("ARS",
                    ars(3_000), ars(2_000), ars(500), ars(5_000), ars(1_000), ars(1_000)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mínimo de compras");
        }

        @Test
        void rejectsADefaultDailyLimitAboveTheMonthly() {
            assertThatThrownBy(() -> new LimitPolicy("ARS",
                    ars(1_000), ars(2_000), ars(500), ars(5_000), ars(2_000), ars(1_000)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("diario");
        }

        @Test
        void rejectsAmountsInAnotherCurrency() {
            assertThatThrownBy(() -> new LimitPolicy("ARS",
                    ars(1_000), usd(2_000), ars(500), ars(5_000), ars(1_000), ars(1_000)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxPurchaseLimit");
        }
    }
}
