package com.unisono.card.currency.infrastructure.adapter.output.persistence.entity;

import com.unisono.card.currency.domain.model.RateSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cotización de un par. El índice único parcial (una sola abierta por par) está en la migración V19.
 */
@Entity
@Table(name = "exchange_rates", indexes = {
        @Index(name = "idx_exchange_rates_pair_valid_from", columnList = "base_currency, quote_currency, valid_from")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeRateEntity {

    @Id
    @Column(name = "rate_id", length = 36)
    private String rateId;

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    @Column(name = "quote_currency", nullable = false, length = 3)
    private String quoteCurrency;

    @Column(name = "rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private RateSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
