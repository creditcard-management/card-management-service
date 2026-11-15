package com.unisono.card.currency.infrastructure.adapter.output.persistence.repository;

import com.unisono.card.currency.infrastructure.adapter.output.persistence.entity.ExchangeRateEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JpaExchangeRateRepository extends JpaRepository<ExchangeRateEntity, String> {

    /**
     * Lock de Postgres por par hasta el fin de la transacción. Serializa las publicaciones del par aunque todavía
     * no tenga una cotización abierta que bloquear con FOR UPDATE.
     * <p>
     * Versión de dos claves de Postgres: {@code namespace} identifica a card-currency (no comparte espacio con
     * otros locks de la base) y {@code pairKey} al par, sin hash (ver {@code ExchangeRateRepositoryAdapter}).
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:namespace, :pairKey)) AS pair_lock",
            nativeQuery = true)
    Integer lockPair(@Param("namespace") int namespace, @Param("pairKey") int pairKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM ExchangeRateEntity r
            WHERE r.baseCurrency = :base AND r.quoteCurrency = :quote AND r.validTo IS NULL
            """)
    Optional<ExchangeRateEntity> findOpenForUpdate(@Param("base") String base, @Param("quote") String quote);

    @Query("""
            SELECT r FROM ExchangeRateEntity r
            WHERE r.baseCurrency = :base AND r.quoteCurrency = :quote
              AND r.validFrom <= :at AND (r.validTo IS NULL OR r.validTo > :at)
            """)
    Optional<ExchangeRateEntity> findValidAt(@Param("base") String base, @Param("quote") String quote,
                                             @Param("at") Instant at);

    /**
     * Par de una cotización como {@code "USD/ARS"}. Es una proyección escalar (no carga la entidad) para poder
     * tomar el lock del par antes de leer la cotización.
     */
    @Query(value = "SELECT base_currency || '/' || quote_currency FROM exchange_rates WHERE rate_id = :rateId",
            nativeQuery = true)
    Optional<String> findPairOf(@Param("rateId") String rateId);

    Optional<ExchangeRateEntity> findByBaseCurrencyAndQuoteCurrencyAndValidTo(String base, String quote,
                                                                            Instant validTo);

    boolean existsByBaseCurrencyAndQuoteCurrency(String base, String quote);

    List<ExchangeRateEntity> findByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc(String base, String quote,
                                                                                   Pageable pageable);
}
