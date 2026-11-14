package com.unisono.card.limits.infrastructure.adapter.output.persistence;

import com.unisono.card.limits.domain.model.CreditLimit;
import com.unisono.card.limits.domain.model.CreditLimitHistory;
import com.unisono.card.limits.domain.model.LimitType;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.adapter.CreditLimitRepositoryAdapter;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.entity.CreditLimitEntity;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.mapper.CreditLimitEntityMapper;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.repository.JpaCreditLimitRepository;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Ida y vuelta del límite y su historial contra un PostgreSQL real (Testcontainers): lo que se guarda es lo que se
 * lee, con las columnas numeric(15,2) reales, los timestamps y el soft delete. Sin Docker, los tests se saltean.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = CreditLimitPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreditLimitPersistenceTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Configuration
    @EntityScan(basePackageClasses = CreditLimitEntity.class)
    @EnableJpaRepositories(basePackageClasses = JpaCreditLimitRepository.class)
    @Import({CreditLimitRepositoryAdapter.class, CreditLimitEntityMapper.class})
    static class Config {
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired
    private CreditLimitRepositoryAdapter creditLimits;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void savedLimitIsReadBackWithTheSameState() {
        CreditLimit limit = newLimit();
        limit.consumeForPurchase(ars("12345.67"));
        limit.updateTemporalLimits(ars("80000.00"), ars("300000.00"), "Ajuste", "analista");
        limit.updateCashAdvanceLimit(ars("0.00"), "Sin adelantos", "analista");

        inTransaction(() -> creditLimits.save(limit));
        CreditLimit loaded = inTransaction(() -> creditLimits.findById(limit.getId()).orElseThrow());

        assertThat(loaded.getCardId()).isEqualTo(limit.getCardId());
        assertThat(loaded.getPurchaseLimit()).isEqualTo(limit.getPurchaseLimit());
        assertThat(loaded.getCashAdvanceLimit()).isEqualTo(limit.getCashAdvanceLimit());
        assertThat(loaded.getCashAdvanceLimit().isEnabled()).isFalse();
        assertThat(loaded.getConsumedCredit().getAmount()).isEqualTo(ars("12345.67"));
        assertThat(loaded.getAvailableCredit().getAmount()).isEqualTo(ars("487654.33"));
        assertThat(loaded.getDailyLimit()).isEqualTo(limit.getDailyLimit());
        assertThat(loaded.getDailyLimit().getConsumed()).isEqualTo(ars("12345.67"));
        assertThat(loaded.getDailyLimit().getResetDate()).isEqualTo(LocalDate.now());
        assertThat(loaded.getMonthlyLimit()).isEqualTo(limit.getMonthlyLimit());
        // Los timestamps los pone Hibernate (@CreationTimestamp / @UpdateTimestamp)
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull().isAfterOrEqualTo(loaded.getCreatedAt());

        // La lectura por tarjeta y con lock devuelven lo mismo
        assertThat(inTransaction(() -> creditLimits.findByCardId(limit.getCardId()).orElseThrow().getId()))
                .isEqualTo(limit.getId());
        assertThat(inTransaction(() -> creditLimits.findByIdForUpdate(limit.getId()).orElseThrow().getConsumedCredit()))
                .isEqualTo(loaded.getConsumedCredit());
    }

    @Test
    void updatesOverwriteTheRowAndKeepTheCreationDate() {
        CreditLimit limit = newLimit();
        inTransaction(() -> creditLimits.save(limit));
        CreditLimit first = inTransaction(() -> creditLimits.findById(limit.getId()).orElseThrow());

        first.consumeForPurchase(ars("1000.00"));
        first.increasePurchaseLimit(ars("50000.00"), "Aumento", "analista");
        inTransaction(() -> creditLimits.save(first));
        CreditLimit second = inTransaction(() -> creditLimits.findById(limit.getId()).orElseThrow());

        assertThat(second.getPurchaseLimit().getAmount()).isEqualTo(ars("550000.00"));
        assertThat(second.getConsumedCredit().getAmount()).isEqualTo(ars("1000.00"));
        assertThat(second.getAvailableCredit().getAmount()).isEqualTo(ars("549000.00"));
        assertThat(second.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(second.getUpdatedAt()).isAfterOrEqualTo(first.getUpdatedAt());
    }

    @Test
    void historyIsReadBackNewestFirstWithTheSameValues() {
        CreditLimit limit = newLimit();
        limit.increasePurchaseLimit(ars("50000.00"), "Aumento por antigüedad", "analista");
        limit.updateDailyLimit(ars("80000.00"), "Ajuste diario", "analista");
        inTransaction(() -> {
            creditLimits.save(limit);
            limit.getHistory().forEach(creditLimits::saveHistory);
            return null;
        });

        List<CreditLimitHistory> history = inTransaction(() -> creditLimits.findHistoryByCreditLimitId(limit.getId()));

        assertThat(history).hasSize(3);
        assertThat(history).allSatisfy(h -> assertThat(h.getId()).isNotNull());
        assertThat(history).extracting(CreditLimitHistory::getChangedAt).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());

        List<CreditLimitHistory> saved = limit.getHistory();
        for (CreditLimitHistory original : saved) {
            CreditLimitHistory loaded = history.stream()
                    .filter(h -> h.getLimitType() == original.getLimitType() && h.getAction().equals(original.getAction()))
                    .findFirst().orElseThrow();
            assertThat(loaded.getCreditLimitId()).isEqualTo(limit.getId());
            assertThat(loaded.getPreviousValue()).isEqualTo(original.getPreviousValue());
            assertThat(loaded.getNewValue()).isEqualTo(original.getNewValue());
            assertThat(loaded.getDifference()).isEqualTo(original.getDifference());
            assertThat(loaded.getReason()).isEqualTo(original.getReason());
            assertThat(loaded.getChangedBy()).isEqualTo(original.getChangedBy());
            // timestamp de PostgreSQL: redondea a microsegundos
            assertThat(loaded.getChangedAt()).isCloseTo(original.getChangedAt(), within(1, ChronoUnit.MICROS));
        }
        assertThat(history).extracting(CreditLimitHistory::getLimitType)
                .containsExactlyInAnyOrder(LimitType.PURCHASE, LimitType.PURCHASE, LimitType.DAILY);
    }

    @Test
    void softDeletedLimitIsNoLongerFoundButTheRowStays() {
        CreditLimit limit = newLimit();
        inTransaction(() -> creditLimits.save(limit));

        inTransaction(() -> {
            creditLimits.delete(limit.getId());
            return null;
        });

        assertThat(inTransaction(() -> creditLimits.findById(limit.getId()))).isEmpty();
        assertThat(inTransaction(() -> creditLimits.findByIdForUpdate(limit.getId()))).isEmpty();
        assertThat(inTransaction(() -> creditLimits.findByCardId(limit.getCardId()))).isEmpty();
        assertThat(inTransaction(() -> creditLimits.existsByCardId(limit.getCardId()))).isFalse();

        // find() por PK no pasa por las consultas que filtran deleted
        CreditLimitEntity row = inTransaction(() -> entityManager.find(CreditLimitEntity.class, limit.getId()));
        assertThat(row.getDeleted()).isTrue();
        assertThat(row.getDeletedAt()).isNotNull();
        assertThat(row.getConsumedCreditAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ========================================
    // Helpers
    // ========================================

    private static CreditLimit newLimit() {
        return CreditLimit.assign(CardId.of(UUID.randomUUID().toString()), ars("500000.00"), 0.3, "test");
    }

    private static Money ars(String amount) {
        return Money.ofARS(new BigDecimal(amount));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(s -> action.get());
    }
}
