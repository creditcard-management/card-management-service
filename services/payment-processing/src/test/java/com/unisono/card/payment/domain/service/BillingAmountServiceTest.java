package com.unisono.card.payment.domain.service;

import com.unisono.card.payment.domain.exception.CurrencyConversionException;
import com.unisono.card.payment.domain.exception.ExternalServiceException;
import com.unisono.card.payment.domain.model.TransactionAmount;
import com.unisono.card.payment.domain.port.output.CreditLimitPort;
import com.unisono.card.payment.domain.port.output.CurrencyConversionPort;
import com.unisono.card.payment.domain.port.output.CurrencyConversionPort.ConvertedAmount;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingAmountServiceTest {

    private static final CardId CARD_ID = CardId.of("550e8400-e29b-41d4-a716-446655440000");
    private static final Money USD_10 = Money.of(new BigDecimal("10.00"), "USD");

    @Mock
    private CreditLimitPort creditLimitPort;
    @Mock
    private CurrencyConversionPort currencyConversionPort;
    @InjectMocks
    private BillingAmountService service;

    @Test
    void sameCurrencyAsTheLimitIsNotConverted() {
        when(creditLimitPort.findLimitCurrency(CARD_ID)).thenReturn(Optional.of("ARS"));

        TransactionAmount amount = service.toBillingAmount(CARD_ID, Money.ofARS(1500));

        assertThat(amount.hasCurrencyConversion()).isFalse();
        assertThat(amount.getEffectiveAmount()).isEqualTo(Money.ofARS(1500));
        verifyNoInteractions(currencyConversionPort);
    }

    @Test
    void cardWithoutLimitIsNotConvertedSoTheCreditCheckDeclinesIt() {
        when(creditLimitPort.findLimitCurrency(CARD_ID)).thenReturn(Optional.empty());

        TransactionAmount amount = service.toBillingAmount(CARD_ID, USD_10);

        assertThat(amount.hasCurrencyConversion()).isFalse();
        verifyNoInteractions(currencyConversionPort);
    }

    @Test
    void foreignCurrencyIsConvertedToTheLimitCurrency() {
        when(creditLimitPort.findLimitCurrency(CARD_ID)).thenReturn(Optional.of("ARS"));
        when(currencyConversionPort.convert(USD_10, "ARS")).thenReturn(new ConvertedAmount(
                Money.ofARS(new BigDecimal("11505.00")), new BigDecimal("1150.50000000"), "rate-1"));

        TransactionAmount amount = service.toBillingAmount(CARD_ID, USD_10);

        assertThat(amount.getAmount()).isEqualTo(USD_10);
        assertThat(amount.getEffectiveAmount()).isEqualTo(Money.ofARS(new BigDecimal("11505.00")));
        assertThat(amount.getExchangeRate()).isEqualByComparingTo("1150.5");
        assertThat(amount.getExchangeRateId()).isEqualTo("rate-1");
    }

    @Test
    void conversionErrorsPropagate() {
        when(creditLimitPort.findLimitCurrency(CARD_ID)).thenReturn(Optional.of("ARS"));
        when(currencyConversionPort.convert(USD_10, "ARS"))
                .thenThrow(new CurrencyConversionException("USD", "ARS", "no exchange rate"));

        assertThatThrownBy(() -> service.toBillingAmount(CARD_ID, USD_10))
                .isInstanceOf(CurrencyConversionException.class);
    }

    @Test
    void rejectsConversionToAnotherCurrency() {
        when(creditLimitPort.findLimitCurrency(CARD_ID)).thenReturn(Optional.of("ARS"));
        when(currencyConversionPort.convert(USD_10, "ARS")).thenReturn(new ConvertedAmount(
                Money.of(new BigDecimal("9.00"), "EUR"), new BigDecimal("0.9"), "rate-2"));

        assertThatThrownBy(() -> service.toBillingAmount(CARD_ID, USD_10))
                .isInstanceOf(ExternalServiceException.class);
    }
}
