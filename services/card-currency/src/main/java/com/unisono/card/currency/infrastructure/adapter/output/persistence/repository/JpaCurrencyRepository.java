package com.unisono.card.currency.infrastructure.adapter.output.persistence.repository;

import com.unisono.card.currency.infrastructure.adapter.output.persistence.entity.CurrencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaCurrencyRepository extends JpaRepository<CurrencyEntity, String> {

    List<CurrencyEntity> findAllByOrderByCodeAsc();

    List<CurrencyEntity> findByActiveTrueOrderByCodeAsc();
}
