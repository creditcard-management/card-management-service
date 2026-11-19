package com.unisono.card.limits.infrastructure.adapter.output.currency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CurrencyCatalogAdapter contra PostgreSQL: lee {@code currencies} de card-currency con FOR SHARE, dentro de la
 * transacción de la asignación. La tabla es la de V19 de card-management-database (solo las columnas que lee).
 */
@Testcontainers(disabledWithoutDocker = true)
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = CurrencyCatalogAdapterTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CurrencyCatalogAdapterTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Configuration
    @Import(CurrencyCatalogAdapter.class)
    static class Config {
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CurrencyCatalogAdapter adapter;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void schema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS currencies (code VARCHAR(3) PRIMARY KEY, active BOOLEAN NOT NULL)");
        jdbc.update("DELETE FROM currencies");
        jdbc.update("INSERT INTO currencies (code, active) VALUES ('ARS', true), ('USD', false)");
    }

    private boolean isActive(String code) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(s -> adapter.isActive(code)));
    }

    @Test
    void activeInactiveAndMissingCurrencies() {
        assertThat(isActive("ARS")).isTrue();
        assertThat(isActive("USD")).isFalse();
        assertThat(isActive("EUR")).isFalse();
    }

    @Test
    void requiresTheTransactionOfTheAssignment() {
        // Sin transacción el FOR SHARE no protegería nada: se libera al terminar la consulta
        assertThatThrownBy(() -> adapter.isActive("ARS")).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void keepsTheCurrencyRowLockedUntilTheAssignmentCommits() {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            assertThat(adapter.isActive("ARS")).isTrue();

            // Lo que hace card-currency al desactivar: bloquear la fila. Mientras la asignación no confirme, no puede
            String lockFromAnotherTransaction = "SELECT code FROM currencies WHERE code = 'ARS' FOR UPDATE NOWAIT";
            Throwable blocked = catchFromAnotherTransaction(lockFromAnotherTransaction);
            assertThat(blocked).isInstanceOf(DataAccessException.class);
        });
    }

    private Throwable catchFromAnotherTransaction(String sql) {
        try {
            CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(s -> jdbc.queryForList(sql))).join();
            return null;
        } catch (CompletionException e) {
            return e.getCause();
        }
    }
}
