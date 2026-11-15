package com.unisono.card.currency.application.mapper;

import com.unisono.card.currency.application.dto.response.ConversionResponse;
import com.unisono.card.currency.application.dto.response.CurrencyResponse;
import com.unisono.card.currency.application.dto.response.ExchangeRateResponse;
import com.unisono.card.currency.domain.model.Conversion;
import com.unisono.card.currency.domain.model.ExchangeRate;
import com.unisono.card.currency.domain.model.SupportedCurrency;
import org.springframework.stereotype.Component;

@Component
public class CurrencyDtoMapper {

    public CurrencyResponse toResponse(SupportedCurrency currency) {
        return new CurrencyResponse(currency.getCode(), currency.getName(), currency.getFractionDigits(),
                currency.isActive(), currency.getCreatedAt(), currency.getUpdatedAt());
    }

    public ExchangeRateResponse toResponse(ExchangeRate rate) {
        return new ExchangeRateResponse(rate.getRateId(), rate.getPair().baseCode(), rate.getPair().quoteCode(),
                rate.getRate(), rate.getValidFrom(), rate.getValidTo(), rate.getSource().name(), rate.getCreatedAt());
    }

    public ConversionResponse toResponse(Conversion conversion) {
        return new ConversionResponse(
                conversion.source().getAmount(), conversion.source().getCurrencyCode(),
                conversion.target().getAmount(), conversion.target().getCurrencyCode(),
                conversion.appliedRate(),
                conversion.isIdentity() ? null : conversion.exchangeRate().getRateId(),
                conversion.inverse());
    }
}
