# card-currency

Microservicio dueño de las monedas que acepta el sistema y de sus cotizaciones. Mantiene el catálogo de monedas (ISO 4217), guarda la cotización de cada par con su vigencia y convierte montos con la cotización vigente en un instante dado.

> payment-processing lo usa para cobrar los pagos en la moneda del límite de la tarjeta (`POST /exchange-rates/convert`). Sin cotización para el par, el pago se rechaza con 422.

| | |
|---|---|
| Puerto | `8086` |
| Base paths | `/api/v1/currencies`, `/api/v1/exchange-rates` |
| Swagger UI | `http://localhost:8086/swagger-ui.html` |
| Tablas | `currencies`, `exchange_rates` (migración V19 de `card-management-database`) |
| Kafka | No publica ni consume |
| Depende de | `card-shared`. El pom también trae `card-events` y `spring-kafka`, que todavía no se usan |

## Modelo

- **Moneda** (`SupportedCurrency`): código ISO 4217, nombre y estado. Solo las activas se cotizan y convierten. La moneda del sistema (`card.currency.system-currency`, por defecto `ARS`, la de los límites de crédito) no se puede desactivar. La migración carga ARS, USD y EUR.
- **Cotización** (`ExchangeRate`): `1 base = rate quote`, con hasta 8 decimales y vigencia `[validFrom, validTo)`. Hay una sola cotización abierta (`validTo` nulo) por par: publicar una nueva cierra la anterior en el instante en que empieza la nueva. Las publicaciones de un mismo par se serializan con un lock de Postgres.
- **Conversión**: usa la cotización directa del par y, si no hay, la inversa (dividiendo). El resultado se redondea a 2 decimales HALF_UP, como todo `Money`.

## API

| Endpoint | Hace |
|---|---|
| `POST /currencies` | Alta de una moneda (`{code, name}`), queda activa. 409 si ya existe |
| `GET /currencies` (`?activeOnly=true`), `GET /currencies/{code}` | Consultas |
| `PUT /currencies/{code}/activate` · `/deactivate` | Cambia el estado. 422 si es la moneda del sistema |
| `POST /exchange-rates` | Publica `{baseCurrency, quoteCurrency, rate, effectiveFrom?}`. Sin `effectiveFrom` rige desde ahora; no acepta fechas pasadas (422) |
| `GET /exchange-rates/{base}/{quote}` (`?at=`) | Cotización vigente del par, sin invertir. 404 si no hay |
| `GET /exchange-rates/{base}/{quote}/history` (`?limit=`) | Historial del par, de la más nueva a la más vieja (máximo `card.currency.history-max-size`) |
| `POST /exchange-rates/convert` | Convierte `{amount, fromCurrency, toCurrency, at?}` y devuelve el monto, la tasa aplicada, la cotización usada y si fue inversa |

Errores: 400 validación, 404 sin moneda o sin cotización, 409 moneda duplicada, 422 moneda no soportada o regla de negocio.

```bash
curl -X POST localhost:8086/api/v1/exchange-rates -H 'Content-Type: application/json' \
  -d '{"baseCurrency":"USD","quoteCurrency":"ARS","rate":1150.50}'
curl -X POST localhost:8086/api/v1/exchange-rates/convert -H 'Content-Type: application/json' \
  -d '{"amount":10,"fromCurrency":"USD","toCurrency":"ARS"}'   # → targetAmount 11505.00
```

## Ejecución local

Desde la raíz del repo, con PostgreSQL y las migraciones de `card-management-database` (incluida la V19) aplicadas:

```bash
mvn -q install -DskipTests
mvn -pl services/card-currency spring-boot:run -Dspring-boot.run.profiles=card-currency
```

## Tests

```bash
mvn -pl services/card-currency -am test
```

| Tests | Cubren |
|---|---|
| `CurrencyPairTest`, `ExchangeRateTest`, `SupportedCurrencyTest` | Value objects y aggregate: códigos, vigencias, cierre de cotizaciones y moneda del sistema |
| `CurrencyServicesTest` | Catálogo, publicación (futuras, retroactivas, monedas inactivas), historial y conversión directa, inversa y en un instante |
| `CurrencyApiTest` | Endpoints, validación y mapeo de errores (MockMvc) |
| `CurrencyPersistenceTest` | Mapeo contra el schema de V19, vigencias e índice de cotización abierta por par (Testcontainers, requiere Docker) |

`src/test/resources/currency-schema.sql` es una copia del DDL de V19: si cambia la migración, hay que actualizarla.
