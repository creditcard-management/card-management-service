package com.unisono.card.currency.infrastructure.adapter.input.rest;

import com.unisono.card.currency.application.mapper.CurrencyDtoMapper;
import com.unisono.card.currency.domain.exception.CurrencyAlreadyExistsException;
import com.unisono.card.currency.domain.exception.CurrencyNotSupportedException;
import com.unisono.card.currency.domain.exception.ExchangeRateNotFoundException;
import com.unisono.card.currency.domain.model.Conversion;
import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.model.RateSource;
import com.unisono.card.currency.domain.model.SupportedCurrency;
import com.unisono.card.currency.domain.port.input.ConvertAmountUseCase;
import com.unisono.card.currency.domain.port.input.ExchangeRateUseCase;
import com.unisono.card.currency.domain.port.input.ExchangeRateUseCase.PublishRateCommand;
import com.unisono.card.currency.domain.port.input.ManageCurrenciesUseCase;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.NotFoundException;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controllers con MockMvc standalone y el GlobalExceptionHandler real: rutas, bodies, validación y códigos HTTP.
 */
class CurrencyApiTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");
    private static final CurrencyPair USD_ARS = CurrencyPair.of("USD", "ARS");

    private final ManageCurrenciesUseCase currencies = mock(ManageCurrenciesUseCase.class);
    private final ExchangeRateUseCase exchangeRates = mock(ExchangeRateUseCase.class);
    private final ConvertAmountUseCase conversions = mock(ConvertAmountUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CurrencyDtoMapper mapper = new CurrencyDtoMapper();
        mvc = MockMvcBuilders
                .standaloneSetup(new CurrencyController(currencies, mapper),
                        new ExchangeRateController(exchangeRates, conversions, mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    private static ExchangeRate usdArs(String value) {
        return ExchangeRate.restore("rate-1", USD_ARS, new BigDecimal(value), T0, null, RateSource.MANUAL, T0);
    }

    @Test
    void registerCurrencyReturns201() throws Exception {
        when(currencies.register("BRL", "Real")).thenReturn(SupportedCurrency.register("BRL", "Real", T0));

        mvc.perform(post("/api/v1/currencies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BRL\",\"name\":\"Real\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("BRL"))
                .andExpect(jsonPath("$.fractionDigits").value(2))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void registerValidatesBodyAndMapsDuplicateTo409() throws Exception {
        mvc.perform(post("/api/v1/currencies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BR\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.code").exists())
                .andExpect(jsonPath("$.fieldErrors.name").exists());

        when(currencies.register("USD", "Dólar")).thenThrow(new CurrencyAlreadyExistsException("USD"));
        mvc.perform(post("/api/v1/currencies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"USD\",\"name\":\"Dólar\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void listAndDeactivate() throws Exception {
        when(currencies.list(true)).thenReturn(List.of(SupportedCurrency.register("ARS", "Peso", T0)));
        when(currencies.deactivate("ARS")).thenThrow(new BusinessRuleException("system currency"));

        mvc.perform(get("/api/v1/currencies").param("activeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ARS"));
        mvc.perform(put("/api/v1/currencies/ARS/deactivate"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void publishRateBuildsCommand() throws Exception {
        when(exchangeRates.publish(any())).thenReturn(usdArs("1150.5"));

        mvc.perform(post("/api/v1/exchange-rates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"usd","quoteCurrency":"ARS","rate":1150.5,
                                 "effectiveFrom":"2026-11-16T00:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rateId").value("rate-1"))
                .andExpect(jsonPath("$.rate").value(1150.5))
                .andExpect(jsonPath("$.source").value("MANUAL"));

        ArgumentCaptor<PublishRateCommand> command = ArgumentCaptor.forClass(PublishRateCommand.class);
        verify(exchangeRates).publish(command.capture());
        assertThat(command.getValue().pair()).isEqualTo(USD_ARS);
        assertThat(command.getValue().effectiveFrom()).isEqualTo(Instant.parse("2026-11-16T00:00:00Z"));
    }

    @Test
    void publishRejectsInvalidBodiesWithoutCallingTheService() throws Exception {
        mvc.perform(post("/api/v1/exchange-rates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\",\"quoteCurrency\":\"ARS\",\"rate\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.rate").exists());
        mvc.perform(post("/api/v1/exchange-rates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\",\"quoteCurrency\":\"USD\",\"rate\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/exchange-rates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\",\"quoteCurrency\":\"ARS\",\"rate\":1,\"effectiveFrom\":\"ayer\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(exchangeRates);
    }

    @Test
    void concurrentPublishMapsTo409() throws Exception {
        when(exchangeRates.publish(any())).thenThrow(new DataIntegrityViolationException("uq_exchange_rates_open_pair"));

        mvc.perform(post("/api/v1/exchange-rates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\",\"quoteCurrency\":\"ARS\",\"rate\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void currentRateAndNotFound() throws Exception {
        when(exchangeRates.getRate(USD_ARS, null)).thenReturn(usdArs("1000"));
        when(exchangeRates.getRate(CurrencyPair.of("EUR", "ARS"), T0))
                .thenThrow(new ExchangeRateNotFoundException(CurrencyPair.of("EUR", "ARS"), T0));

        mvc.perform(get("/api/v1/exchange-rates/usd/ars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("USD"));
        mvc.perform(get("/api/v1/exchange-rates/EUR/ARS").param("at", "2026-11-15T12:00:00Z"))
                .andExpect(status().isNotFound());
    }

    @Test
    void convert() throws Exception {
        Money source = Money.of(new BigDecimal("10.00"), "USD");
        when(conversions.convert(eq(source), eq("ARS"), isNull())).thenReturn(new Conversion(
                source, Money.of(new BigDecimal("11505.00"), "ARS"), new BigDecimal("1150.50000000"),
                usdArs("1150.5"), false));

        mvc.perform(post("/api/v1/exchange-rates/convert").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"fromCurrency\":\"usd\",\"toCurrency\":\"ARS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetAmount").value(11505.00))
                .andExpect(jsonPath("$.targetCurrency").value("ARS"))
                .andExpect(jsonPath("$.rateId").value("rate-1"))
                .andExpect(jsonPath("$.inverse").value(false));
    }

    @Test
    void convertUnsupportedCurrencyIs422() throws Exception {
        when(conversions.convert(any(), eq("BRL"), isNull())).thenThrow(new CurrencyNotSupportedException("BRL"));

        mvc.perform(post("/api/v1/exchange-rates/convert").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1,\"fromCurrency\":\"USD\",\"toCurrency\":\"BRL\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Currency not supported: BRL"));
    }

    @Test
    void cancelScheduledRate() throws Exception {
        when(exchangeRates.cancelScheduled("rate-1")).thenReturn(usdArs("1000"));
        when(exchangeRates.cancelScheduled("started")).thenThrow(new BusinessRuleException("already started"));
        when(exchangeRates.cancelScheduled("missing")).thenThrow(new NotFoundException("Exchange rate", "missing"));

        mvc.perform(delete("/api/v1/exchange-rates/rate-1")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/exchange-rates/started")).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/v1/exchange-rates/missing")).andExpect(status().isNotFound());
    }

    @Test
    void registerCurrencyWithoutTwoDecimalsIs422() throws Exception {
        when(currencies.register("JPY", "Yen"))
                .thenThrow(new BusinessRuleException("Currency JPY uses 0 decimals"));

        mvc.perform(post("/api/v1/currencies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"JPY\",\"name\":\"Yen\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
