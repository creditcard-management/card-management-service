package com.unisono.card.currency.infrastructure.adapter.output.persistence.adapter;

import com.unisono.card.currency.domain.model.SupportedCurrency;
import com.unisono.card.currency.domain.port.output.CurrencyRepository;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.entity.CurrencyEntity;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.repository.JpaCurrencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CurrencyRepositoryAdapter implements CurrencyRepository {

    private final JpaCurrencyRepository jpaRepository;

    @Override
    public SupportedCurrency save(SupportedCurrency currency) {
        return toDomain(jpaRepository.save(toEntity(currency)));
    }

    @Override
    public Optional<SupportedCurrency> findByCode(String code) {
        return jpaRepository.findById(code).map(CurrencyRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpaRepository.existsById(code);
    }

    @Override
    public List<SupportedCurrency> findAll(boolean activeOnly) {
        List<CurrencyEntity> entities = activeOnly
                ? jpaRepository.findByActiveTrueOrderByCodeAsc()
                : jpaRepository.findAllByOrderByCodeAsc();
        return entities.stream().map(CurrencyRepositoryAdapter::toDomain).toList();
    }

    static CurrencyEntity toEntity(SupportedCurrency currency) {
        return CurrencyEntity.builder()
                .code(currency.getCode())
                .name(currency.getName())
                .active(currency.isActive())
                .createdAt(currency.getCreatedAt())
                .updatedAt(currency.getUpdatedAt())
                .build();
    }

    static SupportedCurrency toDomain(CurrencyEntity entity) {
        return SupportedCurrency.restore(entity.getCode(), entity.getName(), entity.isActive(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
