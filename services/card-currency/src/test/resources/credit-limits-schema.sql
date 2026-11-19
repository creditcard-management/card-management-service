-- Solo las columnas de credit_limits (V2 de card-management-database) que lee CreditLimitCurrencyAdapter
CREATE TABLE IF NOT EXISTS credit_limits (
    id VARCHAR(36) PRIMARY KEY,
    purchase_limit_currency VARCHAR(3) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
