package com.unisono.card.currency.infrastructure.adapter.input.rest;

import com.unisono.card.currency.application.dto.request.RegisterCurrencyRequest;
import com.unisono.card.currency.application.dto.response.CurrencyResponse;
import com.unisono.card.currency.application.mapper.CurrencyDtoMapper;
import com.unisono.card.currency.domain.port.input.ManageCurrenciesUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/currencies")
@RequiredArgsConstructor
@Tag(name = "Currencies", description = "Catálogo de monedas aceptadas")
public class CurrencyController {

    private final ManageCurrenciesUseCase currencies;
    private final CurrencyDtoMapper mapper;

    @PostMapping
    @Operation(summary = "Dar de alta una moneda (queda activa)")
    public ResponseEntity<CurrencyResponse> register(@Valid @RequestBody RegisterCurrencyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(currencies.register(request.code(), request.name())));
    }

    @GetMapping
    @Operation(summary = "Listar monedas")
    public List<CurrencyResponse> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return currencies.list(activeOnly).stream().map(mapper::toResponse).toList();
    }

    @GetMapping("/{code}")
    @Operation(summary = "Consultar una moneda")
    public CurrencyResponse get(@PathVariable String code) {
        return mapper.toResponse(currencies.get(code));
    }

    @PutMapping("/{code}/activate")
    @Operation(summary = "Activar una moneda")
    public CurrencyResponse activate(@PathVariable String code) {
        return mapper.toResponse(currencies.activate(code));
    }

    @PutMapping("/{code}/deactivate")
    @Operation(summary = "Desactivar una moneda (no se puede si tiene límites de crédito)")
    public CurrencyResponse deactivate(@PathVariable String code) {
        return mapper.toResponse(currencies.deactivate(code));
    }
}
