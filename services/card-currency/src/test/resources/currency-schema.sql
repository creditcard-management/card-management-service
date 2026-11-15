-- Copia de card-management-database/migrations/V19__currencies_and_exchange_rates.sql para validar el mapeo JPA.
-- Mantener sincronizada con la migración.
CREATE TABLE currencies (
    code            VARCHAR(3)      PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_currencies_code CHECK (code ~ '^[A-Z]{3}$')
);

-- 2. Cotizaciones por par con vigencia [valid_from, valid_to). 1 base = rate quote.
--    valid_to NULL = vigente hasta la próxima publicación del par.
CREATE TABLE exchange_rates (
    rate_id         VARCHAR(36)     PRIMARY KEY,
    base_currency   VARCHAR(3)      NOT NULL REFERENCES currencies (code),
    quote_currency  VARCHAR(3)      NOT NULL REFERENCES currencies (code),
    rate            NUMERIC(19,8)   NOT NULL,
    valid_from      TIMESTAMPTZ     NOT NULL,
    valid_to        TIMESTAMPTZ,
    source          VARCHAR(20)     NOT NULL DEFAULT 'MANUAL',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_exchange_rates_pair CHECK (base_currency <> quote_currency),
    CONSTRAINT chk_exchange_rates_rate CHECK (rate > 0),
    CONSTRAINT chk_exchange_rates_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);

-- Una sola cotización abierta por par (card-currency cierra la anterior antes de insertar la nueva)
CREATE UNIQUE INDEX uq_exchange_rates_open_pair
    ON exchange_rates (base_currency, quote_currency) WHERE valid_to IS NULL;
-- Búsqueda de la cotización vigente en un instante y del historial del par
CREATE INDEX idx_exchange_rates_pair_valid_from
    ON exchange_rates (base_currency, quote_currency, valid_from);

-- 3. Monedas iniciales. ARS es la moneda del sistema (límites de crédito); no tiene cotizaciones cargadas.
INSERT INTO currencies (code, name) VALUES
    ('ARS', 'Peso argentino'),
    ('USD', 'Dólar estadounidense'),
    ('EUR', 'Euro')
ON CONFLICT (code) DO NOTHING
;
