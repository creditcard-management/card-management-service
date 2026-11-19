package com.unisono.card.currency.infrastructure.adapter.output.creditlimit;

import com.unisono.card.currency.infrastructure.adapter.output.persistence.adapter.CurrencyRepositoryAdapter;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.entity.CurrencyEntity;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.repository.JpaCurrencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CreditLimitCurrencyAdapter contra PostgreSQL, y el bloqueo de la fila de la moneda que coordina una baja con
 * una asignación de card-limits (que lee la fila con FOR SHARE).
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = CreditLimitCurrencyAdapterTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreditLimitCurrencyAdapterTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Configuration
    @EntityScan(basePackageClasses = CurrencyEntity.class)
    @EnableJpaRepositories(basePackageClasses = JpaCurrencyRepository.class)
    @Import({CreditLimitCurrencyAdapter.class, CurrencyRepositoryAdapter.class})
    static class Config {
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations",
                () -> "classpath:currency-schema.sql,classpath:credit-limits-schema.sql");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }

    @Autowired
    private CreditLimitCurrencyAdapter adapter;
    @Autowired
    private CurrencyRepositoryAdapter currencies;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM credit_limits");
    }

    private void creditLimit(String id, String currency, boolean deleted) {
        jdbc.update("INSERT INTO credit_limits (id, purchase_limit_currency, deleted) VALUES (?, ?, ?)",
                id, currency, deleted);
    }

    @Test
    void findsTheCurrenciesOfTheCreditLimitsIgnoringDeletedOnes() {
        creditLimit("cl-1", "ARS", false);
        creditLimit("cl-2", "ARS", false);
        creditLimit("cl-3", "USD", false);
        creditLimit("cl-4", "EUR", true);

        assertThat(adapter.currenciesWithCreditLimits()).containsExactly("ARS", "USD");
        assertThat(adapter.hasCreditLimitsIn("USD")).isTrue();
        assertThat(adapter.hasCreditLimitsIn("EUR")).isFalse();
        assertThat(adapter.hasCreditLimitsIn("BRL")).isFalse();
    }

    @Test
    void withoutCreditLimitsThereAreNoCurrencies() {
        assertThat(adapter.currenciesWithCreditLimits()).isEmpty();
    }

    @Test
    void deactivationLockMakesACreditLimitAssignmentWait() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // Baja de USD en curso: bloquea la fila (FOR UPDATE) y no confirma todavía
        CompletableFuture<Void> deactivation = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            assertThat(currencies.findByCodeForUpdate("USD")).isPresent();
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // Lo que hace card-limits al asignar: leer la moneda con FOR SHARE. Tiene que esperar a la baja
        CompletableFuture<Boolean> assignmentRead = CompletableFuture.supplyAsync(() -> tx.execute(s ->
                jdbc.queryForObject("SELECT active FROM currencies WHERE code = 'USD' FOR SHARE", Boolean.class)));
        Thread.sleep(300);
        assertThat(assignmentRead).isNotDone();

        release.countDown();
        deactivation.get(10, TimeUnit.SECONDS);
        assertThat(assignmentRead.get(10, TimeUnit.SECONDS)).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
