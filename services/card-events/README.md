# card-events

Librería con el contrato de los eventos de dominio que viajan por Kafka: la clase base `DomainEvent`, los eventos de tarjeta, de límite de crédito y de pagos, los nombres de los topics y el `ObjectMapper` común. Es un `jar` sin Spring Boot y sin lógica de Kafka: cada servicio configura su propio productor o consumidor.

> Documentación técnica funcional completa y catálogo de topics: [`docs/technical/EVENTS.md`](../../docs/technical/EVENTS.md)

## Contenido

| Paquete | Clases |
|---|---|
| `events.domain` | `DomainEvent` (`eventId`, `occurredOn`, `eventType`), `CardStatusChange` y los eventos `CardCreated`, `CardApproved`, `CardRejected`, `CardIssued`, `CardActivated`, `CardBlocked`, `CardUnblocked`, `CardCancelled`, `CardExpired`; `CreditLimitAssigned`, `CreditLimitUpdated`, `CreditLimitConsumed`, `CreditLimitReleased` (todos con sufijo `Event`) |
| `events.domain.payment` | `PaymentInitiated`, `PaymentAuthorized`, `PaymentProcessed`, `PaymentDeclined`, `PaymentReversed`, `RefundInitiated`, `RefundProcessed`, `TransactionSettled` (con sufijo `Event`) |
| `events.config` | `KafkaTopics` (nombres de topics, por ejemplo `card.*`, `credit-limit.*`, `payment.*` y las DLQ) y `EventSerializationConfig.objectMapper()` (fechas ISO de `java.time`) |

| Productor | Consumidores |
|---|---|
| card-data: `card.*` | card-lifecycle (alta e historial) |
| card-limits: `credit-limit.*` | — |
| payment-processing: `payment.*` | card-data (`payment.processing.declined` → bloqueo por fraude) |

Cada evento tiene un constructor de negocio para publicar (genera `eventId` y `occurredOn`) y uno `@JsonCreator` para reconstruirlo al consumir. Un cambio en un evento es un cambio de contrato entre servicios: los consumidores tienen que poder leer los mensajes que ya están en el topic.

## Uso

```xml
<dependency>
    <groupId>com.unisono.card</groupId>
    <artifactId>card-events</artifactId>
    <version>${project.version}</version>
</dependency>
```

```java
ObjectMapper mapper = EventSerializationConfig.objectMapper();
String json = mapper.writeValueAsString(event);                                 // al publicar (payload del outbox)
CardApprovedEvent approved = mapper.readValue(json, CardApprovedEvent.class);   // al consumir
String topic = KafkaTopics.CARD_APPROVED;                                       // "card.approved"
```

## Tests

```bash
mvn -pl services/card-events test
```

| Test | Cubre |
|---|---|
| `EventContractTest` | Ida y vuelta por JSON de cada evento con todos los campos |
| `CardStatusChangeTest` | Transición que expone cada evento de estado, valores derivados fuera del JSON y compatibilidad con mensajes viejos |
