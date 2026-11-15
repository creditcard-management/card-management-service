package com.unisono.card.currency.infrastructure.adapter.output.persistence.adapter;

import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.port.output.ExchangeRateRepository;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.entity.ExchangeRateEntity;
import com.unisono.card.currency.infrastructure.adapter.output.persistence.repository.JpaExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ExchangeRateRepositoryAdapter implements ExchangeRateRepository {

    private final JpaExchangeRateRepository jpaRepository;

    /**
     * saveAndFlush: al publicar, la cotización anterior se cierra antes de insertar la nueva. Sin el flush,
     * Hibernate haría el INSERT antes que el UPDATE y chocaría con el índice único de cotización abierta por par.
     */
    @Override
    public ExchangeRate save(ExchangeRate rate) {
        return toDomain(jpaRepository.saveAndFlush(toEntity(rate)));
    }

    @Override
    public Optional<ExchangeRate> findOpenForUpdate(CurrencyPair pair) {
        jpaRepository.lockPair("exchange_rates:" + pair);
        return jpaRepository.findOpenForUpdate(pair.baseCode(), pair.quoteCode())
                .map(ExchangeRateRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<ExchangeRate> findValidAt(CurrencyPair pair, Instant at) {
        return jpaRepository.findValidAt(pair.baseCode(), pair.quoteCode(), at)
                .map(ExchangeRateRepositoryAdapter::toDomain);
    }

    @Override
    public List<ExchangeRate> findHistory(CurrencyPair pair, int limit) {
        return jpaRepository.findByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc(
                        pair.baseCode(), pair.quoteCode(), PageRequest.of(0, limit))
                .stream()
                .map(ExchangeRateRepositoryAdapter::toDomain)
                .toList();
    }

    static ExchangeRateEntity toEntity(ExchangeRate rate) {
        return ExchangeRateEntity.builder()
                .rateId(rate.getRateId())
                .baseCurrency(rate.getPair().baseCode())
                .quoteCurrency(rate.getPair().quoteCode())
                .rate(rate.getRate())
                .validFrom(rate.getValidFrom())
                .validTo(rate.getValidTo())
                .source(rate.getSource())
                .createdAt(rate.getCreatedAt())
                .build();
    }

    static ExchangeRate toDomain(ExchangeRateEntity entity) {
        return ExchangeRate.restore(entity.getRateId(),
                CurrencyPair.of(entity.getBaseCurrency(), entity.getQuoteCurrency()),
                entity.getRate(), entity.getValidFrom(), entity.getValidTo(), entity.getSource(),
                entity.getCreatedAt());
    }
}
