package com.unisono.card.payment.domain.service;

import com.unisono.card.payment.domain.exception.ExternalServiceException;
import com.unisono.card.payment.domain.model.TransactionAmount;
import com.unisono.card.payment.domain.port.output.CreditLimitPort;
import com.unisono.card.payment.domain.port.output.CurrencyConversionPort;
import com.unisono.card.payment.domain.port.output.CurrencyConversionPort.ConvertedAmount;
import com.unisono.card.shared.domain.valueobject.Money;
import com.unisono.card.shared.domain.valueobject.CardId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Resolves the amount a debit is billed in: the currency of the card's credit limit.
 * <p>
 * If the transaction is in another currency, card-currency converts it with the rate valid now. The transaction
 * keeps the original amount and the converted one (the effective amount, which is what card-limits consumes),
 * together with the rate and the card-currency rate used. Clients cannot send their own rate.
 */
@Slf4j
@RequiredArgsConstructor
public class BillingAmountService {

    private final CreditLimitPort creditLimitPort;
    private final CurrencyConversionPort currencyConversionPort;

    public TransactionAmount toBillingAmount(CardId cardId, Money amount) {
        Optional<String> limitCurrency = creditLimitPort.findLimitCurrency(cardId);
        if (limitCurrency.isEmpty()) {
            // Sin límite: el pago se rechaza más adelante en el chequeo de crédito ("Card has no credit limit")
            return TransactionAmount.of(amount);
        }
        String billingCurrency = limitCurrency.get();
        if (billingCurrency.equals(amount.getCurrencyCode())) {
            return TransactionAmount.of(amount);
        }

        ConvertedAmount conversion = currencyConversionPort.convert(amount, billingCurrency);
        if (!billingCurrency.equals(conversion.converted().getCurrencyCode())) {
            throw new ExternalServiceException("card-currency", String.format(
                    "converted to %s instead of %s", conversion.converted().getCurrencyCode(), billingCurrency), null);
        }
        log.info("Card {}: {} billed as {} (rate {}, {})", cardId, amount, conversion.converted(),
                conversion.rate(), conversion.rateId());
        return TransactionAmount.withConversion(amount, conversion.converted(), conversion.rate(),
                conversion.rateId());
    }
}
