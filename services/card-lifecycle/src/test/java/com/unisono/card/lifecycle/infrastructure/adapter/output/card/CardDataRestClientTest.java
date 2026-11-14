package com.unisono.card.lifecycle.infrastructure.adapter.output.card;

import com.unisono.card.lifecycle.domain.exception.CardDataIntegrationException;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.NotFoundException;
import com.unisono.card.shared.domain.valueobject.CVV;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.CardNumber;
import com.unisono.card.shared.domain.valueobject.ExpirationDate;
import com.unisono.card.shared.domain.valueobject.CardStatus;
import com.unisono.card.shared.domain.valueobject.PIN;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CardDataRestClientTest {

    private static final String BASE = "http://card-data";
    private static final CardId CARD_ID = CardId.of("be546553-f910-4891-8881-e83e1e7f488f");
    private static final String CARD_URL = BASE + "/api/v1/cards/" + CARD_ID.getValue();

    private MockRestServiceServer server;
    private CardDataRestClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new CardDataRestClient(restTemplate);
        ReflectionTestUtils.setField(client, "cardDataBaseUrl", BASE);
    }

    @Test
    void readsStatusIgnoringOtherFields() {
        server.expect(requestTo(CARD_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"x\",\"status\":\"ISSUED\",\"holderName\":\"Juan\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getStatus(CARD_ID)).isEqualTo(CardStatus.ISSUED);
    }

    @Test
    void translates422ToBusinessRuleWithRemoteMessage() {
        server.expect(requestTo(CARD_URL + "/status")).andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":422,\"message\":\"Transición inválida: REQUESTED → ACTIVE\"}"));

        assertThatThrownBy(() -> client.updateStatus(CARD_ID, "ACTIVE", "Activación", "cliente"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Transición inválida: REQUESTED → ACTIVE");
    }

    @Test
    void translates404ToNotFound() {
        server.expect(requestTo(CARD_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getStatus(CARD_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void translates5xxToIntegrationError() {
        server.expect(requestTo(CARD_URL + "/activate")).andRespond(withServerError());

        assertThatThrownBy(() -> client.activate(CARD_ID, "App", "cliente"))
                .isInstanceOf(CardDataIntegrationException.class);
    }

    @Test
    void sendsReasonAndAuthorWhenUpdatingStatus() {
        server.expect(requestTo(CARD_URL + "/status")).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reason").value("Cumple requisitos"))
                .andExpect(jsonPath("$.performedBy").value("admin"))
                .andRespond(withSuccess());

        client.updateStatus(CARD_ID, "APPROVED", "Cumple requisitos", "admin");

        server.verify();
    }

    @Test
    void blocksUnblocksAndCancelsWithReason() {
        server.expect(requestTo(CARD_URL + "/block")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reason").value("Pérdida"))
                .andExpect(jsonPath("$.performedBy").value("usuario"))
                .andRespond(withSuccess("{\"status\":\"BLOCKED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(CARD_URL + "/block-fraud")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        server.expect(requestTo(CARD_URL + "/unblock")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reason").value("Recuperada"))
                .andExpect(jsonPath("$.performedBy").value("operador"))
                .andRespond(withSuccess());
        server.expect(requestTo(CARD_URL + "/cancel")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reason").value("Baja"))
                .andRespond(withSuccess());

        client.block(CARD_ID, "Pérdida", "usuario");
        client.blockForFraud(CARD_ID, "Fraude", "antifraude");
        client.unblock(CARD_ID, "Recuperada", "operador");
        client.cancel(CARD_ID, "Baja", "cliente");

        server.verify();
    }

    @Test
    void issuesWithCardDataReasonAndAuthorInOneCall() {
        server.expect(requestTo(CARD_URL + "/issue")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.cardNumber").value("4532015112830366"))
                .andExpect(jsonPath("$.cvv").value("123"))
                .andExpect(jsonPath("$.pin").value("4821"))
                .andExpect(jsonPath("$.expirationMonth").value(12))
                .andExpect(jsonPath("$.expirationYear").value(2030))
                .andExpect(jsonPath("$.reason").value("Emisión"))
                .andExpect(jsonPath("$.performedBy").value("emisor"))
                .andRespond(withSuccess("{\"status\":\"ISSUED\"}", MediaType.APPLICATION_JSON));

        client.issue(CARD_ID, CardNumber.of("4532015112830366"), CVV.of("123"), PIN.of("4821"),
                ExpirationDate.of(12, 2030), "Emisión", "emisor");

        server.verify();
    }

    @Test
    void activatesWithReasonAndAuthor() {
        server.expect(requestTo(CARD_URL + "/activate")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reason").value("App"))
                .andExpect(jsonPath("$.performedBy").value("cliente"))
                .andRespond(withSuccess());

        client.activate(CARD_ID, "App", "cliente");

        server.verify();
    }

    @Test
    void validatesPin() {
        server.expect(requestTo(CARD_URL + "/validate-pin")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.pin").value("4821"))
                .andRespond(withSuccess("{\"valid\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.validatePin(CARD_ID, PIN.of("4821"))).isFalse();
    }
}
