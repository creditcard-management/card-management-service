package com.unisono.card.limits.infrastructure.adapter.output.persistence.mapper;

import com.unisono.card.limits.domain.model.AvailableCredit;
import com.unisono.card.limits.domain.model.CashAdvanceLimit;
import com.unisono.card.limits.domain.model.ConsumedCredit;
import com.unisono.card.limits.domain.model.CreditLimit;
import com.unisono.card.limits.domain.model.CreditLimitHistory;
import com.unisono.card.limits.domain.model.DailyLimit;
import com.unisono.card.limits.domain.model.LimitType;
import com.unisono.card.limits.domain.model.MonthlyLimit;
import com.unisono.card.limits.domain.model.PurchaseLimit;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.entity.CreditLimitEntity;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.entity.CreditLimitHistoryEntity;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CreditLimitEntityMapper: cada campo del dominio va a su columna y vuelve igual. Se compara columna por columna
 * (no solo la ida y vuelta) para detectar campos cruzados, que un mapeo simétricamente equivocado no mostraría.
 */
class CreditLimitEntityMapperTest {

    private static final String ID = "8b0e2c1a-5f4d-4c3b-9a2e-1d0f9e8c7b6a";
    private static final String CARD_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 3, 10, 9, 15, 30);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 11, 2, 18, 45, 5);

    private final CreditLimitEntityMapper mapper = new CreditLimitEntityMapper();

    /** Valores distintos en cada campo para que un cruce de columnas se note */
    private static CreditLimit creditLimit(Money cashAdvance, Money consumed, Money available,
                                           LocalDate dailyResetDate, YearMonth monthlyResetMonth) {
        return CreditLimit.reconstituteFromPersistence(
                ID,
                CardId.of(CARD_ID),
                PurchaseLimit.of(ars("500000.00")),
                CashAdvanceLimit.of(cashAdvance),
                ConsumedCredit.of(consumed),
                AvailableCredit.of(available),
                DailyLimit.reconstituteFromPersistence(ars("80000.00"), ars("12345.67"), dailyResetDate),
                MonthlyLimit.reconstituteFromPersistence(ars("300000.00"), ars("45000.10"), monthlyResetMonth),
                CREATED_AT,
                UPDATED_AT
        );
    }

    private static CreditLimit sample() {
        return creditLimit(ars("120000.00"), ars("37500.55"), ars("462499.45"), LocalDate.now(), YearMonth.now());
    }

    private static Money ars(String amount) {
        return Money.ofARS(new BigDecimal(amount));
    }

    // ========================================
    // CreditLimit
    // ========================================

    @Test
    void everyFieldGoesToItsColumn() {
        LocalDate today = LocalDate.now();
        YearMonth thisMonth = YearMonth.now();

        CreditLimitEntity entity = mapper.toEntity(sample());

        assertThat(entity.getId()).isEqualTo(ID);
        assertThat(entity.getCardId()).isEqualTo(CARD_ID);
        assertThat(entity.getPurchaseLimitAmount()).isEqualByComparingTo("500000.00");
        assertThat(entity.getPurchaseLimitCurrency()).isEqualTo("ARS");
        assertThat(entity.getCashAdvanceLimitAmount()).isEqualByComparingTo("120000.00");
        assertThat(entity.getCashAdvanceLimitCurrency()).isEqualTo("ARS");
        assertThat(entity.getConsumedCreditAmount()).isEqualByComparingTo("37500.55");
        assertThat(entity.getConsumedCreditCurrency()).isEqualTo("ARS");
        assertThat(entity.getAvailableCreditAmount()).isEqualByComparingTo("462499.45");
        assertThat(entity.getAvailableCreditCurrency()).isEqualTo("ARS");
        assertThat(entity.getDailyLimitAmount()).isEqualByComparingTo("80000.00");
        assertThat(entity.getDailyLimitCurrency()).isEqualTo("ARS");
        assertThat(entity.getDailyLimitConsumed()).isEqualByComparingTo("12345.67");
        assertThat(entity.getDailyLimitResetDate()).isEqualTo(today);
        assertThat(entity.getMonthlyLimitAmount()).isEqualByComparingTo("300000.00");
        assertThat(entity.getMonthlyLimitCurrency()).isEqualTo("ARS");
        assertThat(entity.getMonthlyLimitConsumed()).isEqualByComparingTo("45000.10");
        assertThat(entity.getMonthlyLimitResetYear()).isEqualTo(thisMonth.getYear());
        assertThat(entity.getMonthlyLimitResetMonth()).isEqualTo(thisMonth.getMonthValue());
        assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(entity.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(entity.getDeleted()).isFalse();
        assertThat(entity.getDeletedAt()).isNull();
    }

    @Test
    void roundTripKeepsEveryField() {
        CreditLimit original = sample();

        CreditLimit restored = mapper.toDomain(mapper.toEntity(original));

        assertSameState(restored, original);
    }

    @Test
    void storedPeriodsAreKeptAsTheyAreEvenIfTheyAlreadyEnded() {
        // El mapper no resetea: guarda la fecha y lo consumido tal cual; el reset lo resuelven los VO al leer
        LocalDate yesterday = LocalDate.now().minusDays(1);
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        CreditLimit original = creditLimit(ars("120000.00"), ars("37500.55"), ars("462499.45"), yesterday, lastMonth);

        CreditLimit restored = mapper.toDomain(mapper.toEntity(original));

        assertSameState(restored, original);
        assertThat(restored.getDailyLimit().getResetDate()).isEqualTo(yesterday);
        assertThat(restored.getMonthlyLimit().getResetMonth()).isEqualTo(lastMonth);
        // Período vencido: el cupo vuelve a estar completo
        assertThat(restored.getDailyLimit().getAvailable()).isEqualTo(ars("80000.00"));
        assertThat(restored.getMonthlyLimit().getAvailable()).isEqualTo(ars("300000.00"));
    }

    @Test
    void cardWithoutCashAdvancesRoundTrips() {
        CreditLimit original = creditLimit(ars("0.00"), ars("37500.55"), ars("462499.45"),
                LocalDate.now(), YearMonth.now());

        CreditLimit restored = mapper.toDomain(mapper.toEntity(original));

        assertThat(restored.getCashAdvanceLimit().isEnabled()).isFalse();
        assertSameState(restored, original);
    }

    @Test
    void overLimitCardRoundTripsWithNegativeAvailableCredit() {
        // Excedida al deshacer una restauración (revertOperation): consumido > límite
        CreditLimit original = creditLimit(ars("120000.00"), ars("500250.00"), ars("-250.00"),
                LocalDate.now(), YearMonth.now());

        CreditLimit restored = mapper.toDomain(mapper.toEntity(original));

        assertSameState(restored, original);
        assertThat(restored.getOverLimitAmount()).isEqualTo(ars("250.00"));
    }

    @Test
    void nullMapsToNull() {
        assertThat(mapper.toEntity(null)).isNull();
        assertThat(mapper.toDomain(null)).isNull();
        assertThat(mapper.toHistoryEntity(null)).isNull();
        assertThat(mapper.toHistoryDomain(null)).isNull();
    }

    // ========================================
    // CreditLimitHistory
    // ========================================

    @Test
    void historyWithPreviousValueRoundTrips() {
        CreditLimitHistory original = CreditLimitHistory.reconstituteFromPersistence(
                42L, ID, LimitType.DAILY, "UPDATED", ars("100000.00"), ars("80000.00"), ars("-20000.00"),
                "Ajuste por riesgo", "analista@unisono.com", UPDATED_AT, "{\"ticket\":\"RISK-12\"}");

        CreditLimitHistoryEntity entity = mapper.toHistoryEntity(original);

        assertThat(entity.getId()).isEqualTo(42L);
        assertThat(entity.getCreditLimitId()).isEqualTo(ID);
        assertThat(entity.getLimitType()).isEqualTo(LimitType.DAILY);
        assertThat(entity.getAction()).isEqualTo("UPDATED");
        assertThat(entity.getPreviousValueAmount()).isEqualByComparingTo("100000.00");
        assertThat(entity.getPreviousValueCurrency()).isEqualTo("ARS");
        assertThat(entity.getNewValueAmount()).isEqualByComparingTo("80000.00");
        assertThat(entity.getNewValueCurrency()).isEqualTo("ARS");
        assertThat(entity.getDifferenceAmount()).isEqualByComparingTo("-20000.00");
        assertThat(entity.getDifferenceCurrency()).isEqualTo("ARS");
        assertThat(entity.getReason()).isEqualTo("Ajuste por riesgo");
        assertThat(entity.getChangedBy()).isEqualTo("analista@unisono.com");
        assertThat(entity.getChangedAt()).isEqualTo(UPDATED_AT);
        assertThat(entity.getMetadata()).isEqualTo("{\"ticket\":\"RISK-12\"}");

        assertSameHistory(mapper.toHistoryDomain(entity), original);
    }

    @Test
    void historyWithoutPreviousValueRoundTrips() {
        // previous_value_* es nullable: el mapper respeta el null en las dos direcciones
        CreditLimitHistory original = CreditLimitHistory.reconstituteFromPersistence(
                null, ID, LimitType.PURCHASE, "ASSIGNED", null, ars("500000.00"), ars("500000.00"),
                "Asignación inicial de límite", "admin", UPDATED_AT, null);

        CreditLimitHistoryEntity entity = mapper.toHistoryEntity(original);

        assertThat(entity.getId()).isNull();
        assertThat(entity.getPreviousValueAmount()).isNull();
        assertThat(entity.getPreviousValueCurrency()).isNull();

        CreditLimitHistory restored = mapper.toHistoryDomain(entity);
        assertThat(restored.getPreviousValue()).isNull();
        assertSameHistory(restored, original);
    }

    @Test
    void assignmentHistoryRoundTripsWithZeroPreviousValue() {
        CreditLimitHistory original = CreditLimitHistory.recordAssignment(
                ID, LimitType.PURCHASE, ars("500000.00"), "Asignación inicial de límite", "admin");

        CreditLimitHistory restored = mapper.toHistoryDomain(mapper.toHistoryEntity(original));

        assertThat(restored.getPreviousValue()).isEqualTo(Money.zero());
        assertSameHistory(restored, original);
    }

    // ========================================
    // Helpers
    // ========================================

    static void assertSameState(CreditLimit actual, CreditLimit expected) {
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getCardId()).isEqualTo(expected.getCardId());
        assertThat(actual.getPurchaseLimit()).isEqualTo(expected.getPurchaseLimit());
        assertThat(actual.getCashAdvanceLimit()).isEqualTo(expected.getCashAdvanceLimit());
        assertThat(actual.getConsumedCredit()).isEqualTo(expected.getConsumedCredit());
        assertThat(actual.getAvailableCredit()).isEqualTo(expected.getAvailableCredit());
        assertThat(actual.getDailyLimit()).isEqualTo(expected.getDailyLimit());
        assertThat(actual.getMonthlyLimit()).isEqualTo(expected.getMonthlyLimit());
        assertThat(actual.getCreatedAt()).isEqualTo(expected.getCreatedAt());
        assertThat(actual.getUpdatedAt()).isEqualTo(expected.getUpdatedAt());
    }

    static void assertSameHistory(CreditLimitHistory actual, CreditLimitHistory expected) {
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getCreditLimitId()).isEqualTo(expected.getCreditLimitId());
        assertThat(actual.getLimitType()).isEqualTo(expected.getLimitType());
        assertThat(actual.getAction()).isEqualTo(expected.getAction());
        assertThat(actual.getPreviousValue()).isEqualTo(expected.getPreviousValue());
        assertThat(actual.getNewValue()).isEqualTo(expected.getNewValue());
        assertThat(actual.getDifference()).isEqualTo(expected.getDifference());
        assertThat(actual.getReason()).isEqualTo(expected.getReason());
        assertThat(actual.getChangedBy()).isEqualTo(expected.getChangedBy());
        assertThat(actual.getChangedAt()).isEqualTo(expected.getChangedAt());
        assertThat(actual.getMetadata()).isEqualTo(expected.getMetadata());
    }
}
