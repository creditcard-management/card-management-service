package com.unisono.card.lifecycle.application.service;

import com.unisono.card.lifecycle.domain.exception.CardDataIntegrationException;
import com.unisono.card.lifecycle.domain.exception.InvalidStateTransitionException;
import com.unisono.card.lifecycle.domain.model.CardLifecycleEvent;
import com.unisono.card.lifecycle.domain.model.CardLifecycleState;
import com.unisono.card.lifecycle.domain.model.StateTransition;
import com.unisono.card.lifecycle.domain.model.TransitionContext;
import com.unisono.card.lifecycle.domain.port.input.ApproveCardUseCase.ApproveCardCommand;
import com.unisono.card.lifecycle.domain.port.output.CardDataClient;
import com.unisono.card.lifecycle.domain.port.output.LifecycleStateMachinePort;
import com.unisono.card.lifecycle.domain.port.output.StateTransitionRepository;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.NotFoundException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.CardStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApproveCardServiceTest {

    private static final CardId CARD_ID = CardId.of("be546553-f910-4891-8881-e83e1e7f488f");

    @Mock
    private CardDataClient cardDataClient;

    @Mock
    private LifecycleStateMachinePort stateMachine;

    @Mock
    private StateTransitionRepository transitionRepository;

    private ApproveCardService service;

    private final ApproveCardCommand command =
            new ApproveCardCommand(CARD_ID, "Cumple requisitos", "admin@bank.com");

    @BeforeEach
    void setUp() {
        LifecycleTransitionExecutor executor =
                new LifecycleTransitionExecutor(cardDataClient, stateMachine, transitionRepository);
        service = new ApproveCardService(cardDataClient, executor);
        lenient().when(transitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void approvesThroughCardDataWithReasonAndAuthor() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.REQUESTED);
        when(stateMachine.fire(eq(CARD_ID), eq(CardLifecycleState.REQUESTED), eq(CardLifecycleEvent.APPROVE), any()))
                .thenReturn(CardLifecycleState.APPROVED);

        StateTransition result = service.execute(command);

        verify(cardDataClient).updateStatus(CARD_ID, "APPROVED", "Cumple requisitos", "admin@bank.com");
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getFromState()).isEqualTo("REQUESTED");
        assertThat(result.getToState()).isEqualTo("APPROVED");
        assertThat(result.getEvent()).isEqualTo("APPROVE");
        // La fila exitosa la registra el consumidor de card.approved
        verify(transitionRepository, never()).save(any());
    }

    @Test
    void passesReasonAndAuthorToTheStateMachine() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.REQUESTED);
        when(stateMachine.fire(any(), any(), any(), any())).thenReturn(CardLifecycleState.APPROVED);

        service.execute(command);

        ArgumentCaptor<TransitionContext> context = ArgumentCaptor.forClass(TransitionContext.class);
        verify(stateMachine).fire(eq(CARD_ID), any(), any(), context.capture());
        assertThat(context.getValue().getReason()).isEqualTo("Cumple requisitos");
        assertThat(context.getValue().getPerformedBy()).isEqualTo("admin@bank.com");
    }

    @Test
    void rejectedTransitionIsRecordedWithoutCallingCardData() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.ACTIVE);
        when(stateMachine.fire(any(), any(), any(), any()))
                .thenThrow(new InvalidStateTransitionException("no se puede aprobar"));

        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(cardDataClient, never()).updateStatus(any(), any(), any(), any());
        StateTransition failed = captured();
        assertThat(failed.isFailed()).isTrue();
        assertThat(failed.getFromState()).isEqualTo("ACTIVE");
        assertThat(failed.getToState()).isEqualTo("APPROVED");
        assertThat(failed.getErrorMessage()).isEqualTo("no se puede aprobar");
        assertThat(failed.getPerformedBy()).isEqualTo("admin@bank.com");
    }

    @Test
    void cardDataRejectionIsRecorded() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.REQUESTED);
        when(stateMachine.fire(any(), any(), any(), any())).thenReturn(CardLifecycleState.APPROVED);
        doThrow(new BusinessRuleException("Transición no permitida"))
                .when(cardDataClient).updateStatus(any(), any(), any(), any());

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(BusinessRuleException.class);

        assertThat(captured().getErrorMessage()).isEqualTo("Transición no permitida");
    }

    @Test
    void cardDataUnavailableIsRecorded() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.REQUESTED);
        when(stateMachine.fire(any(), any(), any(), any())).thenReturn(CardLifecycleState.APPROVED);
        doThrow(new CardDataIntegrationException("card-data no disponible", null))
                .when(cardDataClient).updateStatus(any(), any(), any(), any());

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(CardDataIntegrationException.class);

        assertThat(captured().getFromState()).isEqualTo("REQUESTED");
    }

    @Test
    void unknownCardIsNotRecorded() {
        when(cardDataClient.getStatus(CARD_ID)).thenThrow(new NotFoundException("Tarjeta no encontrada"));

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(stateMachine, transitionRepository);
    }

    private StateTransition captured() {
        ArgumentCaptor<StateTransition> captor = ArgumentCaptor.forClass(StateTransition.class);
        verify(transitionRepository).save(captor.capture());
        return captor.getValue();
    }
}
