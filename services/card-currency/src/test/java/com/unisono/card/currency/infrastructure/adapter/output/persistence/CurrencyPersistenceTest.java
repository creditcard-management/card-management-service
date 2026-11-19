package com.unisono.card.currency.infrastructure.adapter.output.persistence;

import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.model.RateSource;
import com.unisono.card.currency.domain.model.SupportedCurrency;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.adapter.CurrencyRepositoryAdapter;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.adapter.ExchangeRateRepositoryAdapter;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.entity.ExchangeRateEntity;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.repository.JpaExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapters contra un PostgreSQL real (Testcontainers) con el schema de la migración V19: valida el mapeo
 * ({@code ddl-auto=validate}), la búsqueda por vigencia y el índice único de cotización abierta por par.
 * Sin Docker, los tests se saltean.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = CurrencyPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CurrencyPersistenceTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Configuration
    @EntityScan(basePackageClasses = ExchangeRateEntity.class)
    @EnableJpaRepositories(basePackageClasses = JpaExchangeRateRepository.class)
    @Import({CurrencyRepositoryAdapter.class, ExchangeRateRepositoryAdapter.class})
    static class Config {
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:currency-schema.sql");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    private static final CurrencyPair USD_ARS = CurrencyPair.of("USD", "ARS");
    // Postgres guarda microsegundos
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private CurrencyRepositoryAdapter currencies;
    @Autowired
    private ExchangeRateRepositoryAdapter rates;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM exchange_rates");
        tx = new TransactionTemplate(transactionManager);
    }

    private static ExchangeRate rate(String value, Instant from) {
        return ExchangeRate.publish(USD_ARS, new BigDecimal(value), from, RateSource.MANUAL, T0);
    }

    @Test
    void migrationSeedsInitialCurrencies() {
        assertThat(currencies.findAll(true)).extracting(SupportedCurrency::getCode)
                .contains("ARS", "EUR", "USD");
        assertThat(currencies.existsByCode("USD")).isTrue();
    }

    @Test
    void currencyRoundTrip() {
        SupportedCurrency brl = SupportedCurrency.register("BRL", "Real", T0);
        currencies.save(brl);
        brl.deactivate(false, T0.plusSeconds(1));
        currencies.save(brl);

        SupportedCurrency loaded = currencies.findByCode("BRL").orElseThrow();
        assertThat(loaded.isActive()).isFalse();
        assertThat(loaded.getUpdatedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(currencies.findAll(true)).extracting(SupportedCurrency::getCode).doesNotContain("BRL");
    }

    @Test
    void closeThenInsertKeepsOneOpenRateAndFindsByValidity() {
        ExchangeRate first = rates.save(rate("1000.12345678", T0));
        ExchangeRate second = rate("1100", T0.plusSeconds(3600));
        // Lo mismo que ExchangeRateService.publish: lock de la abierta, cerrarla e insertar la nueva
        tx.executeWithoutResult(status -> {
            ExchangeRate open = rates.findOpenForUpdate(USD_ARS).orElseThrow();
            open.supersededBy(second);
            rates.save(open);
            rates.save(second);
        });

        Optional<ExchangeRate> openAfter = tx.execute(status -> rates.findOpenForUpdate(USD_ARS));
        assertThat(openAfter).get()
                .extracting(ExchangeRate::getRateId).isEqualTo(second.getRateId());
        ExchangeRate atStart = rates.findValidAt(USD_ARS, T0).orElseThrow();
        assertThat(atStart.getRateId()).isEqualTo(first.getRateId());
        assertThat(atStart.getRate()).isEqualByComparingTo("1000.12345678");
        assertThat(atStart.getValidTo()).isEqualTo(T0.plusSeconds(3600));
        assertThat(rates.findValidAt(USD_ARS, T0.plusSeconds(3600))).get()
                .extracting(ExchangeRate::getRateId).isEqualTo(second.getRateId());
        assertThat(rates.findValidAt(USD_ARS, T0.minusSeconds(1))).isEmpty();
        assertThat(rates.findValidAt(USD_ARS.inverse(), T0)).isEmpty();

        List<ExchangeRate> history = rates.findHistory(USD_ARS, 10);
        assertThat(history).extracting(ExchangeRate::getRateId)
                .containsExactly(second.getRateId(), first.getRateId());
        assertThat(rates.findHistory(USD_ARS, 1)).hasSize(1);
    }

    @Test
    void schemaRejectsTwoOpenRatesForThePair() {
        rates.save(rate("1000", T0));

        assertThatThrownBy(() -> rates.save(rate("1100", T0.plusSeconds(60))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cancelOpenScheduledRateDeletesItAndReopensThePrevious() {
        ExchangeRate current = rates.save(rate("1000", T0));
        ExchangeRate scheduled = rate("1100", T0.plusSeconds(3600));
        tx.executeWithoutResult(status -> {
            ExchangeRate open = rates.findOpenForUpdate(USD_ARS).orElseThrow();
            open.supersededBy(scheduled);
            rates.save(open);
            rates.save(scheduled);
        });

        // Lo mismo que ExchangeRateService.cancelScheduled: DELETE antes de reabrir (índice único)
        tx.executeWithoutResult(status -> {
            ExchangeRate locked = rates.findByIdForUpdate(scheduled.getRateId()).orElseThrow();
            ExchangeRate previous = rates.findEndingAt(USD_ARS, locked.getValidFrom()).orElseThrow();
            assertThat(previous.getRateId()).isEqualTo(current.getRateId());
            rates.delete(locked);
            previous.resumeAfter(locked);
            rates.save(previous);
        });

        assertThat(rates.findByIdForUpdate(scheduled.getRateId())).isEmpty();
        Optional<ExchangeRate> open = tx.execute(status -> rates.findOpenForUpdate(USD_ARS));
        assertThat(open).get().extracting(ExchangeRate::getRateId).isEqualTo(current.getRateId());
    }

    @Test
    void existsForPairDistinguishesDirections() {
        rates.save(rate("1000", T0));

        assertThat(rates.existsForPair(USD_ARS)).isTrue();
        assertThat(rates.existsForPair(USD_ARS.inverse())).isFalse();
        Optional<ExchangeRate> missing = tx.execute(status -> rates.findByIdForUpdate("missing"));
        assertThat(missing).isEmpty();
    }

    @Test
    void lockUsesTheCardCurrencyNamespaceAndTheSameKeyForBothDirections() {
        Integer locks = tx.execute(status -> {
            rates.findOpenForUpdate(USD_ARS.inverse());
            // Mismo backend: la consulta ve el lock de su propia transacción
            return jdbc.queryForObject("""
                    SELECT count(*) FROM pg_locks
                    WHERE locktype = 'advisory' AND pid = pg_backend_pid()
                      AND classid = ? AND objid = ? AND objsubid = 2
                    """, Integer.class,
                    ExchangeRateRepositoryAdapter.LOCK_NAMESPACE,
                    ExchangeRateRepositoryAdapter.pairLockKey(USD_ARS));
        });

        assertThat(locks).isEqualTo(1);
    }
}
