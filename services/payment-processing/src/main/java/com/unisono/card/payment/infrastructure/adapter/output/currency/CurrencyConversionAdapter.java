package com.unisono.card.payment.infrastructure.adapter.output.currency;

import com.unisono.card.payment.domain.exception.CurrencyConversionException;
import com.unisono.card.payment.domain.exception.ExternalServiceException;
import com.unisono.card.payment.domain.port.output.CurrencyConversionPort;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

/**
 * REST client of card-currency: POST /api/v1/exchange-rates/convert
 * <p>
 * 404 (no rate for the pair) and 422 (currency not supported) are business errors of the payment
 * ({@link CurrencyConversionException}); anything else means card-currency is not available.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrencyConversionAdapter implements CurrencyConversionPort {

    private static final String SERVICE = "card-currency";
    private static final String CONVERT_PATH = "/api/v1/exchange-rates/convert";

    private final RestTemplate restTemplate;

    @Value("${services.card-currency.url:http://localhost:8086}")
    private String cardCurrencyServiceUrl;

    @Override
    public ConvertedAmount convert(Money amount, String targetCurrency) {
        String source = amount.getCurrencyCode();
        log.debug("Converting {} to {}", amount, targetCurrency);
        try {
            ConversionResponse response = restTemplate.postForObject(cardCurrencyServiceUrl + CONVERT_PATH,
                    new ConvertRequest(amount.getAmount(), source, targetCurrency), ConversionResponse.class);
            if (response == null || response.getTargetAmount() == null || response.getAppliedRate() == null) {
                throw new ExternalServiceException(SERVICE, "empty conversion response for " + source + "/"
                        + targetCurrency, null);
            }
            ConvertedAmount converted = new ConvertedAmount(
                    Money.of(response.getTargetAmount(), response.getTargetCurrency()),
                    response.getAppliedRate(), response.getRateId());
            log.debug("Converted {} to {} (rate {}, {})", amount, converted.converted(), converted.rate(),
                    converted.rateId());
            return converted;

        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND || ex.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                log.warn("card-currency rejected conversion {} -> {}: {}", source, targetCurrency,
                        ex.getResponseBodyAsString());
                throw new CurrencyConversionException(source, targetCurrency,
                        ex.getStatusCode() == HttpStatus.NOT_FOUND ? "no exchange rate" : "currency not supported");
            }
            throw new ExternalServiceException(SERVICE, ex.getMessage(), ex);
        } catch (RestClientException ex) {
            log.error("Error converting {} to {}: {}", source, targetCurrency, ex.getMessage());
            throw new ExternalServiceException(SERVICE, ex.getMessage(), ex);
        }
    }

    // ==================== Internal DTOs (contratos de card-currency) ====================

    @Data
    @AllArgsConstructor
    static class ConvertRequest {
        private BigDecimal amount;
        private String fromCurrency;
        private String toCurrency;
    }

    @Data
    @NoArgsConstructor
    static class ConversionResponse {
        private BigDecimal sourceAmount;
        private String sourceCurrency;
        private BigDecimal targetAmount;
        private String targetCurrency;
        private BigDecimal appliedRate;
        private String rateId;
        private boolean inverse;
    }
}
