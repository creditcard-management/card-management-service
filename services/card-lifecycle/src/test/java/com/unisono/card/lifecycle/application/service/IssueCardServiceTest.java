package com.unisono.card.lifecycle.application.service;

import com.unisono.card.lifecycle.domain.exception.CardDataIntegrationException;
import com.unisono.card.lifecycle.domain.exception.InvalidStateTransitionException;
import com.unisono.card.lifecycle.domain.model.CardLifecycleEvent;
import com.unisono.card.lifecycle.domain.model.CardLifecycleState;
import com.unisono.card.lifecycle.domain.model.StateTransition;
import com.unisono.card.lifecycle.domain.model.TransitionContext;
import com.unisono.card.lifecycle.domain.port.input.IssueCardUseCase.IssueCardCommand;
import com.unisono.card.lifecycle.domain.port.output.CardDataClient;
import com.unisono.card.lifecycle.domain.port.output.LifecycleStateMachinePort;
import com.unisono.card.lifecycle.domain.port.output.StateTransitionRepository;
import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IssueCardServiceTest {

    private static final CardId CARD_ID = CardId.of("be546553-f910-4891-8881-e83e1e7f488f");
    private static final CardNumber PAN = CardNumber.of("4532015112830366");
    private static final CVV CVV_OK = CVV.of("123");
    private static final PIN PIN_OK = PIN.of("4821");
    private static final YearMonth EXPIRY = YearMonth.now().plusYears(3);
    private static final ExpirationDate EXPIRATION =
            ExpirationDate.forIssuance(EXPIRY.getMonthValue(), EXPIRY.getYear());

    @Mock
    private CardDataClient cardDataClient;

    @Mock
    private LifecycleStateMachinePort stateMachine;

    @Mock
    private StateTransitionRepository transitionRepository;

    private IssueCardService service;

    private final IssueCardCommand command = new IssueCardCommand(
            CARD_ID, PAN, CVV_OK, PIN_OK, EXPIRATION, "Emisión estándar", "system@bank.com");

    @BeforeEach
    void setUp() {
        LifecycleTransitionExecutor executor =
                new LifecycleTransitionExecutor(cardDataClient, stateMachine, transitionRepository);
        service = new IssueCardService(cardDataClient, executor);
        lenient().when(transitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void issuesWithASingleAtomicCallToCardData() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.APPROVED);
        when(stateMachine.fire(eq(CARD_ID), eq(CardLifecycleState.APPROVED), eq(CardLifecycleEvent.ISSUE), any()))
                .thenReturn(CardLifecycleState.ISSUED);

        StateTransition result = service.execute(command);

        verify(cardDataClient).issue(CARD_ID, PAN, CVV_OK, PIN_OK, EXPIRATION, "Emisión estándar", "system@bank.com");
        verify(cardDataClient, never()).updateStatus(any(), any(), any(), any());
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getFromState()).isEqualTo("APPROVED");
        assertThat(result.getToState()).isEqualTo("ISSUED");
        verify(transitionRepository, never()).save(any());
    }

    @Test
    void passesIssuanceDataToTheStateMachineGuard() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.APPROVED);
        when(stateMachine.fire(any(), any(), any(), any())).thenReturn(CardLifecycleState.ISSUED);

        service.execute(command);

        ArgumentCaptor<TransitionContext> captor = ArgumentCaptor.forClass(TransitionContext.class);
        verify(stateMachine).fire(eq(CARD_ID), any(), any(), captor.capture());
        TransitionContext context = captor.getValue();
        assertThat(context.get(TransitionContext.CARD_NUMBER)).isEqualTo(PAN);
        assertThat(context.get(TransitionContext.CVV)).isEqualTo(CVV_OK);
        assertThat(context.get(TransitionContext.PIN)).isEqualTo(PIN_OK);
        assertThat(context.get(TransitionContext.EXPIRATION_DATE)).isEqualTo(EXPIRATION);
        assertThat(context.getPerformedBy()).isEqualTo("system@bank.com");
    }

    @Test
    void guardRejectionDoesNotTouchCardData() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.REQUESTED);
        when(stateMachine.fire(any(), any(), any(), any()))
                .thenThrow(new InvalidStateTransitionException("no se puede emitir"));

        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(cardDataClient, never()).issue(any(), any(), any(), any(), any(), any(), any());
        StateTransition failed = captured();
        assertThat(failed.getFromState()).isEqualTo("REQUESTED");
        assertThat(failed.getToState()).isEqualTo("ISSUED");
    }

    @Test
    void cardDataRejectionIsRecorded() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.APPROVED);
        when(stateMachine.fire(any(), any(), any(), any())).thenReturn(CardLifecycleState.ISSUED);
        doThrow(new ValidationException("PAN duplicado"))
                .when(cardDataClient).issue(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(ValidationException.class);

        assertThat(captured().getErrorMessage()).isEqualTo("PAN duplicado");
    }

    @Test
    void cardDataUnavailableIsRecorded() {
        when(cardDataClient.getStatus(CARD_ID)).thenReturn(CardStatus.APPROVED);
        when(stateMachine.fire(any(), any(), any(), any())).thenReturn(CardLifecycleState.ISSUED);
        doThrow(new CardDataIntegrationException("card-data no disponible", null))
                .when(cardDataClient).issue(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(CardDataIntegrationException.class);

        StateTransition failed = captured();
        assertThat(failed.isFailed()).isTrue();
        assertThat(failed.getErrorMessage()).isEqualTo("card-data no disponible");
    }

    private StateTransition captured() {
        ArgumentCaptor<StateTransition> captor = ArgumentCaptor.forClass(StateTransition.class);
        verify(transitionRepository).save(captor.capture());
        return captor.getValue();
    }
}
