package com.unisono.card.lifecycle.infrastructure.adapter.input.rest;

import com.unisono.card.lifecycle.domain.exception.CardDataIntegrationException;
import com.unisono.card.lifecycle.domain.exception.InvalidStateTransitionException;
import com.unisono.card.lifecycle.domain.model.StateTransition;
import com.unisono.card.lifecycle.domain.port.input.*;
import com.unisono.card.lifecycle.infrastructure.adapter.input.rest.mapper.LifecycleDtoMapper;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.NotFoundException;
import com.unisono.card.shared.domain.valueobject.CardId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa web: mapeo request → command, validación de bodies y traducción de errores
 * de {@link GlobalExceptionHandler}. Los casos de uso están mockeados.
 */
@WebMvcTest(CardLifecycleController.class)
@Import(LifecycleDtoMapper.class)
// @EnableJpaAuditing de la clase principal necesita un contexto de mapeo JPA
@MockBean(JpaMetamodelMappingContext.class)
class CardLifecycleControllerTest {

    private static final String CARD = "be546553-f910-4891-8881-e83e1e7f488f";
    private static final CardId CARD_ID = CardId.of(CARD);
    private static final String BASE = "/api/v1/card-lifecycle/" + CARD;

    @Autowired
    private MockMvc mvc;

    @MockBean private ApproveCardUseCase approveCardUseCase;
    @MockBean private RejectCardUseCase rejectCardUseCase;
    @MockBean private IssueCardUseCase issueCardUseCase;
    @MockBean private ActivateCardUseCase activateCardUseCase;
    @MockBean private BlockCardUseCase blockCardUseCase;
    @MockBean private UnblockCardUseCase unblockCardUseCase;
    @MockBean private CancelCardUseCase cancelCardUseCase;
    @MockBean private GetLifecycleHistoryUseCase getLifecycleHistoryUseCase;

    // =============================================
    // Transiciones
    // =============================================

    @Test
    void approveMapsRequestToCommand() throws Exception {
        postJson("/approve", "{\"reason\":\"Cumple\",\"approvedBy\":\"admin\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardId").value(CARD))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        verify(approveCardUseCase).execute(new ApproveCardUseCase.ApproveCardCommand(CARD_ID, "Cumple", "admin"));
    }

    @Test
    void approveWithoutReasonIs400() throws Exception {
        postJson("/approve", "{\"approvedBy\":\"admin\"}").andExpect(status().isBadRequest());

        verifyNoInteractions(approveCardUseCase);
    }

    @Test
    void rejectMapsRequestToCommand() throws Exception {
        postJson("/reject", "{\"reason\":\"No califica\",\"rejectedBy\":\"admin\"}").andExpect(status().isOk());

        verify(rejectCardUseCase).execute(new RejectCardUseCase.RejectCardCommand(CARD_ID, "No califica", "admin"));
    }

    @Test
    void issueBuildsValueObjects() throws Exception {
        YearMonth expiry = YearMonth.now().plusYears(3);

        postJson("/issue", issueBody("4821", expiry))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));

        ArgumentCaptor<IssueCardUseCase.IssueCardCommand> captor =
                ArgumentCaptor.forClass(IssueCardUseCase.IssueCardCommand.class);
        verify(issueCardUseCase).execute(captor.capture());
        IssueCardUseCase.IssueCardCommand command = captor.getValue();
        assertThat(command.cardId()).isEqualTo(CARD_ID);
        assertThat(command.cardNumber().getValue()).isEqualTo("4532015112830366");
        assertThat(command.pin().getValue()).isEqualTo("4821");
        assertThat(command.expirationDate().getMonth()).isEqualTo(expiry.getMonthValue());
        assertThat(command.expirationDate().getYear()).isEqualTo(expiry.getYear());
        assertThat(command.issuedBy()).isEqualTo("system");
    }

    @Test
    void issueWithObviousPinIs400FromValueObject() throws Exception {
        postJson("/issue", issueBody("1234", YearMonth.now().plusYears(3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value("El PIN no puede ser una secuencia obvia (ej: 1234)"));

        verifyNoInteractions(issueCardUseCase);
    }

    @Test
    void issueWithInvalidPanFormatIs400() throws Exception {
        postJson("/issue", issueBody("4821", YearMonth.now().plusYears(3)).replace("4532015112830366", "12ab"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(issueCardUseCase);
    }

    @Test
    void activateMapsRequestToCommand() throws Exception {
        postJson("/activate", "{\"pin\":\"4821\",\"reason\":\"App\",\"activatedBy\":\"cliente\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        ArgumentCaptor<ActivateCardUseCase.ActivateCardCommand> captor =
                ArgumentCaptor.forClass(ActivateCardUseCase.ActivateCardCommand.class);
        verify(activateCardUseCase).execute(captor.capture());
        assertThat(captor.getValue().pin().getValue()).isEqualTo("4821");
        assertThat(captor.getValue().activatedBy()).isEqualTo("cliente");
    }

    @Test
    void activateWithShortPinIs400() throws Exception {
        postJson("/activate", "{\"pin\":\"48\",\"reason\":\"App\",\"activatedBy\":\"cliente\"}")
                .andExpect(status().isBadRequest());

        verifyNoInteractions(activateCardUseCase);
    }

    @Test
    void blockDefaultsToTemporaryBlock() throws Exception {
        postJson("/block", "{\"reason\":\"Pérdida\",\"blockedBy\":\"usuario\"}").andExpect(status().isOk());

        verify(blockCardUseCase).executeBlock(new BlockCardUseCase.BlockCardCommand(CARD_ID, "Pérdida", "usuario"));
        verify(blockCardUseCase, never()).executeBlockFraud(any());
    }

    @Test
    void blockTypeFraudUsesFraudBlock() throws Exception {
        postJson("/block", "{\"reason\":\"Fraude\",\"blockedBy\":\"antifraude\",\"blockType\":\"fraud\"}")
                .andExpect(status().isOk());

        verify(blockCardUseCase).executeBlockFraud(
                new BlockCardUseCase.BlockCardCommand(CARD_ID, "Fraude", "antifraude"));
        verify(blockCardUseCase, never()).executeBlock(any());
    }

    @Test
    void unblockMapsRequestToCommand() throws Exception {
        postJson("/unblock", "{\"reason\":\"Recuperada\",\"unblockedBy\":\"operador\"}").andExpect(status().isOk());

        verify(unblockCardUseCase).execute(
                new UnblockCardUseCase.UnblockCardCommand(CARD_ID, "Recuperada", "operador"));
    }

    @Test
    void unblockWithoutAuthorIs400() throws Exception {
        postJson("/unblock", "{\"reason\":\"Recuperada\"}").andExpect(status().isBadRequest());

        verifyNoInteractions(unblockCardUseCase);
    }

    @Test
    void cancelMapsRequestToCommand() throws Exception {
        postJson("/cancel", "{\"reason\":\"Baja\",\"cancelledBy\":\"cliente\"}").andExpect(status().isOk());

        verify(cancelCardUseCase).execute(new CancelCardUseCase.CancelCardCommand(CARD_ID, "Baja", "cliente"));
    }

    // =============================================
    // Errores (GlobalExceptionHandler)
    // =============================================

    @Test
    void invalidTransitionIs422() throws Exception {
        when(unblockCardUseCase.execute(any()))
                .thenThrow(new InvalidStateTransitionException("no se puede desbloquear"));

        postJson("/unblock", "{\"reason\":\"x\",\"unblockedBy\":\"u\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Invalid State Transition"))
                .andExpect(jsonPath("$.message").value("no se puede desbloquear"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void businessRuleIs422() throws Exception {
        when(activateCardUseCase.execute(any())).thenThrow(new BusinessRuleException("PIN incorrecto"));

        postJson("/activate", "{\"pin\":\"4821\",\"reason\":\"App\",\"activatedBy\":\"cliente\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Business Rule Violation"))
                .andExpect(jsonPath("$.message").value("PIN incorrecto"));
    }

    @Test
    void unknownCardIs404() throws Exception {
        when(approveCardUseCase.execute(any())).thenThrow(new NotFoundException("Tarjeta no encontrada"));

        postJson("/approve", "{\"reason\":\"Cumple\",\"approvedBy\":\"admin\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Tarjeta no encontrada"));
    }

    @Test
    void cardDataFailureIs502() throws Exception {
        when(cancelCardUseCase.execute(any()))
                .thenThrow(new CardDataIntegrationException("card-data no disponible", null));

        postJson("/cancel", "{\"reason\":\"Baja\",\"cancelledBy\":\"cliente\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Bad Gateway"));
    }

    // =============================================
    // Historial
    // =============================================

    @Test
    void historyIncludesReadableDescriptions() throws Exception {
        when(getLifecycleHistoryUseCase.getHistory(CARD_ID)).thenReturn(List.of(
                StateTransition.createSuccessful(CARD_ID, "BLOCKED", "ACTIVE", "UNBLOCK", "Recuperada", "operador"),
                StateTransition.createFailed(CARD_ID, "BLOCKED_FRAUD", "ACTIVE", "UNBLOCK", "x", "no permitido", "u")));

        mvc.perform(get(BASE + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromState").value("BLOCKED"))
                .andExpect(jsonPath("$[0].toStateDescription").value("Activa"))
                .andExpect(jsonPath("$[0].eventDescription").value("Desbloquear"))
                .andExpect(jsonPath("$[0].successful").value(true))
                .andExpect(jsonPath("$[1].successful").value(false))
                .andExpect(jsonPath("$[1].errorMessage").value("no permitido"));
    }

    @Test
    void emptyHistoryIsEmptyList() throws Exception {
        when(getLifecycleHistoryUseCase.getHistory(CARD_ID)).thenReturn(List.of());

        mvc.perform(get(BASE + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // =============================================
    // Helpers
    // =============================================

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String issueBody(String pin, YearMonth expiry) {
        return """
                {"cardNumber":"4532015112830366","cvv":"123","pin":"%s",
                 "expirationMonth":%d,"expirationYear":%d,
                 "reason":"Emisión estándar","issuedBy":"system"}
                """.formatted(pin, expiry.getMonthValue(), expiry.getYear());
    }
}
