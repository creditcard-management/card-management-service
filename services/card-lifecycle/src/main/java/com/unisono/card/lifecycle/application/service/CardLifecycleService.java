package com.unisono.card.lifecycle.application.service;

import com.unisono.card.lifecycle.domain.model.StateTransition;
import com.unisono.card.lifecycle.domain.port.input.GetLifecycleHistoryUseCase;
import com.unisono.card.lifecycle.domain.port.output.StateTransitionRepository;
import com.unisono.card.shared.domain.valueobject.CardId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Consultas del historial de transiciones. Las transiciones se ejecutan en los servicios
 * de cada caso de uso (Approve/Reject/Issue/Activate/Block/CancelCardService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardLifecycleService implements GetLifecycleHistoryUseCase {

    private final StateTransitionRepository transitionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<StateTransition> getHistory(CardId cardId) {
        log.info("Obteniendo historial de tarjeta: {}", cardId.getValue());
        return transitionRepository.findByCardId(cardId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StateTransition> getSuccessfulTransitions(CardId cardId) {
        log.info("Obteniendo historial exitoso de tarjeta: {}", cardId.getValue());
        return transitionRepository.findSuccessfulByCardId(cardId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StateTransition> getFailedTransitions(CardId cardId) {
        log.info("Obteniendo historial fallido de tarjeta: {}", cardId.getValue());
        return transitionRepository.findFailedByCardId(cardId);
    }
}
