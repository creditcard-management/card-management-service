# card-limits

Microservicio dueño del aggregate `CreditLimit`: asigna y modifica los límites de crédito de una tarjeta (compras, adelantos, diario y mensual), consume y libera crédito, valida compras y adelantos, y guarda el historial de cambios. Publica los cambios como eventos `credit-limit.*` por un outbox transaccional.

> Documentación técnica funcional completa: [`docs/technical/LIMITS.md`](../../docs/technical/LIMITS.md)

| | |
|---|---|
| Puerto | `8082` |
| Base path | `/api/v1/credit-limits` |
| Swagger UI | `http://localhost:8082/swagger-ui.html` |
| Tablas | `credit_limits`, `credit_limit_history`, `credit_operations`, `credit_limit_outbox_events`; lee `cards` por JPA y `currencies` (catálogo de card-currency) por JDBC, sin llamar a esos servicios |
| Kafka | Publica `credit-limit.assigned`, `.updated`, `.consumed` y `.released`. No consume |
| Depende de | `card-shared`, `card-events` |

## API

| Grupo | Endpoints |
|---|---|
| Asignación | `POST /` y `POST /cards/{cardId}`. La tarjeta tiene que estar `APPROVED` o `ISSUED` y sin límite previo, y la moneda tener política (`card.limits.policies`) y estar activa en el catálogo de card-currency |
| Consultas | `GET /{creditLimitId}`, `GET /card/{cardId}`, `GET /card/{cardId}/info`, `GET /{creditLimitId}/history` |
| Modificación | `PUT /{creditLimitId}/increase-purchase-limit`, `/decrease-purchase-limit`, `/update-cash-advance-limit`, `/update-temporal-limits` |
| Operaciones | `POST /{creditLimitId}/consume-purchase`, `/consume-cash-advance`, `/release-credit`, `/restore-credit` y `/operations/{operationId}/cancel`. Son idempotentes por `operationId` |
| Validación sin consumir | `POST /validate/purchase`, `POST /validate/cash-advance` |

Consumir y validar exigen la tarjeta `ACTIVE`. Restaurar, liberar y cancelar no miran el estado. Los bodies, las reglas de cada endpoint y los códigos de error están en `LIMITS.md` §4.

## Ejecución local

Desde la raíz del repo, con PostgreSQL (migraciones de `card-management-database`) y Kafka del compose levantados:

```bash
mvn -q install -DskipTests
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:29092 \
  mvn -pl services/card-limits spring-boot:run -Dspring-boot.run.profiles=card-limits
```

Desde el host, Kafka escucha en `localhost:29092`.

## Tests

```bash
mvn -pl services/card-limits -am test
```

| Tests | Cubren |
|---|---|
| `CreditLimitInvariantsTest`, `CashAdvanceCumulativeTest`, `CashAdvanceDisabledTest`, `CreditLimitRestoreTest` | Aggregate: invariantes, adelantos y restauración |
| `CreditValidationServiceTest`, `CreditOperationIdempotencyTest`, `CreditLimitChangeEventsTest` | Validación, idempotencia por `operationId` y eventos |
| `CreditLimitControllerTest` | Endpoints (MockMvc) |
| `CreditLimitEntityMapperTest`, `CreditOperationRepositoryAdapterMappingTest`, `CreditLimitOutboxRelayTest` | Mapeos y relay del outbox |
| `CreditLimitPersistenceTest`, `CreditLimitConcurrencyTest`, `CreditLimitOutboxPersistenceTest`, `CurrencyCatalogAdapterTest` | Persistencia y concurrencia contra PostgreSQL (Testcontainers, requieren Docker) |
