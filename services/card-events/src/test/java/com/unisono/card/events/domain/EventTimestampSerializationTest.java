package com.unisono.card.events.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisono.card.events.config.EventSerializationConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las fechas de los eventos viajan en ISO con fracción de segundo: con precisión de segundos, dos cambios de la misma
 * tarjeta dentro del mismo segundo (bloqueo y desbloqueo) empataban y el historial de card-lifecycle no tenía orden.
 */
class EventTimestampSerializationTest {

    private static final String CARD_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 19, 18, 9, 28, 761_709_000);

    private final ObjectMapper mapper = EventSerializationConfig.objectMapper();

    @Test
    void datesKeepTheirFractionOfSecond() throws Exception {
        CardBlockedEvent event = new CardBlockedEvent(CARD_ID, "ACTIVE", "FRAUD", AT, "f", "antifraude");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("blockedAt").asText()).isEqualTo("2026-11-19T18:09:28.761709");
        assertThat(LocalDateTime.parse(json.get("occurredOn").asText())).isEqualTo(event.getOccurredOn());
    }

    @Test
    void roundTripKeepsTheExactDateOfTheChange() throws Exception {
        CardUnblockedEvent event = new CardUnblockedEvent(CARD_ID, "BLOCKED", AT, "u", "admin");

        CardUnblockedEvent read = mapper.readValue(mapper.writeValueAsString(event), CardUnblockedEvent.class);

        assertThat(read.changedAt()).isEqualTo(AT);
        assertThat(read.getOccurredOn()).isEqualTo(event.getOccurredOn());
    }

    @Test
    void messagePublishedWithSecondPrecisionIsStillReadable() throws Exception {
        String old = """
                {"eventId":"e-1","occurredOn":"2026-11-19T18:09:28","eventType":"CardCancelledEvent",
                 "cardId":"%s","previousStatus":"BLOCKED_FRAUD","cancelledAt":"2026-11-19T18:09:29",
                 "reason":"Fraude confirmado","cancelledBy":"antifraude"}
                """.formatted(CARD_ID);

        CardCancelledEvent event = mapper.readValue(old, CardCancelledEvent.class);

        assertThat(event.getOccurredOn()).isEqualTo(LocalDateTime.of(2026, 11, 19, 18, 9, 28));
        assertThat(event.changedAt()).isEqualTo(LocalDateTime.of(2026, 11, 19, 18, 9, 29));
    }
}
