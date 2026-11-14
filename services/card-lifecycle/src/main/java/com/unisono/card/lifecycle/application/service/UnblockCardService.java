package com.unisono.card.lifecycle.application.service;

import com.unisono.card.lifecycle.domain.model.CardLifecycleEvent;
import com.unisono.card.lifecycle.domain.model.CardLifecycleState;
import com.unisono.card.lifecycle.domain.model.StateTransition;
import com.unisono.card.lifecycle.domain.model.TransitionContext;
import com.unisono.card.lifecycle.domain.port.input.UnblockCardUseCase;
import com.unisono.card.lifecycle.domain.port.output.CardDataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Desbloqueo (BLOCKED → ACTIVE). La state machine no permite desbloquear una tarjeta
 * BLOCKED_FRAUD: esa solo se cancela o vence. Desbloquea vía card-data, que limpia
 * block_reason, blocked_at y los intentos fallidos de PIN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnblockCardService implements UnblockCardUseCase {

    private final CardDataClient cardDataClient;
    private final LifecycleTransitionExecutor executor;

    @Override
    public StateTransition execute(UnblockCardCommand command) {
        log.info("Ejecutando desbloqueo de tarjeta: {}", command.cardId().getValue());

        StateTransition transition = executor.execute(
                command.cardId(),
                CardLifecycleEvent.UNBLOCK,
                CardLifecycleState.ACTIVE,
                TransitionContext.of(command.reason(), command.unblockedBy()),
                () -> cardDataClient.unblock(command.cardId(), command.reason(), command.unblockedBy())
        );

        log.info("Tarjeta desbloqueada: {}", command.cardId().getValue());
        return transition;
    }
}
