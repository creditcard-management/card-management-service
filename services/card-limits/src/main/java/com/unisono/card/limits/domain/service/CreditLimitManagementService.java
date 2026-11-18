package com.unisono.card.limits.domain.service;

import com.unisono.card.limits.domain.model.CreditLimit;
import com.unisono.card.limits.domain.model.CreditLimitHistory;
import com.unisono.card.limits.domain.model.CreditOperation;
import com.unisono.card.limits.domain.exception.CreditOperationCancelledException;
import com.unisono.card.limits.domain.port.input.*;
import com.unisono.card.limits.domain.port.output.CardValidationPort;
import com.unisono.card.limits.domain.port.output.CreditLimitRepository;
import com.unisono.card.limits.domain.port.output.CreditOperationRepository;
import com.unisono.card.limits.domain.port.output.LimitPolicyPort;
import com.unisono.card.limits.domain.service.CreditLimitEventEmitter.Movement;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.NotFoundException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Servicio de gestión de límites de crédito
 *
 * Implementa los use cases de:
 * - AssignCreditLimitUseCase
 * - UpdateCreditLimitUseCase
 * - GetCreditInfoUseCase
 * - RecalculateAvailableCreditUseCase
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreditLimitManagementService implements
        AssignCreditLimitUseCase,
        UpdateCreditLimitUseCase,
        GetCreditInfoUseCase,
        RecalculateAvailableCreditUseCase {

    private final CreditLimitRepository creditLimitRepository;
    private final CardValidationPort cardValidationPort;
    private final CreditOperationRepository creditOperationRepository;
    private final CreditLimitEventEmitter eventEmitter;
    private final LimitPolicyPort limitPolicyPort;

    // ========================================
    // AssignCreditLimitUseCase
    // ========================================

    @Override
    public CreditLimit execute(AssignCreditLimitCommand command) {
        log.info("Assigning credit limit to card: {}", command.cardId());

        // Validar que la tarjeta existe y está activa
        validateCard(command.cardId());

        // Validar que no tenga límite ya asignado
        if (creditLimitRepository.existsByCardId(command.cardId())) {
            throw new BusinessRuleException(
                    "La tarjeta ya tiene un límite de crédito asignado"
            );
        }

        // Crear el límite de crédito con los rangos y defaults de su moneda (422 si la moneda no tiene política)
        CreditLimit creditLimit = CreditLimit.assign(
                command.cardId(),
                command.purchaseLimitAmount(),
                command.cashAdvancePercentage(),
                limitPolicyPort.forCurrency(command.purchaseLimitAmount().getCurrencyCode()),
                command.assignedBy()
        );

        // Actualizar límites temporales si se especifican (se validan juntos: diario ≤ mensual)
        if (command.dailyLimitAmount() != null || command.monthlyLimitAmount() != null) {
            creditLimit.updateTemporalLimits(
                    command.dailyLimitAmount(),
                    command.monthlyLimitAmount(),
                    "Límites temporales iniciales",
                    command.assignedBy()
            );
        }

        // Guardar
        CreditLimit savedLimit = creditLimitRepository.save(creditLimit);

        // Guardar historial
        creditLimit.getHistory().forEach(creditLimitRepository::saveHistory);

        // Los límites diario y mensual iniciales son parte de la asignación: un solo evento
        eventEmitter.assigned(savedLimit, command.assignedBy());

        log.info("Credit limit assigned successfully: {}", savedLimit.getId());

        return savedLimit;
    }

    // ========================================
    // UpdateCreditLimitUseCase
    // ========================================

    @Override
    public CreditLimit increasePurchaseLimit(IncreasePurchaseLimitCommand command) {
        log.info("Increasing purchase limit for: {}", command.creditLimitId());

        CreditLimit creditLimit = lockCreditLimitOrThrow(command.creditLimitId());

        creditLimit.increasePurchaseLimit(
                command.additionalAmount(),
                limitPolicyPort.forCurrency(creditLimit.getCurrencyCode()),
                command.reason(),
                command.changedBy()
        );

        CreditLimit savedLimit = creditLimitRepository.save(creditLimit);

        // Guardar nuevo registro de historial y publicar el cambio
        CreditLimitHistory lastChange = creditLimit.getLastChange();
        if (lastChange != null && lastChange.getId() == null) {
            creditLimitRepository.saveHistory(lastChange);
            eventEmitter.limitChanged(savedLimit, lastChange);
        }

        log.info("Purchase limit increased successfully");

        return savedLimit;
    }

    @Override
    public CreditLimit decreasePurchaseLimit(DecreasePurchaseLimitCommand command) {
        log.info("Decreasing purchase limit for: {}", command.creditLimitId());

        CreditLimit creditLimit = lockCreditLimitOrThrow(command.creditLimitId());

        creditLimit.decreasePurchaseLimit(
                command.reductionAmount(),
                limitPolicyPort.forCurrency(creditLimit.getCurrencyCode()),
                command.reason(),
                command.changedBy()
        );

        CreditLimit savedLimit = creditLimitRepository.save(creditLimit);

        // Guardar nuevo registro de historial y publicar el cambio
        CreditLimitHistory lastChange = creditLimit.getLastChange();
        if (lastChange != null && lastChange.getId() == null) {
            creditLimitRepository.saveHistory(lastChange);
            eventEmitter.limitChanged(savedLimit, lastChange);
        }

        log.info("Purchase limit decreased successfully");

        return savedLimit;
    }

    @Override
    public CreditLimit updateCashAdvanceLimit(UpdateCashAdvanceLimitCommand command) {
        log.info("Updating cash advance limit for: {}", command.creditLimitId());

        CreditLimit creditLimit = lockCreditLimitOrThrow(command.creditLimitId());

        creditLimit.updateCashAdvanceLimit(
                command.newAmount(),
                limitPolicyPort.forCurrency(creditLimit.getCurrencyCode()),
                command.reason(),
                command.changedBy()
        );

        CreditLimit savedLimit = creditLimitRepository.save(creditLimit);

        // Guardar nuevo registro de historial y publicar el cambio
        CreditLimitHistory lastChange = creditLimit.getLastChange();
        if (lastChange != null && lastChange.getId() == null) {
            creditLimitRepository.saveHistory(lastChange);
            eventEmitter.limitChanged(savedLimit, lastChange);
        }

        log.info("Cash advance limit updated successfully");

        return savedLimit;
    }

    @Override
    public CreditLimit updateTemporalLimits(UpdateTemporalLimitsCommand command) {
        log.info("Updating temporal limits for: {}", command.creditLimitId());

        CreditLimit creditLimit = lockCreditLimitOrThrow(command.creditLimitId());

        creditLimit.updateTemporalLimits(
                command.dailyLimitAmount(),
                command.monthlyLimitAmount(),
                command.reason(),
                command.changedBy()
        );

        CreditLimit savedLimit = creditLimitRepository.save(creditLimit);

        // Guardar nuevos registros de historial
        List<CreditLimitHistory> newHistory = creditLimit.getHistory().stream()
                .filter(h -> h.getId() == null)
                .toList();
        newHistory.forEach(change -> {
            creditLimitRepository.saveHistory(change);
            eventEmitter.limitChanged(savedLimit, change);
        });

        log.info("Temporal limits updated successfully");

        return savedLimit;
    }

    // ========================================
    // RecalculateAvailableCreditUseCase
    // ========================================

    @Override
    public CreditLimit consumeForPurchase(ConsumeCreditCommand command) {
        log.info("Consuming credit for purchase: {} - Amount: {}{}",
                command.creditLimitId(), command.amount(), operationLog(command.operationId()));

        return applyOperation(command.creditLimitId(), command.operationId(), CreditOperation.Type.CONSUME_PURCHASE,
                command.description(), Movement.PURCHASE, creditLimit -> creditLimit.consumeForPurchase(command.amount()));
    }

    @Override
    public CreditLimit consumeForCashAdvance(ConsumeCreditCommand command) {
        log.info("Consuming credit for cash advance: {} - Amount: {}{}",
                command.creditLimitId(), command.amount(), operationLog(command.operationId()));

        return applyOperation(command.creditLimitId(), command.operationId(), CreditOperation.Type.CONSUME_CASH_ADVANCE,
                command.description(), Movement.CASH_ADVANCE,
                creditLimit -> creditLimit.consumeForCashAdvance(command.amount()));
    }

    @Override
    public CreditLimit releaseCredit(ReleaseCreditCommand command) {
        log.info("Releasing credit: {} - Payment: {}{}",
                command.creditLimitId(), command.paymentAmount(), operationLog(command.operationId()));

        return applyOperation(command.creditLimitId(), command.operationId(), CreditOperation.Type.RELEASE,
                command.description(), Movement.PAYMENT, creditLimit -> {
                    Money credited = creditLimit.releaseCredit(command.paymentAmount());
                    if (credited.isPositive()) {
                        log.info("Credit limit {}: payment exceeds the consumed credit, {} credited as balance",
                                command.creditLimitId(), credited);
                    }
                });
    }

    @Override
    public CreditLimit restoreCredit(RestoreCreditCommand command) {
        log.info("Restoring credit: {} - Amount: {} (original date {}) - {}{}",
                command.creditLimitId(), command.amount(), command.originalTransactionDate(), command.description(),
                operationLog(command.operationId()));

        return applyOperation(command.creditLimitId(), command.operationId(), CreditOperation.Type.RESTORE,
                command.description(), Movement.REFUND, creditLimit -> {
                    Money credited = creditLimit.restoreConsumption(command.amount(), command.originalTransactionDate(),
                            command.cashAdvance());
                    if (credited.isPositive()) {
                        log.info("Credit limit {}: {} credited as balance (the refunded consumption was already paid)",
                                command.creditLimitId(), credited);
                    }
                });
    }

    @Override
    public CancelOperationResult cancelOperation(CancelOperationCommand command) {
        log.info("Cancelling credit operation {} of credit limit {}", command.operationId(), command.creditLimitId());

        CreditLimit creditLimit = lockCreditLimitOrThrow(command.creditLimitId());
        Optional<CreditOperation> existing = creditOperationRepository.findById(command.operationId());

        if (existing.isEmpty()) {
            creditOperationRepository.save(CreditOperation.voided(
                    command.operationId(), command.creditLimitId(), creditLimit.getCurrencyCode()));
            log.info("Credit operation {} had not been applied: voided", command.operationId());
            return new CancelOperationResult(CancelOutcome.VOIDED, creditLimit);
        }

        CreditOperation operation = existing.get();
        validateOwnership(operation, command.creditLimitId());

        if (!operation.isApplied()) {
            log.info("Credit operation {} was already {}", command.operationId(), operation.getStatus());
            return new CancelOperationResult(CancelOutcome.ALREADY_CANCELLED, creditLimit);
        }

        Money netDebtBefore = creditLimit.getNetDebt();
        creditLimit.revertOperation(operation);
        CreditLimit savedLimit = creditLimitRepository.save(creditLimit);
        operation.cancel();
        creditOperationRepository.save(operation);
        eventEmitter.creditMoved(savedLimit, netDebtBefore, cancelledMovement(operation.getType()),
                command.operationId());

        log.info("Credit operation {} ({}) cancelled. Available: {}",
                command.operationId(), operation.getType(), savedLimit.getAvailableCredit().getAmount());
        if (savedLimit.getOverLimitAmount().isPositive()) {
            log.warn("⚠️ Credit limit {} is over its limit by {} after undoing operation {} ({}): the restored "
                            + "or paid credit had already been spent. New purchases are declined until it is paid",
                    command.creditLimitId(), savedLimit.getOverLimitAmount(), command.operationId(), operation.getType());
        }
        return new CancelOperationResult(CancelOutcome.CANCELLED, savedLimit);
    }

    /**
     * Todas las modificaciones del límite (cambios de límites, consumos, restauraciones, liberaciones y
     * cancelaciones) leen con {@link #lockCreditLimitOrThrow}: el guardado reescribe la fila completa, así que
     * sin el lock una modificación concurrente pisaría a la otra (lost update).
     *
     * Aplica un consumo, una restauración o una liberación con el límite bloqueado y la registra en
     * credit_operations. Es idempotente por operationId: una operación ya aplicada no se repite y una cancelada
     * se rechaza (con un id generado, cada request es una operación nueva).
     */
    private CreditLimit applyOperation(String creditLimitId, String operationId, CreditOperation.Type type,
                                       String description, Movement movement, Consumer<CreditLimit> action) {
        CreditLimit creditLimit = lockCreditLimitOrThrow(creditLimitId);

        Optional<CreditOperation> existing = creditOperationRepository.findById(operationId);
        if (existing.isPresent()) {
            CreditOperation operation = existing.get();
            validateOwnership(operation, creditLimitId);
            if (!operation.isApplied()) {
                throw new CreditOperationCancelledException(operationId);
            }
            if (operation.getType() != type) {
                throw new BusinessRuleException(String.format(
                        "La operación %s ya existe con otro tipo (%s)", operationId, operation.getType()));
            }
            log.info("Credit operation {} was already applied: nothing to do", operationId);
            return creditLimit;
        }

        // Solo los consumos exigen tarjeta activa: restaurar (reversos y devoluciones) y liberar (pagos) deben
        // seguir funcionando sobre una tarjeta ya bloqueada, cancelada o vencida. Va después de la idempotencia:
        // repetir una operación ya aplicada responde 200 aunque la tarjeta se haya bloqueado mientras tanto
        if (type.isConsumption()) {
            ensureCardActive(creditLimit);
        }

        CreditLimit.ConsumptionSnapshot before = creditLimit.consumptionSnapshot();
        Money netDebtBefore = creditLimit.getNetDebt();
        action.accept(creditLimit);
        CreditLimit savedLimit = creditLimitRepository.save(creditLimit);

        // Todo movimiento queda registrado: con el operationId del cliente o con uno generado (ver los commands)
        creditOperationRepository.save(CreditOperation.applied(
                operationId, creditLimitId, type, before, creditLimit.consumptionSnapshot(), description));
        // Solo cuando se aplicó: una operación repetida (idempotente) ya publicó su evento
        eventEmitter.creditMoved(savedLimit, netDebtBefore, movement, operationId);

        log.info("Credit operation {} applied. Available: {}", type, savedLimit.getAvailableCredit().getAmount());
        return savedLimit;
    }

    private static Movement cancelledMovement(CreditOperation.Type type) {
        return switch (type) {
            case RESTORE -> Movement.REFUND_CANCELLED;
            case RELEASE -> Movement.PAYMENT_CANCELLED;
            case CONSUME_PURCHASE, CONSUME_CASH_ADVANCE -> Movement.CONSUMPTION_CANCELLED;
        };
    }

    private void ensureCardActive(CreditLimit creditLimit) {
        if (!cardValidationPort.isCardActive(creditLimit.getCardId())) {
            throw new BusinessRuleException(String.format(
                    "La tarjeta %s no está activa: no puede consumir crédito", creditLimit.getCardId().getValue()));
        }
    }

    private static void validateOwnership(CreditOperation operation, String creditLimitId) {
        if (!operation.getCreditLimitId().equals(creditLimitId)) {
            throw new BusinessRuleException(String.format(
                    "La operación %s pertenece a otro límite de crédito", operation.getOperationId()));
        }
    }

    private static String operationLog(String operationId) {
        return " (operation " + operationId + ")";
    }

    private CreditLimit lockCreditLimitOrThrow(String creditLimitId) {
        return creditLimitRepository.findByIdForUpdate(creditLimitId)
                .orElseThrow(() -> new NotFoundException("CreditLimit", creditLimitId));
    }

    // ========================================
    // GetCreditInfoUseCase
    // ========================================

    @Override
    @Transactional(readOnly = true)
    public Optional<CreditLimit> findById(String creditLimitId) {
        log.debug("Finding credit limit by ID: {}", creditLimitId);
        return creditLimitRepository.findById(creditLimitId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CreditLimit> findByCardId(CardId cardId) {
        log.debug("Finding credit limit for card: {}", cardId);
        return creditLimitRepository.findByCardId(cardId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditLimitHistory> getHistory(String creditLimitId) {
        log.debug("Getting history for credit limit: {}", creditLimitId);

        // Verificar que el límite existe
        getCreditLimitOrThrow(creditLimitId);

        return creditLimitRepository.findHistoryByCreditLimitId(creditLimitId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasLimitAssigned(CardId cardId) {
        return creditLimitRepository.existsByCardId(cardId);
    }

    // ========================================
    // Helper methods
    // ========================================

    private CreditLimit getCreditLimitOrThrow(String creditLimitId) {
        return creditLimitRepository.findById(creditLimitId)
                .orElseThrow(() -> new NotFoundException("CreditLimit", creditLimitId));
    }

    private void validateCard(CardId cardId) {
        if (!cardValidationPort.cardExists(cardId)) {
            throw new NotFoundException("Card", cardId.getValue());
        }

        if (!cardValidationPort.canAssignCreditLimit(cardId)) {
            throw new BusinessRuleException(
                    "La tarjeta no puede recibir límite de crédito en su estado actual"
            );
        }
    }
}
