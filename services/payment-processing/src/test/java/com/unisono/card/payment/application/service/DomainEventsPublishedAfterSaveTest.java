package com.unisono.card.payment.application.service;

import com.unisono.card.events.domain.DomainEvent;
import com.unisono.card.payment.application.dto.request.ProcessPaymentCommand;
import com.unisono.card.payment.application.dto.request.ProcessRefundCommand;
import com.unisono.card.payment.application.mapper.PaymentMapper;
import com.unisono.card.payment.application.mapper.RefundMapper;
import com.unisono.card.payment.domain.model.AuthorizationCode;
import com.unisono.card.payment.domain.model.CardData;
import com.unisono.card.payment.domain.model.MerchantInfo;
import com.unisono.card.payment.domain.model.PaymentMethod;
import com.unisono.card.payment.domain.model.Transaction;
import com.unisono.card.payment.domain.model.TransactionAmount;
import com.unisono.card.payment.domain.model.TransactionType;
import com.unisono.card.payment.domain.port.output.PaymentEventPublisher;
import com.unisono.card.payment.domain.port.output.TransactionRepository;
import com.unisono.card.payment.domain.service.BillingAmountService;
import com.unisono.card.payment.domain.service.PaymentDomainService;
import com.unisono.card.payment.domain.service.TransactionValidationService;
import com.unisono.card.payment.infrastructure.adapter.output.persistence.mapper.TransactionEntityMapper;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.ExpirationDate;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regresión: {@link TransactionRepository#save} devuelve la transacción reconstruida desde la base, sin los
 * eventos de dominio pendientes. Los servicios tienen que publicar los eventos que la operación registró en el
 * aggregate que recibieron; antes publicaban los del objeto guardado (ninguno) y payment-processing no emitía
 * ningún evento, así que card-data nunca bloqueaba una tarjeta por fraude.
 * <p>
 * Acá {@code save} se comporta como el adapter real: ida y vuelta por {@link TransactionEntityMapper}.
 */
class DomainEventsPublishedAfterSaveTest {

    private static final CardId CARD_ID = CardId.of("550e8400-e29b-41d4-a716-446655440000");

    private final TransactionEntityMapper entityMapper = new TransactionEntityMapper();
    private final PaymentDomainService domainService = mock(PaymentDomainService.class);
    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final PaymentEventPublisher publisher = mock(PaymentEventPublisher.class);

    private final List<String> published = new java.util.ArrayList<>();

    @BeforeEach
    void saveRebuildsFromTheDatabase() {
        doAnswer(inv -> {
            Iterable<DomainEvent> events = inv.getArgument(0);
            events.forEach(e -> published.add(e.getClass().getSimpleName().replace("Event", "")));
            return null;
        }).when(publisher).publishAll(any());
        when(repository.save(any())).thenAnswer(inv -> {
            Transaction stored = entityMapper.toDomain(entityMapper.toEntity(inv.getArgument(0)));
            assertThat(stored.getDomainEvents()).as("rebuilt aggregate has no events").isEmpty();
            return stored;
        });
    }

    @Test
    void purchasePublishesInitiatedAuthorizedAndProcessed() {
        BillingAmountService billing = mock(BillingAmountService.class);
        when(billing.toBillingAmount(eq(CARD_ID), any())).thenAnswer(inv -> TransactionAmount.of(inv.getArgument(1)));
        when(domainService.processPayment(any(), any(), any(), any(), any(), eq(TransactionType.PURCHASE), any()))
                .thenAnswer(inv -> {
                    Transaction tx = Transaction.createPurchase(CARD_ID, inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), inv.getArgument(4));
                    tx.authorize(AuthorizationCode.approved("AUTH01"), 10);
                    tx.approve();
                    return tx;
                });
        PaymentApplicationService service = new PaymentApplicationService(domainService, billing,
                new TransactionValidationService(), repository, publisher, Mappers.getMapper(PaymentMapper.class));

        service.processPurchase(purchaseCommand());

        assertThat(publishedTypes()).containsExactly("PaymentInitiated", "PaymentAuthorized", "PaymentProcessed");
    }

    @Test
    void declinedPurchasePublishesPaymentDeclined() {
        BillingAmountService billing = mock(BillingAmountService.class);
        when(billing.toBillingAmount(eq(CARD_ID), any())).thenAnswer(inv -> TransactionAmount.of(inv.getArgument(1)));
        when(domainService.processPayment(any(), any(), any(), any(), any(), eq(TransactionType.PURCHASE), any()))
                .thenAnswer(inv -> {
                    Transaction tx = Transaction.createPurchase(CARD_ID, inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), inv.getArgument(4));
                    tx.decline("Suspicious transaction", AuthorizationCode.declined("59"));
                    return tx;
                });
        PaymentApplicationService service = new PaymentApplicationService(domainService, billing,
                new TransactionValidationService(), repository, publisher, Mappers.getMapper(PaymentMapper.class));

        service.processPurchase(purchaseCommand());

        assertThat(publishedTypes()).containsExactly("PaymentInitiated", "PaymentDeclined");
    }

    @Test
    void capturePublishesProcessed() {
        Transaction authorized = authorizedPurchase();
        when(repository.findById(authorized.getTransactionId())).thenReturn(Optional.of(authorized));
        doAnswer(inv -> {
            ((Transaction) inv.getArgument(0)).approve();
            return null;
        }).when(domainService).captureTransaction(authorized);
        PaymentApplicationService service = new PaymentApplicationService(domainService,
                mock(BillingAmountService.class), new TransactionValidationService(), repository, publisher,
                Mappers.getMapper(PaymentMapper.class));

        service.captureTransaction(authorized.getTransactionId());

        assertThat(publishedTypes()).containsExactly("PaymentProcessed");
    }

    @Test
    void reversalPublishesPaymentReversed() {
        Transaction purchase = approvedPurchase();
        when(repository.findByIdForUpdate(purchase.getTransactionId())).thenReturn(Optional.of(purchase));
        when(repository.findRefundsOf(purchase.getTransactionId())).thenReturn(List.of());
        doAnswer(inv -> {
            ((Transaction) inv.getArgument(0)).reverse(inv.getArgument(1));
            return null;
        }).when(domainService).reverseTransaction(eq(purchase), anyString());
        TransactionReversalService service = new TransactionReversalService(domainService,
                new TransactionValidationService(), repository, publisher);

        service.reverseTransaction(purchase.getTransactionId(), "Cliente desistió");

        assertThat(publishedTypes()).containsExactly("PaymentReversed");
    }

    @Test
    void settlementPublishesTransactionSettled() {
        Transaction purchase = approvedPurchase();
        when(repository.findById(purchase.getTransactionId())).thenReturn(Optional.of(purchase));
        doAnswer(inv -> {
            ((Transaction) inv.getArgument(0)).settle();
            return null;
        }).when(domainService).settleTransaction(purchase);
        SettlementService service = new SettlementService(domainService, repository, publisher);

        service.settleTransaction(purchase.getTransactionId());

        assertThat(publishedTypes()).containsExactly("TransactionSettled");
    }

    @Test
    void refundPublishesRefundInitiatedAndProcessed() {
        Transaction original = approvedPurchase();
        when(repository.findByIdForUpdate(original.getTransactionId())).thenReturn(Optional.of(original));
        when(repository.findRefundsOf(original.getTransactionId())).thenReturn(List.of());
        when(domainService.processRefund(eq(original), eq(CARD_ID), any(), any(), eq(false))).thenAnswer(inv -> {
            Transaction refund = Transaction.createRefund(CARD_ID, original.getCardData(), original.getMerchantInfo(),
                    inv.getArgument(2), original.getTransactionId());
            refund.approveRefund(original.getTransactionId());
            return refund;
        });
        RefundApplicationService service = new RefundApplicationService(domainService, repository, publisher,
                Mappers.getMapper(RefundMapper.class));

        service.processPartialRefund(ProcessRefundCommand.builder()
                .originalTransactionId(original.getTransactionId().toString())
                .cardId(CARD_ID.getValue())
                .refundAmount(new BigDecimal("500.00"))
                .currency("ARS")
                .merchantId("MERCH-001")
                .reason("Producto devuelto")
                .build());

        assertThat(publishedTypes()).containsExactly("RefundInitiated", "RefundProcessed");
    }

    // ========== Helpers ==========

    /** Tipos publicados, copiados en el momento de la llamada (después el servicio limpia la lista). */
    private List<String> publishedTypes() {
        verify(publisher).publishAll(any());
        return published;
    }

    private static ProcessPaymentCommand purchaseCommand() {
        YearMonth expiration = YearMonth.now().plusYears(3);
        return ProcessPaymentCommand.builder()
                .cardId(CARD_ID.getValue())
                .cardNumberToken("tok_123")
                .lastFourDigits("0366")
                .expirationDate(String.format("%02d/%d", expiration.getMonthValue(), expiration.getYear()))
                .amount(new BigDecimal("1500.00"))
                .currency("ARS")
                .merchantId("MERCH-001")
                .merchantName("Supermercado Uno")
                .paymentMethod(PaymentMethod.CHIP)
                .build();
    }

    private static Transaction authorizedPurchase() {
        YearMonth expiration = YearMonth.now().plusYears(3);
        Transaction tx = Transaction.createPurchase(CARD_ID,
                CardData.of("tok_123", "0366", "Juan Pérez",
                        ExpirationDate.of(expiration.getMonthValue(), expiration.getYear())),
                MerchantInfo.of("MERCH-001", "Supermercado Uno", "5411", "CABA", "AR"),
                TransactionAmount.of(Money.ofARS(1500)), PaymentMethod.CHIP);
        tx.authorize(AuthorizationCode.approved("AUTH01"), 10);
        tx.clearDomainEvents();
        return tx;
    }

    private static Transaction approvedPurchase() {
        Transaction tx = authorizedPurchase();
        tx.approve();
        tx.clearDomainEvents();
        return tx;
    }
}
