# card-data

Microservicio dueño del aggregate `Card`: alta de la solicitud, datos del titular, datos sensibles (PAN y CVV cifrados, PIN hasheado), estados, bloqueo, PIN y CVV. Publica cada cambio de estado como evento `card.*` por un outbox transaccional.

> Documentación técnica funcional completa: [`docs/technical/DATA.md`](../../docs/technical/DATA.md)

| | |
|---|---|
| Puerto | `8081` |
| Base path | `/api/v1/cards` |
| Swagger UI | `http://localhost:8081/swagger-ui.html` |
| Tablas | `cards`, `outbox_events` y `card_data_processed_events` (idempotencia del consumidor) |
| Kafka | Publica `card.*`. Consume `payment.processing.declined` para bloquear por fraude |
| Depende de | `card-shared`, `card-events` |

## API

| Controller | Endpoints |
|---|---|
| `CardController` | `POST /` (alta), `GET /{cardId}`, `GET /client/{clientId}`, `GET /search`, `PUT /{cardId}/status`, `POST /{cardId}/issue`, `POST /{cardId}/activate`, `POST /{cardId}/cancel` |
| `CardSecurityController` | `POST /{cardId}/block`, `POST /{cardId}/block-fraud`, `POST /{cardId}/unblock` (solo desde `BLOCKED`) |
| `PINController` | `POST /{cardId}/validate-pin` (`{pinBlock, pinKeyId}`; `pin` en claro solo para card-lifecycle), `POST /{cardId}/reset-pin` |
| `CVVController` | `POST /{cardId}/validate-cvv` |

Los cambios de estado del flujo normal los orquesta card-lifecycle. Los bodies, códigos de respuesta y reglas de cada endpoint están en `DATA.md` §4.

## Ejecución local

Desde la raíz del repo, con PostgreSQL (migraciones de `card-management-database`) y Kafka del compose levantados:

```bash
mvn -q install -DskipTests
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:29092 \
  mvn -pl services/card-data spring-boot:run -Dspring-boot.run.profiles=card-data
```

Desde el host, Kafka escucha en `localhost:29092`. El `localhost:9092` del yml solo sirve dentro de la red de Docker. Las claves de cifrado (`ENCRYPTION_KEY_K1`, `PIN_ENCRYPTION_KEY_V1`, …) tienen defaults de desarrollo en `application.yml`.

## Tests

```bash
mvn -pl services/card-data -am test
```

| Tests | Cubren |
|---|---|
| `CardTest`, `CardTransitionsTest`, `CardPinAttemptsTest` | Aggregate: transiciones, bloqueos e intentos de PIN |
| `CardManagementServiceTest`, `CardManagementServiceOutboxTest`, `PINManagementServiceTest`, `CVVValidationServiceTest`, `FraudDeclineServiceTest`, `CardExpirationServiceTest` | Servicios de dominio y eventos que encolan |
| `CardStatusEndpointsTest`, `PINControllerTest` | Endpoints (MockMvc) |
| `OutboxRelayTest`, `OutboxCleanerTest`, `PaymentDeclinedConsumerKafkaTest` | Outbox y consumidor de Kafka (Kafka embebido) |
| `CardExpirationSchedulerTest`, `CardExpirationSchedulerWiringTest` | Job de vencimiento |
| `CardExpirationPersistenceTest`, `CardOptimisticLockingPersistenceTest`, `FraudDeclinePersistenceTest` (base `CardDataPostgresTest`) | Persistencia contra PostgreSQL (Testcontainers, requieren Docker) |
