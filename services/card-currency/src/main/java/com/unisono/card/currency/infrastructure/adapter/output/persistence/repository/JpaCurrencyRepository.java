package com.unisono.card.currency.infrastructure.adapter.output.persistence.repository;

import com.unisono.card.currency.infrastructure.adapter.output.persistence.entity.CurrencyEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JpaCurrencyRepository extends JpaRepository<CurrencyEntity, String> {

    List<CurrencyEntity> findAllByOrderByCodeAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CurrencyEntity c WHERE c.code = :code")
    Optional<CurrencyEntity> findByCodeForUpdate(@Param("code") String code);

    List<CurrencyEntity> findByActiveTrueOrderByCodeAsc();
}
