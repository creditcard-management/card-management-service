# payment-processing

Microservicio dueño del aggregate `Transaction`. Procesa compras, adelantos y preautorizaciones con captura: valida la tarjeta en card-data y el comercio en la base, controla el crédito en card-limits, chequea fraude y autoriza. También maneja reversos, devoluciones y liquidación, y publica eventos `payment.*` por un outbox transaccional.

> Documentación técnica funcional completa: [`docs/technical/PAYMENT.md`](../../docs/technical/PAYMENT.md)

| | |
|---|---|
| Puerto | `8085` (`SERVER_PORT`) |
| Base paths | `/api/v1/payments`, `/refunds`, `/reversals`, `/settlements`, `/transactions` |
| Swagger UI | `http://localhost:8085/swagger-ui.html` |
| Tablas | `transactions`, `payment_outbox_events`, `payment_credit_operations`; lee `merchants` y `payment_terminals` |
| Kafka | Publica `payment.*`. card-data consume `payment.processing.declined` |
| REST | card-data (`CARD_DATA_SERVICE_URL`), card-limits (`CARD_LIMITS_SERVICE_URL`), card-currency (`CARD_CURRENCY_SERVICE_URL`), antifraude y autorizador |
| Depende de | `card-shared`, `card-events` |

## API

| Grupo | Endpoints |
|---|---|
| Pagos | `POST /payments/purchase` (hasta 50.000), `/payments/cash-advance` (hasta 5.000, con PIN block), `/payments/pre-authorize`, `/payments/{transactionId}/capture` |
| Devoluciones | `POST /refunds/full`, `/refunds/partial` |
| Reversos | `POST /reversals`, `/reversals/{transactionId}/auto` |
| Liquidación | `POST /settlements/transaction/{transactionId}`, `/settlements/batch`, `/settlements/all-pending` |
| Consultas | `GET /transactions/{transactionId}`, `/transactions/card/{cardId}` (y `/status/{status}`, `/range`, `/summary`), `/transactions/pending-settlement` |

Un pago aprobado responde 201 y uno rechazado, 402 (queda guardado como `DECLINED`). El crédito se consume en card-limits al aprobar y se restaura con un reverso o una devolución.

El pago se cobra en la moneda del límite de la tarjeta: si viene en otra, card-currency lo convierte con la cotización vigente (422 `CURRENCY_NOT_SUPPORTED` si no hay). Las devoluciones van en la moneda del pago original y usan su tasa. Los bodies, las reglas y los códigos de error están en `PAYMENT.md` §4 y §5.

## Ejecución local

Desde la raíz del repo. Necesita PostgreSQL (migraciones de `card-management-database`, hasta V20), Kafka, card-data, card-limits y card-currency levantados, y un comercio y una terminal activos en la base:

```bash
docker exec -i card-db-postgres psql -U postgres -d card_management_db < docs/postman/dev-seed-merchants.sql
mvn -q install -DskipTests
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 mvn -pl services/payment-processing spring-boot:run
```

No usa perfil por defecto; `dev` y `prod` son opcionales. El servicio antifraude no existe en el repo: si no responde, se usa el cálculo local. El autorizador está simulado por defecto (`authorization.simulate=true`) y aprueba todo monto válido.

## Tests

```bash
mvn -pl services/payment-processing -am test
```

| Tests | Cubren |
|---|---|
| `PaymentDomainServiceTest`, `TransactionRefundTest`, `TransactionReversalServiceTest`, `RefundApplicationServiceTest`, `SettlementServiceTest`, `PreAuthorizationExpiryServiceTest` | Flujos de pago, devolución, reverso, liquidación y vencimiento de preautorizaciones |
| `SagaCreditLimitPortTest`, `CreditOperationCompensatorTest`, `CreditOperationReconcilerTest`, `ConsistencyMonitorTest` | Consistencia del crédito con card-limits (compensación y reconciliación) |
| `PaymentControllerTest`, `RefundControllerTest`, `ReversalControllerTest`, `SettlementControllerTest`, `TransactionControllerTest`, `GlobalExceptionHandlerTest` | Endpoints y mapeo de errores (MockMvc) |
| `BillingAmountServiceTest` | Conversión a la moneda del límite |
| `CardValidationAdapterTest`, `CreditLimitAdapterTest`, `CurrencyConversionAdapterTest`, `AuthorizationAdapterTest`, `MerchantValidationAdapterTest` | Adapters de card-data, card-limits, card-currency, autorizador y comercios |
| `PaymentOutboxRelayTest`, `PaymentOutboxCleanerTest`, `OutboxPaymentEventPublisherTest`, `PaymentEventTopicResolverTest`, `PaymentIntegrationEventMapperTest` | Outbox y eventos |
| `TransactionEntityMapperTest`, `DefaultCurrencyTest` | Mapeos y moneda por defecto |
| `TransactionPersistenceTest`, `OutboxAndCreditOperationQueriesTest` (base `PostgresPersistenceTest`) | Persistencia contra PostgreSQL (Testcontainers, requieren Docker) |
