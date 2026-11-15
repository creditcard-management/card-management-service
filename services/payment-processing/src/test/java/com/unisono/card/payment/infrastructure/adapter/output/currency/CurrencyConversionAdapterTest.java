package com.unisono.card.payment.infrastructure.adapter.output.currency;

import com.unisono.card.payment.domain.exception.CurrencyConversionException;
import com.unisono.card.payment.domain.exception.ExternalServiceException;
import com.unisono.card.payment.domain.port.output.CurrencyConversionPort.ConvertedAmount;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CurrencyConversionAdapterTest {

    private static final String BASE = "http://card-currency";
    private static final String URL = BASE + "/api/v1/exchange-rates/convert";
    private static final Money USD_10 = Money.of(new BigDecimal("10.00"), "USD");

    private MockRestServiceServer server;
    private CurrencyConversionAdapter adapter;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        adapter = new CurrencyConversionAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "cardCurrencyServiceUrl", BASE);
    }

    @Test
    void convertsWithCardCurrency() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(10.00))
                .andExpect(jsonPath("$.fromCurrency").value("USD"))
                .andExpect(jsonPath("$.toCurrency").value("ARS"))
                .andRespond(withSuccess("""
                        {"sourceAmount":10.00,"sourceCurrency":"USD","targetAmount":11505.00,"targetCurrency":"ARS",
                         "appliedRate":1150.50000000,"rateId":"rate-1","inverse":false}
                        """, MediaType.APPLICATION_JSON));

        ConvertedAmount converted = adapter.convert(USD_10, "ARS");

        assertThat(converted.converted()).isEqualTo(Money.ofARS(new BigDecimal("11505.00")));
        assertThat(converted.rate()).isEqualByComparingTo("1150.5");
        assertThat(converted.rateId()).isEqualTo("rate-1");
        server.verify();
    }

    @Test
    void missingRateIsAConversionError() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> adapter.convert(USD_10, "ARS"))
                .isInstanceOf(CurrencyConversionException.class)
                .hasMessageContaining("no exchange rate");
    }

    @Test
    void unsupportedCurrencyIsAConversionError() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> adapter.convert(USD_10, "ARS"))
                .isInstanceOf(CurrencyConversionException.class)
                .hasMessageContaining("currency not supported");
    }

    @Test
    void otherErrorsMeanTheServiceIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> adapter.convert(USD_10, "ARS")).isInstanceOf(ExternalServiceException.class);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> adapter.convert(USD_10, "ARS")).isInstanceOf(ExternalServiceException.class);
    }
}
