package com.unisono.card.lifecycle.infrastructure.adapter.output.persistence;

import com.unisono.card.lifecycle.infrastructure.adapter.output.persistence.entity.StateTransitionEntity;
import com.unisono.card.lifecycle.infrastructure.adapter.output.persistence.repository.JpaStateTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Historial de transiciones contra un PostgreSQL real (Testcontainers): la fecha guardada es la del cambio, no la
 * del insert. Los tests del consumer usan el repositorio mockeado y no ven lo que hace Hibernate al persistir.
 * Sin Docker, los tests se saltean.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = StateTransitionPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StateTransitionPersistenceTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Configuration
    @EntityScan(basePackageClasses = StateTransitionEntity.class)
    @EnableJpaRepositories(basePackageClasses = JpaStateTransitionRepository.class)
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
    private JpaStateTransitionRepository repository;

    private String cardId;

    @BeforeEach
    void newCard() {
        cardId = UUID.randomUUID().toString();
    }

    @Test
    void keepsTheDateOfTheChangeWithMicrosecondPrecision() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 11, 19, 18, 9, 28, 761_709_000);

        StateTransitionEntity saved = repository.saveAndFlush(transition("BLOCK_FRAUD", true, changedAt));

        assertThat(repository.findById(saved.getId()).orElseThrow().getTransitionedAt()).isEqualTo(changedAt);
    }

    @Test
    void transitionWithoutDateGetsTheCurrentOne() {
        StateTransitionEntity saved = repository.saveAndFlush(transition("UNBLOCK", false, null));

        assertThat(repository.findById(saved.getId()).orElseThrow().getTransitionedAt())
                .isCloseTo(LocalDateTime.now(), within(1, ChronoUnit.MINUTES));
    }

    /**
     * Caso del e2e: el bloqueo por fraude se consume de Kafka después de que el desbloqueo fallido (sincrónico) ya se
     * guardó. El historial tiene que seguir el orden de los hechos, no el de los inserts.
     */
    @Test
    void historyFollowsTheOrderOfTheChangesNotOfTheInserts() {
        LocalDateTime blockedFraud = LocalDateTime.of(2026, 11, 19, 18, 9, 28, 761_709_000);
        LocalDateTime failedUnblock = blockedFraud.plusNanos(480_000_000);
        LocalDateTime cancelled = failedUnblock.plusNanos(490_000_000);

        repository.saveAndFlush(transition("UNBLOCK", false, failedUnblock));
        repository.saveAndFlush(transition("BLOCK_FRAUD", true, blockedFraud));
        repository.saveAndFlush(transition("CANCEL", true, cancelled));

        List<String> newestFirst = repository.findByCardIdOrderByTransitionedAtDesc(cardId).stream()
                .map(StateTransitionEntity::getEvent)
                .toList();

        assertThat(newestFirst).containsExactly("CANCEL", "UNBLOCK", "BLOCK_FRAUD");
    }

    private StateTransitionEntity transition(String event, boolean successful, LocalDateTime at) {
        return StateTransitionEntity.builder()
                .cardId(cardId)
                .fromState("ACTIVE")
                .toState("BLOCKED_FRAUD")
                .event(event)
                .performedBy("test")
                .successful(successful)
                .errorMessage(successful ? null : "Invalid transition")
                .eventId(successful ? UUID.randomUUID().toString() : null)
                .transitionedAt(at)
                .build();
    }
}
