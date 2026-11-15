package com.unisono.card.payment.infrastructure.config;

import com.unisono.card.payment.domain.service.BillingAmountService;
import com.unisono.card.payment.domain.service.PaymentDomainService;
import com.unisono.card.payment.domain.service.TransactionValidationService;
import com.unisono.card.payment.domain.port.output.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean configuration for domain services
 */
@Configuration
public class BeanConfig {

    /**
     * Payment domain service bean
     */
    @Bean
    public PaymentDomainService paymentDomainService(
            CardValidationPort cardValidationPort,
            CreditLimitPort creditLimitPort,
            MerchantValidationPort merchantValidationPort,
            FraudCheckPort fraudCheckPort,
            AuthorizationPort authorizationPort) {

        return new PaymentDomainService(
                cardValidationPort,
                creditLimitPort,
                merchantValidationPort,
                fraudCheckPort,
                authorizationPort
        );
    }

    /**
     * Billing amount (conversion to the credit limit currency) bean
     */
    @Bean
    public BillingAmountService billingAmountService(CreditLimitPort creditLimitPort,
                                                     CurrencyConversionPort currencyConversionPort) {
        return new BillingAmountService(creditLimitPort, currencyConversionPort);
    }

    /**
     * Transaction validation service bean
     */
    @Bean
    public TransactionValidationService transactionValidationService() {
        return new TransactionValidationService();
    }
}
