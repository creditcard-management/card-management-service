# card-limits — Documentación técnica funcional

> Microservicio dueño del **Aggregate Root `CreditLimit`**: asignación y modificación de límites de crédito de una tarjeta, consumo y liberación de crédito, validación de compras y adelantos, e historial auditable de cambios de límites.

| Atributo | Valor |
|---|---|
| Artefacto Maven | `com.unisono.card:card-limits` (`1.0.0-SNAPSHOT`) |
| Clase principal | `com.unisono.card.limits.CardLimitsApplication` |
| Puerto | `8082` |
| Base path REST | `/api/v1/credit-limits` |
| Base de datos | PostgreSQL `card_management_db` — tablas propias `credit_limits`, `credit_limit_history`, `credit_operations` y `credit_limit_outbox_events`; lee `cards` |
| Mensajería | Kafka, solo como productor: publica `credit-limit.assigned`, `.updated`, `.consumed` y `.released` con un transactional outbox (§6.5). No consume |
| Dependencias internas | `card-shared` (`Money`, `CardId`, `CardStatus`, excepciones) y `card-events` (contratos `credit-limit.*`, `KafkaTopics`) |
| Swagger UI | `http://localhost:8082/swagger-ui.html` |

---

## 1. Responsabilidad dentro del sistema

`card-limits` define **cuánto puede gastar una tarjeta** y lleva la cuenta de lo consumido. Es la base de la regla de negocio “no se activa una tarjeta sin límite asignado” (ver [LIFECYCLE.md §5.4](LIFECYCLE.md)) y de la autorización de pagos.

```mermaid
flowchart LR
    Op[Operador / Backoffice] -->|REST /api/v1/credit-limits| CLM[card-limits :8082]
    CLM -->|JPA lectura cards<br/>estado de la tarjeta| DB[(PostgreSQL)]
    CLM -->|JPA credit_limits<br/>credit_limit_history| DB
    LC[card-lifecycle :8083] -.->|JPA lectura credit_limits<br/>¿tiene límite?| DB
    PP[payment-processing :8085] -->|REST validate / consume<br/>restore-credit| CLM
```

- **Asigna** el límite a una tarjeta en estado `APPROVED` o `ISSUED`, antes de que se active.
- **Consume** crédito por compras y adelantos, y lo **libera** cuando el cliente paga.
- **Valida** si una compra o un adelanto entra en los límites, sin consumir crédito.
- Registra en el **historial** cada asignación o cambio de límite.

> `payment-processing` usa la API tal como está:
> - **Antes de autorizar:** `validate/purchase` o `validate/cash-advance`.
> - **Al aprobar:** `consume-purchase` o `consume-cash-advance`.
> - **En reversos y devoluciones:** `restore-credit`.
>
> Resuelve el `creditLimitId` con `GET /card/{cardId}` ([PAYMENT.md §7](PAYMENT.md)).

---

## 2. Arquitectura

Hexagonal:

```
com.unisono.card.limits
├── domain
│   ├── model        CreditLimit (aggregate root), CreditLimitHistory,
│   │                PurchaseLimit, CashAdvanceLimit, ConsumedCredit, AvailableCredit,
│   │                DailyLimit, MonthlyLimit, LimitType
│   ├── port.input   AssignCreditLimit, UpdateCreditLimit, GetCreditInfo,
│   │                RecalculateAvailableCredit, ValidatePurchase
│   ├── port.output  CreditLimitRepository, CardPort, CardValidationPort
│   └── service      CreditLimitManagementService, CreditValidationService
├── application
│   ├── dto          Requests / Responses
│   └── mapper       CreditLimitDtoMapper
└── infrastructure
    ├── adapter.input.rest           CreditLimitController, CreditValidationController,
    │                                GlobalExceptionHandler
    ├── adapter.output.persistence   CreditLimitRepositoryAdapter, entities, mapper,
    │                                JpaCreditLimitRepository, JpaCreditLimitHistoryRepository
    ├── adapter.output.card          CardAdapter + CardJpaEntity (lectura de cards)
    ├── adapter.output.cardvalidation CardValidationAdapter
    └── config                       JPA (repositorios y entidades), Kafka, outbox, Swagger
```

### Mapa de casos de uso → servicios → endpoints

| Caso de uso (port input) | Implementación | Endpoints |
|---|---|---|
| `AssignCreditLimitUseCase` | `CreditLimitManagementService` | `POST /`, `POST /cards/{cardId}` |
| `GetCreditInfoUseCase` | `CreditLimitManagementService` | `GET /{creditLimitId}`, `GET /card/{cardId}`, `GET /card/{cardId}/info`, `GET /{creditLimitId}/history` |
| `UpdateCreditLimitUseCase` | `CreditLimitManagementService` | `PUT /{id}/increase-purchase-limit`, `/decrease-purchase-limit`, `/update-cash-advance-limit`, `/update-temporal-limits` |
| `RecalculateAvailableCreditUseCase` | `CreditLimitManagementService` | `POST /{id}/consume-purchase`, `/consume-cash-advance`, `/release-credit`, `/restore-credit` |
| `ValidatePurchaseUseCase` | `CreditValidationService` | `POST /validate/purchase`, `POST /validate/cash-advance` |

---

## 3. Modelo de dominio

### 3.1 `CreditLimit` (Aggregate Root)

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | `String` (UUID) | Generado al asignar |
| `cardId` | `CardId` | Tarjeta asociada (1 límite por tarjeta) |
| `purchaseLimit` | `PurchaseLimit` | Límite total de compras |
| `cashAdvanceLimit` | `CashAdvanceLimit` | Tope de adelantos en efectivo |
| `consumedCredit` | `ConsumedCredit` | Crédito usado |
| `cashAdvanceConsumed` | `ConsumedCredit` | Parte de lo consumido usada en adelantos (acumulativa contra el tope de adelantos) |
| `availableCredit` | `AvailableCredit` | `purchaseLimit − consumedCredit` (se recalcula tras cada cambio) |
| `dailyLimit` | `DailyLimit` | Tope diario con consumo del día |
| `monthlyLimit` | `MonthlyLimit` | Tope mensual con consumo del mes |
| `history` | `List<CreditLimitHistory>` | Cambios pendientes de persistir (en memoria) |
| `createdAt`, `updatedAt` | `LocalDateTime` | Auditoría |

**Invariantes declaradas:**

- El límite de adelantos no puede superar el límite de compras.
- Lo usado en adelantos es parte de lo consumido: 0 ≤ `cashAdvanceConsumed` ≤ `consumedCredit`.
- Disponible para adelantos = tope de adelantos − usado en adelantos (cero si el tope quedó por debajo).
- El crédito consumido no puede superar el límite total.
- Crédito disponible = límite de compras − crédito consumido.
- Los límites diario y mensual se reinician solos al cambiar el día o el mes.

### 3.2 Value Objects

| VO | Reglas |
|---|---|
| `PurchaseLimit` | Entre **ARS 1.000** y **ARS 10.000.000**. `increase` / `decrease` revalidan el rango. |
| `CashAdvanceLimit` | **Cero** = sin adelantos (`isEnabled()` falso: todo adelanto se rechaza). Si es mayor a cero, entre **ARS 500** y **ARS 5.000.000**. Se calcula como `purchaseLimit × porcentaje` (0–1, por defecto 0,30). |
| `ConsumedCredit` | `consume(+)` / `release(−)`. Nunca negativo (`ValidationException`); `releaseCredit` rechaza antes con 422 un pago mayor a lo consumido. |
| `AvailableCredit` | `isSufficientFor(monto)`, `isExhausted()`. |
| `DailyLimit` | Monto (por defecto **ARS 100.000**), consumido y `resetDate`. Si `resetDate ≠ hoy`, el disponible es el monto completo y el próximo consumo reinicia el contador. |
| `MonthlyLimit` | Igual que el diario, con `resetMonth` (`YearMonth`). Por defecto **ARS 500.000**. |

Todos operan sobre `Money` (`card-shared`): montos con escala 2 y `HALF_UP`. **Sumar, restar o comparar montos de distinta moneda lanza `BusinessRuleException` (422).**

### 3.3 Operaciones del aggregate

| Método | Validaciones | Efecto | Historial |
|---|---|---|---|
| `assign(cardId, monto, %adelantos, por)` | Datos obligatorios, % entre 0 y 1, rangos de los VO | Nuevo límite; consumido 0; diario/mensual por defecto | `ASSIGNED` (PURCHASE) |
| `increasePurchaseLimit(+monto)` | Nuevo total ≤ 10M | Sube el límite y recalcula el disponible | `INCREASED` (PURCHASE) |
| `decreasePurchaseLimit(−monto)` | Nuevo total ≥ 1.000 y ≥ consumido | Baja el límite y recalcula el disponible | `DECREASED` (PURCHASE) |
| `updateCashAdvanceLimit(nuevo)` | ≤ límite de compras, rango del VO. Puede quedar por debajo de lo usado en adelantos | Reemplaza el tope de adelantos; si queda por debajo de lo usado, los adelantos nuevos se rechazan hasta que un pago lo baje | `INCREASED`/`DECREASED` (CASH_ADVANCE) |
| `updateDailyLimit(nuevo)` / `updateMonthlyLimit(nuevo)` | No nulo | Reemplaza el tope; conserva el consumo del período en curso | `INCREASED`/`DECREASED` (DAILY/MONTHLY) |
| `consumeForPurchase(monto)` | > 0, ≤ límite de compras, ≤ disponible, ≤ disponible diario y mensual | Suma al consumido, al diario y al mensual | — |
| `consumeForCashAdvance(monto)` | > 0, adelantos habilitados, ≤ disponible para adelantos (acumulativo), ≤ disponible, ≤ diario y mensual | Igual que la compra y además suma a lo usado en adelantos | — |
| `releaseCredit(monto)` | > 0, ≤ consumido | Pago del resumen: resta del consumido y recalcula el disponible (no toca diario ni mensual). **Cancela primero lo usado en adelantos** y el resto va a compras | — |
| `restoreConsumption(monto, fechaOriginal, adelanto)` | > 0, fecha obligatoria | Reverso o devolución: resta del consumido (como mucho hasta cero) y recalcula el disponible. Si `fechaOriginal` es del día en curso, resta del acumulado diario; si es del mes en curso, del mensual. Si la original fue un adelanto, también resta de lo usado en adelantos (como mucho hasta cero). Devuelve el excedente que no se pudo restaurar | — |
| `canPurchase(monto)` / `canCashAdvance(monto)` | Las mismas reglas, sin lanzar excepciones | `true` / `false` | — |

### 3.4 `CreditLimitHistory`

| Campo | Descripción |
|---|---|
| `id` | Autoincremental |
| `creditLimitId` | Límite afectado |
| `limitType` | `PURCHASE` (Límite de Compras), `CASH_ADVANCE` (Límite de Adelantos), `DAILY` (Límite Diario), `MONTHLY` (Límite Mensual) |
| `action` | `ASSIGNED`, `INCREASED`, `DECREASED` |
| `previousValue`, `newValue`, `difference` | Montos (`difference` siempre positivo) |
| `reason`, `changedBy` | Motivo y usuario |
| `changedAt` | Fecha y hora |
| `metadata` | Texto/JSON libre (hoy no se usa) |

**El consumo y la liberación de crédito no generan historial**: el módulo no guarda un registro de movimientos.

### 3.5 Elegibilidad de la tarjeta

Antes de asignar, el servicio consulta `cards` (lectura directa por JPA):

- La tarjeta debe existir → si no, **404**.
- Debe estar en `APPROVED` o `ISSUED` y no estar eliminada → si no, **422** (`"La tarjeta no puede recibir límite de crédito en su estado actual"`).
- No puede tener ya un límite activo → **422** (`"La tarjeta ya tiene un límite de crédito asignado"`).

Consumir y validar exigen que la tarjeta esté `ACTIVE` y no eliminada (§4.4 y §4.5). Restaurar, liberar y cancelar no miran el estado: los reversos, devoluciones y pagos de una tarjeta bloqueada tienen que poder aplicarse.

---

## 4. API REST

Base: `/api/v1/credit-limits`. Bodies validados con Bean Validation.

### 4.1 Asignación

#### `POST /api/v1/credit-limits`

```json
{
  "cardId": "550e8400-e29b-41d4-a716-446655440000",
  "purchaseLimitAmount": 150000.00,
  "currency": "ARS",
  "cashAdvancePercentage": 0.30,
  "dailyLimitAmount": 100000.00,
  "monthlyLimitAmount": 500000.00,
  "assignedBy": "admin@unisono.com"
}
```

| Campo | Validación |
|---|---|
| `cardId` | Obligatorio |
| `purchaseLimitAmount` | Obligatorio, entre 1.000 y 10.000.000 |
| `currency` | Obligatorio, 3 letras (en la práctica solo funciona `ARS`, ver §9) |
| `cashAdvancePercentage` | Opcional, entre 0 y 1, por defecto 0,30 (un `null` explícito también). **`0` = sin adelantos.** Si es mayor a 0, el tope resultante debe ser ≥ ARS 500 (400) |
| `dailyLimitAmount`, `monthlyLimitAmount` | Opcionales, > 0; si no se envían quedan 100.000 y 500.000. El diario final no puede superar al mensual final (422) |
| `assignedBy` | Obligatorio |

Respuesta **201** → `CreditLimitResponse`.

#### `POST /api/v1/credit-limits/cards/{cardId}`

Igual que el anterior. El `cardId` del body es obligatorio y debe coincidir con el del path: si no, **400**.

### 4.2 Consultas

| Método y ruta | Respuesta |
|---|---|
| `GET /{creditLimitId}` | `CreditLimitResponse` o 404 |
| `GET /card/{cardId}` | `CreditLimitResponse` o 404 |
| `GET /card/{cardId}/info` | `CreditInfoResponse` o 404 |
| `GET /{creditLimitId}/history` | `CreditLimitHistoryResponse[]` (más reciente primero) o 404 si el límite no existe |

**`CreditLimitResponse`**

```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "cardId": "550e8400-e29b-41d4-a716-446655440000",
  "purchaseLimit": 150000.00,
  "cashAdvanceLimit": 45000.00,
  "cashAdvanceConsumed": 2000.00,
  "cashAdvanceAvailable": 43000.00,
  "availableCredit": 145000.00,
  "consumedCredit": 5000.00,
  "dailyLimit": 100000.00,
  "dailyAvailable": 95000.00,
  "monthlyLimit": 500000.00,
  "monthlyAvailable": 495000.00,
  "currency": "ARS",
  "createdAt": "2026-11-02T10:15:30",
  "updatedAt": "2026-11-02T11:00:00",
  "operationId": "6f1c…"
}
```

`operationId` solo aparece en las respuestas de consumos, restauraciones y liberaciones.

**`CreditInfoResponse`**: `purchaseLimit`, `cashAdvanceLimit`, `cashAdvanceConsumed`, `cashAdvanceAvailable`, `availableCredit`, `consumedCredit`, `utilizationPercentage` (consumido / límite × 100), `dailyAvailable`, `monthlyAvailable`, `currency`, `isExhausted`.

**`CreditLimitHistoryResponse`**: `id`, `limitType`, `action`, `previousValue`, `newValue`, `difference`, `currency`, `reason`, `changedBy`, `changedAt`, `description` (por ejemplo `"Límite de Compras: INCREASED cambió de ARS 150000.00 a ARS 200000.00 (diferencia: ARS 50000.00) - Razón: …"`).

### 4.3 Modificación de límites

`PUT /{creditLimitId}/increase-purchase-limit` · `/decrease-purchase-limit` · `/update-cash-advance-limit`

```json
{ "amount": 50000.00, "currency": "ARS", "reason": "Aumento por buen comportamiento crediticio", "changedBy": "admin@unisono.com" }
```

- En `increase` y `decrease`, `amount` es la **diferencia** a sumar o restar.
- En `update-cash-advance-limit`, `amount` es el **nuevo valor absoluto** del tope. `0` deshabilita los adelantos; entre 0 y 500 es inválido (400). Se puede bajar por debajo de lo ya usado en adelantos: los adelantos nuevos se rechazan hasta que un pago lo baje.
- En `increase` y `decrease`, `amount` debe ser mayor a cero (400).
- `decrease` responde **422** si el nuevo límite de compras queda por debajo de lo consumido o del límite de adelantos (hay que bajar primero el de adelantos).

`PUT /{creditLimitId}/update-temporal-limits`

```json
{ "dailyLimitAmount": 150000.00, "monthlyLimitAmount": 600000.00, "currency": "ARS", "reason": "Ajuste de límites temporales", "changedBy": "admin@unisono.com" }
```

Los dos montos son opcionales (> 0 si se envían). Se valida la combinación final: el diario no puede superar al mensual (422); se pueden subir los dos en el mismo request aunque el diario nuevo supere al mensual anterior. Todos estos endpoints responden **200** `CreditLimitResponse`.

### 4.4 Consumo y liberación

| Método y ruta | Body | Efecto |
|---|---|---|
| `POST /{creditLimitId}/consume-purchase` | `{ "amount": 5000.00, "currency": "ARS", "description": "Compra en Mercado Libre", "operationId": "6f1c…" }` | Consume crédito por una compra |
| `POST /{creditLimitId}/consume-cash-advance` | ídem | Consume crédito por un adelanto |
| `POST /{creditLimitId}/release-credit` | `{ "paymentAmount": 10000.00, "currency": "ARS", "description": "Pago de resumen", "operationId": "pago-…" }` | Libera crédito (pago del resumen). **422** si el pago supera lo consumido (no hay saldo a favor) |
| `POST /{creditLimitId}/restore-credit` | `{ "amount": 1500.00, "currency": "ARS", "originalTransactionDate": "2026-11-03", "description": "Reverso de …", "operationId": "7a2d…", "cashAdvance": false }` | Deshace un consumo (reverso o devolución): disponible y cupo diario y mensual del período de la transacción original; con `cashAdvance: true` (la original fue un adelanto), también lo usado en adelantos. Si ya se pagó, el excedente no se restaura (se loguea) |
| `POST /{creditLimitId}/operations/{operationId}/cancel` | — | Deshace exactamente un consumo, una restauración o una liberación (ver abajo). Responde `{ "operationId", "outcome", "availableCredit", "currency", "overLimitAmount" }` |

Respuesta **200** `CreditLimitResponse`, con el `operationId` de la operación (el del request o el generado). `description` es obligatoria y se guarda en `credit_operations`.

**Estado de la tarjeta.** Los consumos (`consume-purchase`, `consume-cash-advance`) exigen que la tarjeta esté `ACTIVE` y no eliminada: si no, **422**. Se controla después de la idempotencia, así que repetir un `operationId` ya aplicado responde 200 aunque la tarjeta se haya bloqueado después. Restauración, liberación y cancelación no miran el estado: los reversos, devoluciones y pagos de una tarjeta bloqueada, cancelada o vencida tienen que poder aplicarse.

**Registro e idempotencia (`operationId`, opcional, hasta 64 caracteres).** payment-processing lo envía siempre ([PAYMENT.md §4.7](PAYMENT.md)):

- **Todo** consumo, restauración y liberación se registra en `credit_operations` con lo que movió: deltas de lo consumido, del cupo diario y del mensual, y la fecha en que se aplicó. Una liberación no toca el cupo diario ni el mensual.
- **Sin `operationId`**, card-limits genera uno (`auto-<uuid>`) y lo devuelve en la respuesta. Esa operación no es idempotente (repetir el request aplica dos veces), pero se puede cancelar con el id generado.
- **Con `operationId`**, repetir el mismo id no vuelve a aplicar nada: responde 200 con el estado actual. Si llega con otro tipo de operación o para otro límite, responde 422.
- **Cancelar** deshace exactamente esos deltas. Lo consumido no baja de cero. El cupo diario o mensual solo se toca si sigue siendo el mismo día o mes: si ya se reseteó, no hay nada que devolver.
- **No valida límites**, a propósito. Deshacer una restauración significa que el reverso o la devolución nunca ocurrieron, así que lo restaurado se vuelve a consumir aunque la tarjeta ya lo haya gastado. Lo mismo al deshacer una liberación (un pago que se rechazó después). En ese caso el disponible queda **negativo** (la tarjeta está excedida): la respuesta informa `overLimitAmount`, se loguea un `WARN` y las compras siguientes se rechazan por crédito insuficiente hasta que pague. `CreditLimit.getOverLimitAmount()` devuelve el exceso. `outcome`:
  - `CANCELLED`: estaba aplicada y se deshizo.
  - `VOIDED`: todavía no había llegado. Queda anulada y, si llega después, se rechaza con **409**. Cubre el caso de un timeout en el que el request todavía no se aplicó.
  - `ALREADY_CANCELLED`: ya estaba cancelada o anulada; no se hace nada.

Todas las modificaciones (cambios de límites, consumo, restauración, liberación y cancelación) bloquean la fila del límite (`SELECT … FOR UPDATE`) hasta el fin de la transacción, así que se ejecutan de a una por límite.

### 4.5 Validación (sin consumir)

`POST /validate/purchase` · `POST /validate/cash-advance`

```json
{ "cardId": "550e8400-e29b-41d4-a716-446655440000", "amount": 5000.00, "currency": "ARS" }
```

Respuesta **200** siempre que exista el límite (aunque la operación se rechace):

```json
{ "approved": false, "reason": "Se excede el límite diario. Disponible hoy: ARS 3000.00", "availableCredit": 145000.00, "requestedAmount": 5000.00, "currency": "ARS" }
```

Orden en que se evalúan los motivos de rechazo:

0. Tarjeta no activa (`"La tarjeta no está activa"`).
1. Límite de compras (o, en un adelanto, adelantos habilitados y disponible para adelantos: tope − usado).
2. Crédito disponible.
3. Límite diario.
4. Límite mensual.

**404** si la tarjeta no tiene límite asignado.

### 4.6 Errores (`GlobalExceptionHandler`)

Mismo formato que `card-data`:

```json
{ "timestamp": "…", "status": 422, "error": "Business Rule Violation", "message": "Crédito insuficiente. Disponible: …, Solicitado: …", "fieldErrors": null }
```

| Excepción | HTTP | Casos típicos |
|---|---|---|
| `NotFoundException` | 404 | Límite o tarjeta inexistente |
| `ValidationException` (incluye `InvalidValueObjectException`) | 400 | Montos fuera de rango en los VO, monto cero, moneda inválida |
| `MethodArgumentNotValidException` | 400 + `fieldErrors` | Bean Validation |
| `CreditOperationCancelledException` | 409 | Llega un consumo, una restauración o una liberación cuyo `operationId` ya se canceló (anulado) |
| `BusinessRuleException` | 422 | Monedas distintas, tarjeta no elegible o no activa al consumir, límite duplicado, crédito insuficiente, límite diario o mensual excedido, reducción por debajo de lo consumido o del límite de adelantos, pago mayor a lo consumido, diario mayor al mensual |
| Otras | 500 | — |

> Las anotaciones OpenAPI de los endpoints documentan estos mismos códigos (400, 404, 409 y 422).

---

## 5. Flujos funcionales

### 5.1 Asignación previa a la activación

```mermaid
sequenceDiagram
    participant Op as Operador
    participant API as CreditLimitController
    participant S as CreditLimitManagementService
    participant C as CardAdapter (JPA cards)
    participant R as CreditLimitRepositoryAdapter
    participant LC as card-lifecycle

    Op->>API: POST /api/v1/credit-limits
    API->>S: execute(AssignCreditLimitCommand)
    S->>C: ¿existe? ¿APPROVED/ISSUED y no eliminada?
    S->>R: ¿ya tiene límite?
    S->>S: CreditLimit.assign(...) (+ diario/mensual si vienen)
    S->>R: save(creditLimit)
    S->>R: saveHistory(...) por cada cambio
    API-->>Op: 201 CreditLimitResponse
    Note over LC: Al activar, card-lifecycle podría verificar<br/>que exista fila en credit_limits (hoy no lo hace)
```

### 5.2 Compra

```mermaid
sequenceDiagram
    participant Caller as Consumidor (pago)
    participant V as /validate/purchase
    participant P as /{id}/consume-purchase
    participant CL as CreditLimit

    Caller->>V: cardId, monto
    V->>CL: canPurchase(monto)
    V-->>Caller: approved / reason
    Caller->>P: creditLimitId, monto
    P->>CL: consumeForPurchase(monto)
    CL->>CL: valida límites → consume → recalcula disponible
    P-->>Caller: CreditLimitResponse
```

La validación usa el **`cardId`**; el consumo usa el **`creditLimitId`**. Quien llama necesita los dos (el segundo se obtiene con `GET /card/{cardId}`). Validar y consumir son dos llamadas separadas, sin reserva: entre una y otra, otra operación puede consumir el disponible.

### 5.3 Reinicio diario y mensual

No hay jobs programados. Al leer o consumir, `DailyLimit` y `MonthlyLimit` comparan su fecha de reinicio con `LocalDate.now()` / `YearMonth.now()` (zona horaria del servidor). Si cambió el período, consideran el disponible completo y el siguiente consumo pone el contador en el monto consumido. En la base, `daily_limit_consumed` puede mostrar el valor del día anterior hasta el próximo consumo.

---

## 6. Persistencia

### 6.1 `credit_limits`

| Columna | Tipo | Nulo | Notas |
|---|---|---|---|
| `id` | `varchar(36)` | no | PK |
| `card_id` | `varchar(36)` | no | `UNIQUE`, índice `idx_credit_limit_card_id` |
| `purchase_limit_amount` / `_currency` | `numeric(15,2)` / `varchar(3)` | no | |
| `cash_advance_limit_amount` / `_currency` | `numeric(15,2)` / `varchar(3)` | no | |
| `cash_advance_consumed` | `numeric(15,2)` | no | Usado en adelantos (V18, default 0). `CHECK` 0 ≤ valor ≤ `consumed_credit_amount` |
| `consumed_credit_amount` / `_currency` | `numeric(15,2)` / `varchar(3)` | no | |
| `available_credit_amount` / `_currency` | `numeric(15,2)` / `varchar(3)` | no | Valor derivado, persistido |
| `daily_limit_amount` / `_currency` | `numeric(15,2)` / `varchar(3)` | no | |
| `daily_limit_consumed` | `numeric(15,2)` | no | |
| `daily_limit_reset_date` | `date` | no | |
| `monthly_limit_amount` / `_currency` | `numeric(15,2)` / `varchar(3)` | no | |
| `monthly_limit_consumed` | `numeric(15,2)` | no | |
| `monthly_limit_reset_year` / `_month` | `int` | no | |
| `created_at` / `updated_at` | `timestamp` | no | `@CreationTimestamp` / `@UpdateTimestamp` |
| `deleted` / `deleted_at` | `boolean` / `timestamp` | no / sí | Soft delete; índice `idx_credit_limit_deleted` |

### 6.2 `credit_limit_history`

| Columna | Tipo | Nulo |
|---|---|---|
| `id` | `bigint` IDENTITY | no |
| `credit_limit_id` | `varchar(36)` | no (índice) |
| `limit_type` | `varchar(20)` | no |
| `action` | `varchar(20)` | no (índice) |
| `previous_value_amount` / `_currency` | `numeric(15,2)` / `varchar(3)` | sí |
| `new_value_amount` / `_currency` | `numeric(15,2)` / `varchar(3)` | no |
| `difference_amount` / `_currency` | `numeric(15,2)` / `varchar(3)` | no |
| `reason` | `varchar(500)` | sí |
| `changed_by` | `varchar(100)` | sí |
| `changed_at` | `timestamp` | no (índice) |
| `metadata` | `text` | sí |

### 6.3 Lectura de `cards`

`CardJpaEntity` mapea solo `id`, `status` y `deleted` de la tabla de `card-data`, para validar si la tarjeta puede recibir un límite.

### 6.4 `credit_operations`

Todos los movimientos de crédito (V12; `RELEASE` desde V17), uno por `operationId`:

- `operation_id` (PK, hasta 64 caracteres): el del cliente o `auto-<uuid>` si no mandó uno. `credit_limit_id` (FK a `credit_limits`).
- `operation_type` (`CONSUME_PURCHASE`, `CONSUME_CASH_ADVANCE`, `RESTORE` o `RELEASE`; nulo en las `VOIDED`) y `status` (`APPLIED`, `CANCELLED` o `VOIDED`).
- `credit_delta`, `daily_delta`, `monthly_delta` y `cash_advance_delta` (V18): lo que movió la operación en lo consumido, el cupo diario, el mensual y lo usado en adelantos (positivo = consumió, negativo = devolvió), en `currency`. Cancelar deshace exactamente esos deltas.
- `applied_on`: fecha en que se aplicó (define si el cupo diario y mensual todavía se pueden revertir).
- `description`, `created_at` y `cancelled_at`.

### 6.5 `credit_limit_outbox_events` (eventos)

Transactional outbox de card-limits (V15), con la misma estructura que `outbox_events` de card-data: `event_id` (único), `aggregate_id` (el `cardId`, key del mensaje), `topic`, `event_type`, `payload` (JSON), `created_at`, `published_at`, `failed_at`, `attempts` y `last_error`.

- `CreditLimitEventEmitter` traduce cada cambio a su evento y lo encola con `EventOutboxPort` en la transacción del caso de uso (`Propagation.MANDATORY`). Si la operación se rechaza o falla, el evento no existe.
- **Asignación y cambios de límite** → `credit-limit.assigned` / `credit-limit.updated` (uno por entrada nueva de `credit_limit_history`).
- **Consumos y liberaciones** → `credit-limit.consumed` / `credit-limit.released`, según suba o baje lo consumido. Una operación repetida con el mismo `operationId` no vuelve a publicar.
- `CreditLimitOutboxRelay` publica los pendientes cada `poll-interval-ms`, en orden y con `FOR UPDATE SKIP LOCKED`. Un error transitorio corta el lote y se reintenta; uno permanente marca `failed_at`. `CreditLimitOutboxCleaner` borra los publicados de más de `retention-days`.
- Contratos y tipos de operación en [EVENTS.md §4.10](EVENTS.md).

### 6.6 Notas

- **Esquema:** lo crea Flyway desde el repo `card-management-database`: `credit_limits` en V2, `credit_limit_history` en V3, `credit_operations` en V12 (`RELEASE` admitido desde V17), `credit_limit_outbox_events` en V15 y el uso de adelantos (`cash_advance_consumed`, `cash_advance_delta`) en V18. Este módulo usa `ddl-auto: validate` con Flyway deshabilitado, así que las tablas deben existir antes del arranque.
- **Restricciones de la migración que el código no refleja:**
  - `credit_limits.card_id` → FK a `cards(id)` (`ON DELETE CASCADE`).
  - `chk_consumed_not_negative`: `consumed_credit_amount`, `daily_limit_consumed` y `monthly_limit_consumed` ≥ 0.
  - `credit_limit_history.credit_limit_id` → FK a `credit_limits(id)`.
  - `chk_limit_type` y `chk_action`: el `CHECK` de acciones admite además `CONSUMED` y `RELEASED`, que el código no usa.
  - `changed_by` es `NOT NULL` (la entidad lo declara nullable; el request lo exige).
  - Columnas `successful` (default `true`) y `error_message`, que la entidad no mapea.
  - Índice único parcial `(credit_limit_id, limit_type, action, changed_at, new_value_amount)`.
- Las consultas por id y por tarjeta filtran `deleted = false`. `CreditLimitRepository.delete()` hace soft delete, pero no hay endpoint que lo use.
- `JpaCreditLimitHistoryRepository` también ofrece consultas por tipo, por rango de fechas y por último cambio, que no se exponen.

---

## 7. Configuración y despliegue

### 7.1 Propiedades principales (`application.yml`)

| Propiedad | Valor por defecto |
|---|---|
| `server.port` | `8082` |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/card_management_db` |
| `spring.datasource.username` / `password` | `card_user` / `card_pass` |
| `spring.jpa.hibernate.ddl-auto` | `validate` |
| `spring.kafka.bootstrap-servers` | `localhost:9092` (docker-compose: `kafka:9092`) |
| `card.limits.outbox.poll-interval-ms` / `batch-size` | `1000` / `50` |
| `card.limits.outbox.send-timeout-ms` | `15000` (debe superar `delivery.timeout.ms` + `max.block.ms` del productor) |
| `card.limits.outbox.retention-days` / `cleanup-cron` | `7` / `0 0 3 * * *` |
| `management.endpoints.web.exposure.include` | `health,info,metrics` |

El perfil `card-limits` (`application-card-limits.yml`, activado por docker-compose) agrega logging a `logs/card-limits.log`, Prometheus y SpringDoc. Los defaults de negocio (límite diario 100.000, mensual 500.000, 30% de adelantos, mínimos y máximos) están fijos en los VO, no en la configuración.

El `DataSource` (HikariCP, pool `CreditLimitHikariCP`) lo arma Spring Boot a partir de `spring.datasource.*` y `spring.datasource.hikari.*`. `CreditLimitDatabaseConfig` solo declara los paquetes de repositorios y entidades JPA.

### 7.2 Docker

- `services/card-limits/Dockerfile`: build multi-stage con contexto en la raíz del repo.
- `deploy/docker/docker-compose.yml`: servicio `card-limits-service`, puerto `8082`, perfil `card-limits`, healthcheck sobre `/actuator/health`. Publica en `kafka:9092` y espera a que `kafka` esté *healthy*.

```bash
docker compose -f deploy/docker/docker-compose.yml up -d card-limits-service
```

### 7.3 Observabilidad

- `GET /actuator/health` (incluye la base), `/actuator/metrics`, `/actuator/prometheus` (perfil).
- Los rechazos de validación se loguean en `WARN`.

---

## 8. Relación con otros módulos

| Módulo | Relación | Estado |
|---|---|---|
| `card-data` | `card-limits` lee `cards.status` y `cards.deleted` | Funciona (acoplamiento por base compartida) |
| `card-lifecycle` | Lee `credit_limits` por `card_id` para decidir si se puede activar | La regla existe pero el endpoint de activación no la usa ([LIFECYCLE.md §9](LIFECYCLE.md)); además esa consulta no filtra `deleted` |
| `payment-processing` | Valida (`validate/*`), consume al aprobar (`consume-*`) y restaura en reversos y devoluciones (`restore-credit`) por REST, siempre con `operationId`. Cancela las operaciones cuyo pago no confirmó (`operations/{id}/cancel`) | Funciona ([PAYMENT.md §4.7 y §7](PAYMENT.md)) |
| `card-events` | Contratos `credit-limit.*` y `KafkaTopics` ([EVENTS.md §4.10](EVENTS.md)) | Se publican por el outbox (§6.5); todavía sin consumidores |

---

## 9. Limitaciones conocidas y deuda técnica

### 9.1 Críticas

1. **Solo funciona en ARS.** Los mínimos y máximos de los VO, `Money.zero()` y los límites diario y mensual por defecto están en ARS. Asignar o consumir en otra moneda se rechaza con 422 (“Cannot operate on different currencies”).

### 9.2 Reglas de negocio

2. **Asignaciones bajas con adelantos fallan.** Con el 30% por defecto, cualquier límite de compras menor a ARS 1.666,67 da un tope de adelantos menor al mínimo de ARS 500 y la asignación se rechaza (400). Sin adelantos (`cashAdvancePercentage: 0`) sí se pueden asignar.
3. **Límites diario y mensual independientes del límite de compras.** Los defaults (100.000 / 500.000) se aplican aunque el límite de compras sea menor.
4. **Sin reserva (hold).** Validar y consumir son operaciones separadas: no hay reserva de fondos ni confirmación posterior.
5. **Sin saldo a favor.** `restore-credit` baja lo consumido como mucho hasta cero. Si la tarjeta ya pagó el consumo que se devuelve, el excedente no se acredita y solo se loguea.
