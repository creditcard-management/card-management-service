# card-lifecycle

Microservicio que orquesta el ciclo de vida de la tarjeta (aprobación, rechazo, emisión, activación, bloqueo, desbloqueo y cancelación) y guarda el historial auditable de transiciones. Cada transición se valida con una state machine (Spring State Machine) y después se aplica en card-data por REST. El historial de transiciones exitosas se arma con los eventos `card.*` que publica card-data.

> Documentación técnica funcional completa: [`docs/technical/LIFECYCLE.md`](../../docs/technical/LIFECYCLE.md)

| | |
|---|---|
| Puerto | `8083` |
| Base path | `/api/v1/card-lifecycle` |
| Swagger UI | `http://localhost:8083/swagger-ui.html` |
| Tablas | `state_transitions` (propia); lee `credit_limits`. No toca `cards`: la cambia vía card-data |
| Kafka | Consume `card.created` y los ocho eventos de cambio de estado. Manda a `dlq.card-events` lo que no puede procesar y lo reprocesa solo |
| REST | card-data (`card-data.base-url`, por defecto `http://localhost:8081`; timeouts de 2 s de conexión y 5 s de lectura) |
| Depende de | `card-shared`, `card-events` |

## API

| Endpoint | Transición |
|---|---|
| `POST /{cardId}/approve` · `/reject` | `REQUESTED` → `APPROVED` / `REJECTED` |
| `POST /{cardId}/issue` | `APPROVED` → `ISSUED` (PAN, CVV, PIN y vencimiento en un solo paso) |
| `POST /{cardId}/activate` | `ISSUED` → `ACTIVE` (valida el PIN antes) |
| `POST /{cardId}/block` | `ACTIVE` → `BLOCKED`, o `BLOCKED_FRAUD` con `blockType: FRAUD` |
| `POST /{cardId}/unblock` | `BLOCKED` → `ACTIVE`. Un bloqueo por fraude no se desbloquea (422) |
| `POST /{cardId}/cancel` | → `CANCELLED` |
| `GET /{cardId}/history` | Historial de transiciones, exitosas y fallidas |

Una transición que la state machine rechaza responde 422 y se registra como fallida. Si card-data no responde, responde 502. Los bodies y los flujos están en `LIFECYCLE.md` §4 y §5.

## Ejecución local

Desde la raíz del repo. Necesita PostgreSQL (migraciones de `card-management-database`), Kafka y card-data levantados:

```bash
mvn -q install -DskipTests
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:29092 \
  mvn -pl services/card-lifecycle spring-boot:run -Dspring-boot.run.profiles=card-lifecycle
```

Desde el host, Kafka escucha en `localhost:29092`. El historial de transiciones exitosas depende de Kafka: llega por los eventos de card-data.

## Tests

```bash
mvn -pl services/card-lifecycle -am test
```

| Tests | Cubren |
|---|---|
| `SpringStateMachineAdapterTest` | Transiciones y guards de la state machine |
| `ApproveCardServiceTest`, `IssueCardServiceTest`, `ActivateCardServiceTest`, `BlockAndCancelServicesTest` | Casos de uso y registro de transiciones fallidas |
| `CardLifecycleControllerTest` | Endpoints (MockMvc) |
| `CardDataRestClientTest`, `RestClientConfigTest` | Cliente REST de card-data, traducción de errores y timeouts |
| `CardEventConsumerTest`, `CardStatusEventConsumerTest` | Consumidores de `card.created` y del historial |
| `CardStatusEventConsumerKafkaTest`, `CardEventConsumerDeadLetterTest`, `DeadLetterRedriverTest` | Consumo, DLQ y reproceso contra Kafka embebido |
