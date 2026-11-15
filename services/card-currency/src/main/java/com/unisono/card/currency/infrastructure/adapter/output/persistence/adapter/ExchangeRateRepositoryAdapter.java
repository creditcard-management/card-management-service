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
        lock(pair);
        return jpaRepository.findOpenForUpdate(pair.baseCode(), pair.quoteCode())
                .map(ExchangeRateRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<ExchangeRate> findByIdForUpdate(String rateId) {
        Optional<String> pairCode = jpaRepository.findPairOf(rateId);
        if (pairCode.isEmpty()) {
            return Optional.empty();
        }
        String[] codes = pairCode.get().split("/");
        lock(CurrencyPair.of(codes[0], codes[1]));
        // Primera carga de la entidad en la transacción, ya con el lock: lee el estado commiteado más reciente
        return jpaRepository.findById(rateId).map(ExchangeRateRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<ExchangeRate> findEndingAt(CurrencyPair pair, Instant validTo) {
        return jpaRepository.findByBaseCurrencyAndQuoteCurrencyAndValidTo(pair.baseCode(), pair.quoteCode(), validTo)
                .map(ExchangeRateRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsForPair(CurrencyPair pair) {
        return jpaRepository.existsByBaseCurrencyAndQuoteCurrency(pair.baseCode(), pair.quoteCode());
    }

    /**
     * Con flush, por el mismo motivo que {@link #save}: al cancelar la cotización abierta, la anterior se reabre
     * después y el DELETE tiene que llegar antes a la base.
     */
    @Override
    public void delete(ExchangeRate rate) {
        jpaRepository.deleteById(rate.getRateId());
        jpaRepository.flush();
    }

    /**
     * Primera clave del advisory lock: "CURR" en ASCII. Separa los locks de card-currency de cualquier otro
     * advisory lock de la base.
     */
    public static final int LOCK_NAMESPACE = 0x43555252;

    /** Cantidad de códigos ISO 4217 posibles: tres letras A-Z. */
    private static final int CURRENCY_CODES = 26 * 26 * 26;

    /**
     * Lock del par, el mismo para los dos sentidos (USD/ARS y ARS/USD): serializa también la regla de cotizar
     * cada par en un solo sentido.
     */
    private void lock(CurrencyPair pair) {
        jpaRepository.lockPair(LOCK_NAMESPACE, pairLockKey(pair));
    }

    /**
     * Segunda clave del advisory lock: cada código de 3 letras es un número en base 26 (0 a 17.575) y el par, con
     * los códigos ordenados, {@code menor × 17.576 + mayor}. Es distinto para cada par (sin colisiones, a diferencia
     * de un hash), igual para los dos sentidos y entra en un {@code int} (máximo 308.915.775).
     */
    public static int pairLockKey(CurrencyPair pair) {
        int base = codeNumber(pair.baseCode());
        int quote = codeNumber(pair.quoteCode());
        return Math.min(base, quote) * CURRENCY_CODES + Math.max(base, quote);
    }

    private static int codeNumber(String code) {
        int n = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (code.length() != 3 || c < 'A' || c > 'Z') {
                throw new IllegalArgumentException("Currency code must be 3 letters A-Z: " + code);
            }
            n = n * 26 + (c - 'A');
        }
        return n;
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
