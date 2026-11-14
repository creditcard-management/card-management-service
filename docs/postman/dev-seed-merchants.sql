-- Datos mínimos para correr la colección de Postman contra un entorno local.
-- payment-processing valida el comercio (merchants.status = 'ACTIVE') y, en adelantos,
-- la terminal (payment_terminals.status = 'ACTIVE'). El esquema no trae comercios de prueba.
--
-- docker exec -i card-db-postgres psql -U postgres -d card_management_db < docs/postman/dev-seed-merchants.sql

INSERT INTO merchants (id, legal_name, trade_name, registration_number, tax_id, tax_category,
                       contact_name, email, phone, street_address, street_number, city, state,
                       postal_code, country, category, type, status)
VALUES ('MERCH-001', 'Supermercado Centro S.A.', 'Supermercado Centro', 'IGJ-000001', '30-00000001-0',
        'RESPONSABLE_INSCRIPTO', 'Comercio de prueba', 'merchant-001@example.com', '+541100000000',
        'Av. Corrientes', '1234', 'CABA', 'Buenos Aires', 'C1043', 'AR', '5411', 'RETAIL', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payment_terminals (id, merchant_id, serial_number, terminal_type, status)
VALUES ('POS-0001', 'MERCH-001', 'SN-POS-0001', 'POS', 'ACTIVE'),
       ('ATM-0001', 'MERCH-001', 'SN-ATM-0001', 'ATM', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;
