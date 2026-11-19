package com.unisono.card.limits.domain.port.output;

/**
 * Catálogo de monedas de card-currency. Un límite solo se asigna en una moneda dada de alta y activa: los pagos
 * en otra moneda de la tarjeta convierten hacia la moneda del límite.
 */
public interface CurrencyCatalogPort {

    /**
     * Si la moneda está dada de alta y activa. Debe llamarse dentro de la transacción de la asignación: deja la
     * fila de la moneda bloqueada para que card-currency no la desactive hasta que el límite se confirme.
     */
    boolean isActive(String currencyCode);
}
