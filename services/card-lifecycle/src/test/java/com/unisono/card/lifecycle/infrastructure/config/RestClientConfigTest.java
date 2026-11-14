package com.unisono.card.lifecycle.infrastructure.config;

import com.sun.net.httpserver.HttpServer;
import com.unisono.card.lifecycle.domain.exception.CardDataIntegrationException;
import com.unisono.card.lifecycle.infrastructure.adapter.output.card.CardDataRestClient;
import com.unisono.card.shared.domain.valueobject.CardId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los timeouts de {@link RestClientConfig} cortan una llamada a un card-data que no responde
 * y el cliente la traduce a CardDataIntegrationException (502).
 */
class RestClientConfigTest {

    private static final CardId CARD_ID = CardId.of("be546553-f910-4891-8881-e83e1e7f488f");

    private HttpServer slowCardData;

    @BeforeEach
    void startSlowServer() throws Exception {
        slowCardData = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slowCardData.createContext("/", exchange -> {
            try {
                Thread.sleep(2_000);
                byte[] body = "{\"status\":\"ACTIVE\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // el cliente ya cortó la conexión
            } finally {
                exchange.close();
            }
        });
        slowCardData.start();
    }

    @AfterEach
    void stopServer() {
        slowCardData.stop(0);
    }

    @Test
    void readTimeoutTurnsHangingCardDataIntoIntegrationError() {
        RestTemplate restTemplate = new RestClientConfig().restTemplate(new RestTemplateBuilder(), 1_000, 200);
        CardDataRestClient client = new CardDataRestClient(restTemplate);
        ReflectionTestUtils.setField(client, "cardDataBaseUrl",
                "http://localhost:" + slowCardData.getAddress().getPort());

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.getStatus(CARD_ID))
                .isInstanceOf(CardDataIntegrationException.class)
                .hasMessageContaining("card-data no disponible");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
    }
}
