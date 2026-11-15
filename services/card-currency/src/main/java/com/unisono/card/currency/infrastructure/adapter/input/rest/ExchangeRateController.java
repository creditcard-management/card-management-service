package com.unisono.card.currency.infrastructure.adapter.input.rest;

import com.unisono.card.currency.application.dto.request.ConvertAmountRequest;
import com.unisono.card.currency.application.dto.request.PublishExchangeRateRequest;
import com.unisono.card.currency.application.dto.response.ConversionResponse;
import com.unisono.card.currency.application.dto.response.ExchangeRateResponse;
import com.unisono.card.currency.application.mapper.CurrencyDtoMapper;
import com.unisono.card.currency.domain.model.CurrencyPair;
import com.unisono.card.currency.domain.port.input.ConvertAmountUseCase;
import com.unisono.card.currency.domain.port.input.ExchangeRateUseCase;
import com.unisono.card.currency.domain.port.input.ExchangeRateUseCase.PublishRateCommand;
import com.unisono.card.shared.domain.valueobject.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/exchange-rates")
@RequiredArgsConstructor
@Tag(name = "Exchange rates", description = "Cotizaciones por par y conversión de montos")
public class ExchangeRateController {

    private final ExchangeRateUseCase exchangeRates;
    private final ConvertAmountUseCase conversions;
    private final CurrencyDtoMapper mapper;

    @PostMapping
    @Operation(summary = "Publicar la cotización de un par (cierra la vigente)")
    public ResponseEntity<ExchangeRateResponse> publish(@Valid @RequestBody PublishExchangeRateRequest request) {
        PublishRateCommand command = new PublishRateCommand(
                CurrencyPair.of(request.baseCurrency(), request.quoteCurrency()),
                request.rate(), request.effectiveFrom());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(exchangeRates.publish(command)));
    }

    @DeleteMapping("/{rateId}")
    @Operation(summary = "Cancelar una cotización programada (que todavía no empezó); la anterior del par sigue vigente")
    public ResponseEntity<Void> cancelScheduled(@PathVariable String rateId) {
        exchangeRates.cancelScheduled(rateId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{base}/{quote}")
    @Operation(summary = "Cotización vigente del par (o la vigente en `at`); las dos monedas tienen que estar activas")
    public ExchangeRateResponse current(@PathVariable String base, @PathVariable String quote,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return mapper.toResponse(exchangeRates.getRate(CurrencyPair.of(base, quote), at));
    }

    @GetMapping("/{base}/{quote}/history")
    @Operation(summary = "Historial de cotizaciones del par, de la más nueva a la más vieja")
    public List<ExchangeRateResponse> history(@PathVariable String base, @PathVariable String quote,
                                              @RequestParam(defaultValue = "20") int limit) {
        return exchangeRates.getHistory(CurrencyPair.of(base, quote), limit).stream()
                .map(mapper::toResponse).toList();
    }

    @PostMapping("/convert")
    @Operation(summary = "Convertir un monto con la cotización vigente (directa o inversa)")
    public ConversionResponse convert(@Valid @RequestBody ConvertAmountRequest request) {
        Money amount = Money.of(request.amount(), CurrencyPair.currency(request.fromCurrency()));
        return mapper.toResponse(conversions.convert(amount, request.toCurrency(), request.at()));
    }
}
