package com.unisono.card.limits.infrastructure.adapter.output.persistence;

import com.unisono.card.limits.domain.port.output.LimitPolicyPort;
import org.springframework.context.annotation.Bean;
import com.unisono.card.limits.domain.model.TestLimitPolicies;
import com.unisono.card.limits.domain.model.CreditLimit;
import com.unisono.card.limits.domain.port.input.RecalculateAvailableCreditUseCase.ConsumeCreditCommand;
import com.unisono.card.limits.domain.port.input.UpdateCreditLimitUseCase.IncreasePurchaseLimitCommand;
import com.unisono.card.limits.domain.port.output.CardValidationPort;
import com.unisono.card.limits.domain.service.CreditLimitEventEmitter;
import com.unisono.card.limits.domain.service.CreditLimitManagementService;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.adapter.CreditLimitOutboxEventAdapter;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.adapter.CreditLimitRepositoryAdapter;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.adapter.CreditOperationRepositoryAdapter;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.entity.CreditLimitEntity;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.mapper.CreditLimitEntityMapper;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.repository.JpaCreditLimitRepository;
import com.unisono.card.limits.infrastructure.adapter.output.persistence.repository.JpaCreditOperationRepository;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Lock del límite (SELECT ... FOR UPDATE) contra un PostgreSQL real (Testcontainers): las modificaciones de un
 * mismo límite se serializan, así que no hay lost updates ni se consume más crédito que el disponible.
 * Sin Docker, los tests se saltean.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = CreditLimitConcurrencyTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreditLimitConcurrencyTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /** Hilos concurrentes: no más que el pool (10), para que la espera sea por el lock y no por conexiones */
    private static final int THREADS = 8;

    @Configuration
    @EntityScan(basePackageClasses = CreditLimitEntity.class)
    @EnableJpaRepositories(basePackageClasses = JpaCreditLimitRepository.class)
    @Import({CreditLimitRepositoryAdapter.class, CreditOperationRepositoryAdapter.class, CreditLimitEntityMapper.class,
            CreditLimitOutboxEventAdapter.class, CreditLimitEventEmitter.class, CreditLimitManagementService.class})
    static class Config {
        @Bean
        LimitPolicyPort limitPolicyPort() {
            return TestLimitPolicies.port();
        }
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
    private CreditLimitManagementService service;

    @Autowired
    private CreditLimitRepositoryAdapter creditLimits;

    @Autowired
    private JpaCreditOperationRepository operations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private CardValidationPort cardValidationPort;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    private String creditLimitId;

    @BeforeEach
    void setUp() {
        when(cardValidationPort.isCardActive(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentConsumptionsNeverExceedTheAvailableCredit() throws Exception {
        creditLimitId = assign(10_000);

        // 16 compras de 1.000 a la vez sobre 10.000 disponibles: entran exactamente 10
        List<Future<?>> results = runConcurrently(16, i -> () -> service.consumeForPurchase(
                new ConsumeCreditCommand(creditLimitId, Money.ofARS(1_000), "compra " + i, creditLimitId + "-op-" + i)));

        int approved = 0;
        int declined = 0;
        for (Future<?> result : results) {
            try {
                result.get(30, TimeUnit.SECONDS);
                approved++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessRuleException.class)
                        .hasMessageContaining("Crédito insuficiente");
                declined++;
            }
        }

        CreditLimit limit = reload();
        assertThat(approved).isEqualTo(10);
        assertThat(declined).isEqualTo(6);
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(10_000));
        assertThat(limit.getAvailableCredit().getAmount().isZero()).isTrue();
        assertThat(limit.getDailyLimit().getConsumed()).isEqualTo(Money.ofARS(10_000));
        assertThat(operations.findAll()).filteredOn(op -> op.getCreditLimitId().equals(creditLimitId)).hasSize(10);
    }

    @Test
    void concurrentLimitChangesAndConsumptionsDoNotLoseUpdates() throws Exception {
        creditLimitId = assign(100_000);

        // Pares: un aumento de 1.000 y una compra de 1.000 a la vez. Sin lock, guardar la fila completa
        // pisaría lo que hizo la otra operación
        int pairs = 10;
        List<Future<?>> results = runConcurrently(pairs * 2, i -> i % 2 == 0
                ? () -> service.increasePurchaseLimit(new IncreasePurchaseLimitCommand(
                        creditLimitId, Money.ofARS(1_000), "aumento " + i, "analista"))
                : () -> service.consumeForPurchase(new ConsumeCreditCommand(
                        creditLimitId, Money.ofARS(1_000), "compra " + i, creditLimitId + "-op-" + i)));
        for (Future<?> result : results) {
            result.get(30, TimeUnit.SECONDS);
        }

        CreditLimit limit = reload();
        assertThat(limit.getPurchaseLimit().getAmount()).isEqualTo(Money.ofARS(100_000 + pairs * 1_000));
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(pairs * 1_000));
        assertThat(limit.getAvailableCredit().getAmount()).isEqualTo(Money.ofARS(100_000));
        assertThat(creditLimits.findHistoryByCreditLimitId(creditLimitId)).hasSize(1 + pairs);
    }

    @Test
    void aLimitChangeReadsTheLimitOnlyAfterTheLockIsReleased() throws Exception {
        creditLimitId = assign(100_000);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // Una transacción toma el lock, consume 5.000 y retiene el lock (sin commitear) hasta que el test la libera
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            CreditLimit limit = creditLimits.findByIdForUpdate(creditLimitId).orElseThrow();
            limit.consumeForPurchase(Money.ofARS(5_000));
            creditLimits.save(limit);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> increase = executor.submit(() -> service.increasePurchaseLimit(new IncreasePurchaseLimitCommand(
                creditLimitId, Money.ofARS(1_000), "aumento", "analista")));

        // Espera el FOR UPDATE de la otra transacción
        assertThatThrownBy(() -> increase.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        increase.get(10, TimeUnit.SECONDS);

        // El aumento leyó el límite después del commit del consumo: los dos cambios quedan
        CreditLimit limit = reload();
        assertThat(limit.getPurchaseLimit().getAmount()).isEqualTo(Money.ofARS(101_000));
        assertThat(limit.getConsumedCredit().getAmount()).isEqualTo(Money.ofARS(5_000));
        assertThat(limit.getAvailableCredit().getAmount()).isEqualTo(Money.ofARS(96_000));
    }

    // ========================================
    // Helpers
    // ========================================

    /** Asigna un límite y guarda su historial, como el caso de uso de asignación */
    private String assign(long purchaseLimit) {
        CreditLimit limit = CreditLimit.assign(CardId.of(UUID.randomUUID().toString()), Money.ofARS(purchaseLimit),
                0.3, TestLimitPolicies.ars(), "test");
        return new TransactionTemplate(transactionManager).execute(s -> {
            creditLimits.save(limit);
            limit.getHistory().forEach(creditLimits::saveHistory);
            return limit.getId();
        });
    }

    private CreditLimit reload() {
        return creditLimits.findById(creditLimitId).orElseThrow();
    }

    /** Lanza las tareas a la vez: todas esperan la misma largada */
    private List<Future<?>> runConcurrently(int tasks, java.util.function.IntFunction<Runnable> task) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            Runnable runnable = task.apply(i);
            futures.add(executor.submit((Callable<Void>) () -> {
                await(start);
                runnable.run();
                return null;
            }));
        }
        start.countDown();
        return futures;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timeout esperando el latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
