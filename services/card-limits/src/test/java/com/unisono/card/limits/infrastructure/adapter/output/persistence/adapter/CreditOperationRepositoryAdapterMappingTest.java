package com.unisono.card.limits.infrastructure.adapter.output.persistence.adapter;

import com.unisono.card.limits.domain.model.CreditOperation;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.entity.CreditOperationEntity;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapeo de CreditOperation ↔ credit_operations: cada delta va a su columna y vuelve igual
 */
class CreditOperationRepositoryAdapterMappingTest {

    @Test
    void everyDeltaGoesToItsColumnAndRoundTrips() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 14, 10, 0);
        CreditOperation original = CreditOperation.reconstitute("pay-1", "cl-1", CreditOperation.Type.RELEASE,
                CreditOperation.Status.APPLIED, ars("-25000.00"), ars("-1.11"), ars("-2.22"), ars("-20000.00"),
                LocalDate.of(2026, 11, 14), "pago", createdAt, null);

        CreditOperationEntity entity = CreditOperationRepositoryAdapter.toEntity(original);

        assertThat(entity.getOperationType()).isEqualTo(CreditOperation.Type.RELEASE);
        assertThat(entity.getCreditDelta()).isEqualByComparingTo("-25000.00");
        assertThat(entity.getDailyDelta()).isEqualByComparingTo("-1.11");
        assertThat(entity.getMonthlyDelta()).isEqualByComparingTo("-2.22");
        assertThat(entity.getCashAdvanceDelta()).isEqualByComparingTo("-20000.00");
        assertThat(entity.getCurrency()).isEqualTo("ARS");

        CreditOperation restored = CreditOperationRepositoryAdapter.toDomain(entity);
        assertThat(restored.getType()).isEqualTo(original.getType());
        assertThat(restored.getStatus()).isEqualTo(original.getStatus());
        assertThat(restored.getCreditDelta()).isEqualTo(original.getCreditDelta());
        assertThat(restored.getDailyDelta()).isEqualTo(original.getDailyDelta());
        assertThat(restored.getMonthlyDelta()).isEqualTo(original.getMonthlyDelta());
        assertThat(restored.getCashAdvanceDelta()).isEqualTo(original.getCashAdvanceDelta());
        assertThat(restored.getAppliedOn()).isEqualTo(original.getAppliedOn());
        assertThat(restored.getDescription()).isEqualTo("pago");
        assertThat(restored.getCreatedAt()).isEqualTo(createdAt);
    }

    private static Money ars(String amount) {
        return Money.ofARS(new BigDecimal(amount));
    }
}
