package com.unisono.card.limits.domain.model;

import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.exception.ValidationException;
import com.unisono.card.shared.domain.valueobject.CardId;
import com.unisono.card.shared.domain.valueobject.Money;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate Root: CreditLimit
 *
 * Representa los límites de crédito de una tarjeta.
 *
 * Invariantes:
 * - El límite de adelantos no puede exceder el límite de compras
 * - El crédito consumido no puede exceder el límite total
 * - Lo consumido y el saldo a favor nunca son mayores a cero a la vez
 * - El crédito disponible = límite total - crédito consumido + saldo a favor
 * - Los límites diarios/mensuales se auto-resetean
 *
 * Todo movimiento cambia la posición neta (consumido − saldo a favor): un consumo la sube, un pago o una
 * devolución la bajan. Si queda positiva es deuda (consumido); si queda negativa, saldo a favor.
 */
@Getter
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CreditLimit {

    public static final String NO_CASH_ADVANCE = "La tarjeta no tiene adelantos en efectivo habilitados";

    private String id;
    private CardId cardId;

    // Límites principales
    private PurchaseLimit purchaseLimit;
    private CashAdvanceLimit cashAdvanceLimit;

    // Crédito actual
    private ConsumedCredit consumedCredit;
    /** Parte de lo consumido que corresponde a adelantos en efectivo (≤ consumedCredit) */
    private ConsumedCredit cashAdvanceConsumed;
    /** Saldo a favor: mayor a cero solo si lo consumido es cero */
    private CreditBalance creditBalance;
    private AvailableCredit availableCredit;

    // Límites temporales
    private DailyLimit dailyLimit;
    private MonthlyLimit monthlyLimit;
    @Builder.Default
    private List<CreditLimitHistory> history = new ArrayList<>();
    // Auditoría
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ========================================
    // FACTORY METHODS
    // ========================================

    /**
     * Asigna un nuevo límite de crédito a una tarjeta, con los rangos y los límites diario y mensual por
     * defecto de la política de su moneda
     */
    public static CreditLimit assign(
            CardId cardId,
            Money purchaseLimitAmount,
            double cashAdvancePercentage,
            LimitPolicy policy,
            String assignedBy
    ) {
        validateAssignment(cardId, purchaseLimitAmount, cashAdvancePercentage, policy);

        String currency = purchaseLimitAmount.getCurrencyCode();
        PurchaseLimit purchaseLimit = PurchaseLimit.of(purchaseLimitAmount, policy);
        CashAdvanceLimit cashAdvanceLimit = CashAdvanceLimit.fromPurchaseLimit(
                purchaseLimit,
                cashAdvancePercentage,
                policy
        );

        LocalDateTime now = LocalDateTime.now();
        String creditLimitId = java.util.UUID.randomUUID().toString();

        // ⭐ Crear registro histórico inicial
        List<CreditLimitHistory> initialHistory = new ArrayList<>();
        initialHistory.add(CreditLimitHistory.recordAssignment(
                creditLimitId,
                LimitType.PURCHASE,
                purchaseLimitAmount,
                "Asignación inicial de límite",
                assignedBy
        ));

        return CreditLimit.builder()
                .id(creditLimitId)
                .cardId(cardId)
                .purchaseLimit(purchaseLimit)
                .cashAdvanceLimit(cashAdvanceLimit)
                .consumedCredit(ConsumedCredit.zero(currency))
                .cashAdvanceConsumed(ConsumedCredit.zero(currency))
                .creditBalance(CreditBalance.zero(currency))
                .availableCredit(AvailableCredit.of(purchaseLimitAmount))
                .dailyLimit(DailyLimit.defaultLimit(policy))
                .monthlyLimit(MonthlyLimit.defaultLimit(policy))
                .history(initialHistory)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * Reconstruye desde persistencia
     */
    public static CreditLimit reconstituteFromPersistence(
            String id,
            CardId cardId,
            PurchaseLimit purchaseLimit,
            CashAdvanceLimit cashAdvanceLimit,
            ConsumedCredit consumedCredit,
            ConsumedCredit cashAdvanceConsumed,
            CreditBalance creditBalance,
            AvailableCredit availableCredit,
            DailyLimit dailyLimit,
            MonthlyLimit monthlyLimit,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        return CreditLimit.builder()
                .id(id)
                .cardId(cardId)
                .purchaseLimit(purchaseLimit)
                .cashAdvanceLimit(cashAdvanceLimit)
                .consumedCredit(consumedCredit)
                .cashAdvanceConsumed(cashAdvanceConsumed)
                .creditBalance(creditBalance)
                .availableCredit(availableCredit)
                .dailyLimit(dailyLimit)
                .monthlyLimit(monthlyLimit)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    // ========================================
    // BUSINESS LOGIC - UPDATE LIMITS
    // ========================================

    /**
     * Aumenta el límite de compras (el nuevo total debe quedar dentro del rango de la política)
     */
    public void increasePurchaseLimit(Money additionalAmount, LimitPolicy policy, String reason, String changedBy) {
        validateChangeAmount(additionalAmount);
        Money previousLimit = this.purchaseLimit.getAmount();

        this.purchaseLimit = this.purchaseLimit.increase(additionalAmount, policy);

        // ⭐ Registrar en historial
        this.history.add(CreditLimitHistory.recordIncrease(
                this.id,
                LimitType.PURCHASE,
                previousLimit,
                this.purchaseLimit.getAmount(),
                reason,
                changedBy
        ));

        recalculateAvailableCredit();
        touch();
    }

    /**
     * Disminuye el límite de compras (el nuevo total debe quedar dentro del rango de la política)
     */
    public void decreasePurchaseLimit(Money reductionAmount, LimitPolicy policy, String reason, String changedBy) {
        validateChangeAmount(reductionAmount);
        Money previousLimit = this.purchaseLimit.getAmount();

        PurchaseLimit newLimit = this.purchaseLimit.decrease(reductionAmount, policy);

        // Validar que el nuevo límite no sea menor al crédito consumido
        if (newLimit.getAmount().isLessThan(this.consumedCredit.getAmount())) {
            throw new BusinessRuleException(
                    "No se puede reducir el límite por debajo del crédito consumido actual"
            );
        }

        // Invariante: el límite de adelantos no puede exceder el de compras
        if (this.cashAdvanceLimit.getAmount().isGreaterThan(newLimit.getAmount())) {
            throw new BusinessRuleException(String.format(
                    "No se puede reducir el límite de compras a %s: es menor al límite de adelantos de %s. "
                            + "Reducir primero el límite de adelantos",
                    newLimit.getAmount(), this.cashAdvanceLimit.getAmount()));
        }

        this.purchaseLimit = newLimit;

        // ⭐ Registrar en historial
        this.history.add(CreditLimitHistory.recordDecrease(
                this.id,
                LimitType.PURCHASE,
                previousLimit,
                this.purchaseLimit.getAmount(),
                reason,
                changedBy
        ));

        recalculateAvailableCredit();
        touch();
    }

    /**
     * Actualiza el límite de adelantos (dentro del rango de la política, o cero para deshabilitarlos)
     */
    public void updateCashAdvanceLimit(Money newAmount, LimitPolicy policy, String reason, String changedBy) {
        Money previousLimit = this.cashAdvanceLimit.getAmount();

        // Validar que no exceda el límite de compras
        if (newAmount.isGreaterThan(this.purchaseLimit.getAmount())) {
            throw new BusinessRuleException(
                    "El límite de adelantos no puede exceder el límite de compras"
            );
        }

        this.cashAdvanceLimit = CashAdvanceLimit.of(newAmount, policy);

        // ⭐ Registrar en historial
        this.history.add(CreditLimitHistory.recordUpdate(
                this.id,
                LimitType.CASH_ADVANCE,
                previousLimit,
                newAmount,
                reason,
                changedBy
        ));

        touch();
    }

    /**
     * Actualiza el límite diario
     */
    public void updateDailyLimit(Money newDailyLimit, String reason, String changedBy) {
        updateTemporalLimits(newDailyLimit, null, reason, changedBy);
    }

    /**
     * Actualiza el límite mensual
     */
    public void updateMonthlyLimit(Money newMonthlyLimit, String reason, String changedBy) {
        updateTemporalLimits(null, newMonthlyLimit, reason, changedBy);
    }

    /**
     * Actualiza el límite diario y/o el mensual (null = no cambia). Valida la combinación final:
     * ambos mayores a cero y diario ≤ mensual. Así se pueden subir los dos a la vez aunque el diario nuevo
     * supere al mensual anterior.
     */
    public void updateTemporalLimits(Money newDailyLimit, Money newMonthlyLimit, String reason, String changedBy) {
        Money finalDaily = newDailyLimit != null ? newDailyLimit : this.dailyLimit.getAmount();
        Money finalMonthly = newMonthlyLimit != null ? newMonthlyLimit : this.monthlyLimit.getAmount();
        validateTemporalLimitAmount(newDailyLimit, "diario");
        validateTemporalLimitAmount(newMonthlyLimit, "mensual");
        if (finalDaily.isGreaterThan(finalMonthly)) {
            throw new BusinessRuleException(String.format(
                    "El límite diario (%s) no puede superar al mensual (%s)", finalDaily, finalMonthly));
        }

        if (newDailyLimit != null) {
            Money previousLimit = this.dailyLimit.getAmount();
            this.dailyLimit = this.dailyLimit.updateLimit(newDailyLimit);
            this.history.add(CreditLimitHistory.recordUpdate(
                    this.id, LimitType.DAILY, previousLimit, newDailyLimit, reason, changedBy));
        }

        if (newMonthlyLimit != null) {
            Money previousLimit = this.monthlyLimit.getAmount();
            this.monthlyLimit = this.monthlyLimit.updateLimit(newMonthlyLimit);
            this.history.add(CreditLimitHistory.recordUpdate(
                    this.id, LimitType.MONTHLY, previousLimit, newMonthlyLimit, reason, changedBy));
        }

        touch();
    }

    private static void validateTemporalLimitAmount(Money amount, String label) {
        if (amount != null && !amount.isPositive()) {
            throw new ValidationException(String.format("El límite %s debe ser mayor a cero", label));
        }
    }


    // ========================================
    // BUSINESS LOGIC - CONSUME CREDIT
    // ========================================

    /**
     * Consume crédito para una compra. Usa primero el saldo a favor y el resto pasa a lo consumido.
     */
    public void consumeForPurchase(Money purchaseAmount) {
        validatePurchaseAmount(purchaseAmount);

        // Validar crédito disponible (límite de compras − consumido + saldo a favor)
        if (!this.availableCredit.isSufficientFor(purchaseAmount)) {
            throw new BusinessRuleException(
                    String.format("Crédito insuficiente. Disponible: %s, Solicitado: %s",
                            this.availableCredit.getAmount(), purchaseAmount)
            );
        }

        // Validar límites temporales
        validateTemporalLimits(purchaseAmount);

        // Consumir crédito: primero el saldo a favor; los límites diario y mensual cuentan el monto completo
        moveNetDebt(purchaseAmount);
        this.dailyLimit = this.dailyLimit.consume(purchaseAmount);
        this.monthlyLimit = this.monthlyLimit.consume(purchaseAmount);

        recalculateAvailableCredit();
        touch();
    }

    /**
     * Consume crédito para un adelanto en efectivo. Como una compra, usa primero el saldo a favor; solo la parte
     * que pasa a deuda cuenta contra el tope de adelantos.
     */
    public void consumeForCashAdvance(Money advanceAmount) {
        validatePurchaseAmount(advanceAmount);

        if (!this.cashAdvanceLimit.isEnabled()) {
            throw new BusinessRuleException(NO_CASH_ADVANCE);
        }

        // Validar límite de adelantos: es acumulativo (lo ya usado en adelantos descuenta del tope) y cuenta solo
        // la parte que no cubre el saldo a favor
        Money advanceDebt = debtPartOf(advanceAmount);
        if (advanceDebt.isGreaterThan(getCashAdvanceAvailable())) {
            throw new BusinessRuleException(
                    String.format("El adelanto de %s excede el disponible para adelantos de %s (tope %s, usado %s%s)",
                            advanceAmount, getCashAdvanceAvailable(), this.cashAdvanceLimit.getAmount(),
                            this.cashAdvanceConsumed.getAmount(),
                            this.creditBalance.isPositive() ? ", saldo a favor " + this.creditBalance.getAmount() : "")
            );
        }

        // Validar crédito disponible
        if (!this.availableCredit.isSufficientFor(advanceAmount)) {
            throw new BusinessRuleException(
                    String.format("Crédito insuficiente. Disponible: %s, Solicitado: %s",
                            this.availableCredit.getAmount(), advanceAmount)
            );
        }

        // Validar límites temporales
        validateTemporalLimits(advanceAmount);

        // Consumir crédito: lo que pasa a deuda es lo usado en adelantos
        Money newDebt = moveNetDebt(advanceAmount);
        this.cashAdvanceConsumed = this.cashAdvanceConsumed.consume(newDebt);
        this.dailyLimit = this.dailyLimit.consume(advanceAmount);
        this.monthlyLimit = this.monthlyLimit.consume(advanceAmount);

        recalculateAvailableCredit();
        touch();
    }

    /**
     * Libera crédito (pago del resumen). Lo que supere a lo consumido queda como saldo a favor.
     *
     * @return el monto acreditado como saldo a favor (cero si el pago no superó a lo consumido)
     */
    public Money releaseCredit(Money paymentAmount) {
        if (paymentAmount == null || !paymentAmount.isPositive()) {
            throw new ValidationException("El monto del pago debe ser mayor a cero");
        }

        Money balanceBefore = this.creditBalance.getAmount();
        // El pago cancela primero lo usado en adelantos (la deuda de mayor tasa) y el resto va a compras
        this.cashAdvanceConsumed = this.cashAdvanceConsumed.release(min(paymentAmount, this.cashAdvanceConsumed.getAmount()));
        moveNetDebt(Money.zero(paymentAmount.getCurrencyCode()).subtract(paymentAmount));
        keepCashAdvanceWithinConsumed();
        recalculateAvailableCredit();
        touch();
        return this.creditBalance.getAmount().subtract(balanceBefore);
    }

    /**
     * Deshace el consumo de una transacción reversada o devuelta.
     * <p>
     * A diferencia de {@link #releaseCredit} (pago del resumen), devuelve también el cupo diario y mensual
     * cuando la transacción original es del día o del mes en curso. Si la tarjeta ya pagó ese consumo, lo que
     * supere a lo consumido queda como saldo a favor.
     *
     * @param amount       monto de la transacción reversada o devuelta
     * @param originalDate fecha de la transacción original (la que consumió el cupo)
     * @return el monto acreditado como saldo a favor (cero si alcanzó con bajar lo consumido)
     */
    public Money restoreConsumption(Money amount, LocalDate originalDate) {
        return restoreConsumption(amount, originalDate, false);
    }

    /**
     * Igual que {@link #restoreConsumption(Money, LocalDate)}; si la transacción original fue un adelanto,
     * también devuelve lo usado en adelantos (a lo sumo hasta cero: un pago pudo haberlo cancelado antes).
     */
    public Money restoreConsumption(Money amount, LocalDate originalDate, boolean cashAdvance) {
        if (amount == null || amount.isZero() || amount.isNegative()) {
            throw new ValidationException("El monto a restaurar debe ser mayor a cero");
        }
        if (originalDate == null) {
            throw new ValidationException("La fecha de la transacción original es obligatoria");
        }

        Money balanceBefore = this.creditBalance.getAmount();
        if (cashAdvance) {
            this.cashAdvanceConsumed = this.cashAdvanceConsumed.release(min(amount, this.cashAdvanceConsumed.getAmount()));
        }
        moveNetDebt(Money.zero(amount.getCurrencyCode()).subtract(amount));
        keepCashAdvanceWithinConsumed();
        this.dailyLimit = this.dailyLimit.restore(amount, originalDate);
        this.monthlyLimit = this.monthlyLimit.restore(amount, YearMonth.from(originalDate));

        recalculateAvailableCredit();
        touch();
        return this.creditBalance.getAmount().subtract(balanceBefore);
    }

    /**
     * Lo consumido, el cupo diario y el mensual en este momento. La diferencia entre dos fotos es lo que
     * movió una operación ({@link CreditOperation}).
     */
    public ConsumptionSnapshot consumptionSnapshot() {
        return new ConsumptionSnapshot(
                this.consumedCredit.getAmount(),
                this.dailyLimit.currentConsumed(),
                this.monthlyLimit.currentConsumed(),
                this.cashAdvanceConsumed.getAmount(),
                this.creditBalance.getAmount()
        );
    }

    /**
     * Deshace exactamente lo que movió una operación: la posición neta (consumido − saldo a favor) y, si siguen
     * siendo del mismo día o mes, el cupo diario y el mensual. Si la posición queda negativa, es saldo a favor:
     * por ejemplo, cancelar una compra que ya se pagó acredita su monto.
     * <p>
     * No valida límites, a propósito: deshacer una restauración significa que el reverso o la devolución nunca
     * ocurrieron, así que lo restaurado se vuelve a consumir aunque la tarjeta haya gastado ese disponible
     * mientras tanto. En ese caso queda excedida ({@link #getOverLimitAmount()}) y las compras siguientes se
     * rechazan por crédito insuficiente hasta que pague.
     */
    public void revertOperation(CreditOperation operation) {
        // Lo que la operación movió en la posición neta, con el signo contrario
        moveNetDebt(operation.getBalanceDelta().subtract(operation.getCreditDelta()));
        Money cashAdvance = this.cashAdvanceConsumed.getAmount().subtract(operation.getCashAdvanceDelta());
        this.cashAdvanceConsumed = ConsumedCredit.of(cashAdvance.isNegative() ? Money.zero(cashAdvance.getCurrencyCode()) : cashAdvance);
        keepCashAdvanceWithinConsumed();
        this.dailyLimit = this.dailyLimit.revert(operation.getDailyDelta(), operation.getAppliedOn());
        this.monthlyLimit = this.monthlyLimit.revert(operation.getMonthlyDelta(), YearMonth.from(operation.getAppliedOn()));

        recalculateAvailableCredit();
        touch();
    }

    /**
     * Cuánto supera lo consumido al límite de compras (cero si no está excedida). Solo puede pasar al deshacer
     * una restauración ({@link #revertOperation}); los consumos normales validan el disponible.
     */
    public Money getOverLimitAmount() {
        Money available = this.availableCredit.getAmount();
        return available.isNegative() ? Money.zero(available.getCurrencyCode()).subtract(available)
                : Money.zero(available.getCurrencyCode());
    }

    public String getCurrencyCode() {
        return this.purchaseLimit.getAmount().getCurrencyCode();
    }

    /**
     * Posición neta: lo consumido menos el saldo a favor (negativa si hay saldo a favor)
     */
    public Money getNetDebt() {
        return this.consumedCredit.getAmount().subtract(this.creditBalance.getAmount());
    }

    /**
     * Parte de un consumo que pasaría a deuda: lo que no cubre el saldo a favor
     */
    public Money debtPartOf(Money amount) {
        Money debt = amount.subtract(this.creditBalance.getAmount());
        return debt.isNegative() ? Money.zero(amount.getCurrencyCode()) : debt;
    }

    public record ConsumptionSnapshot(Money credit, Money daily, Money monthly, Money cashAdvance, Money balance) {
    }

    // ========================================
    // BUSINESS LOGIC - VALIDATIONS
    // ========================================

    /**
     * Valida si una compra puede ser realizada
     */
    public boolean canPurchase(Money purchaseAmount) {
        try {
            validatePurchaseAmount(purchaseAmount);

            return this.availableCredit.isSufficientFor(purchaseAmount)
                    && this.dailyLimit.allows(purchaseAmount)
                    && this.monthlyLimit.allows(purchaseAmount);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Valida si un adelanto puede ser realizado
     */
    public boolean canCashAdvance(Money advanceAmount) {
        try {
            validatePurchaseAmount(advanceAmount);

            return this.cashAdvanceLimit.isEnabled()
                    && !debtPartOf(advanceAmount).isGreaterThan(getCashAdvanceAvailable())
                    && this.availableCredit.isSufficientFor(advanceAmount)
                    && this.dailyLimit.allows(advanceAmount)
                    && this.monthlyLimit.allows(advanceAmount);
        } catch (Exception e) {
            return false;
        }
    }

    // ========================================
    // PRIVATE HELPERS
    // ========================================

    /**
     * Disponible para adelantos: el tope menos lo ya usado en adelantos (cero si el tope se bajó por debajo
     * de lo usado: los adelantos nuevos se rechazan hasta que un pago lo baje)
     */
    public Money getCashAdvanceAvailable() {
        Money available = this.cashAdvanceLimit.getAmount().subtract(this.cashAdvanceConsumed.getAmount());
        return available.isNegative() ? Money.zero(available.getCurrencyCode()) : available;
    }

    /** Invariante: lo usado en adelantos es parte de lo consumido */
    private void keepCashAdvanceWithinConsumed() {
        if (this.cashAdvanceConsumed.getAmount().isGreaterThan(this.consumedCredit.getAmount())) {
            this.cashAdvanceConsumed = ConsumedCredit.of(this.consumedCredit.getAmount());
        }
    }

    private static Money min(Money a, Money b) {
        return a.isGreaterThan(b) ? b : a;
    }

    /**
     * Suma (o resta, si es negativo) a la posición neta y la reparte: positiva es lo consumido, negativa es el
     * saldo a favor. Así el saldo cancela primero la deuda nueva y un pago o devolución de más se acredita.
     *
     * @return cuánto subió lo consumido (cero si bajó o no cambió)
     */
    private Money moveNetDebt(Money delta) {
        Money consumedBefore = this.consumedCredit.getAmount();
        Money net = getNetDebt().add(delta);
        String currency = net.getCurrencyCode();
        if (net.isNegative()) {
            this.consumedCredit = ConsumedCredit.zero(currency);
            this.creditBalance = CreditBalance.of(Money.zero(currency).subtract(net));
        } else {
            this.consumedCredit = ConsumedCredit.of(net);
            this.creditBalance = CreditBalance.zero(currency);
        }
        Money increase = this.consumedCredit.getAmount().subtract(consumedBefore);
        return increase.isNegative() ? Money.zero(currency) : increase;
    }

    private void recalculateAvailableCredit() {
        this.availableCredit = AvailableCredit.calculate(
                this.purchaseLimit.getAmount(),
                this.consumedCredit.getAmount(),
                this.creditBalance.getAmount()
        );
    }

    private void validateTemporalLimits(Money amount) {
        if (!this.dailyLimit.allows(amount)) {
            throw new BusinessRuleException(
                    String.format("Se excede el límite diario. Disponible hoy: %s",
                            this.dailyLimit.getAvailable())
            );
        }

        if (!this.monthlyLimit.allows(amount)) {
            throw new BusinessRuleException(
                    String.format("Se excede el límite mensual. Disponible este mes: %s",
                            this.monthlyLimit.getAvailable())
            );
        }
    }

    private static void validateAssignment(
            CardId cardId,
            Money purchaseLimitAmount,
            double cashAdvancePercentage,
            LimitPolicy policy
    ) {
        if (cardId == null) {
            throw new ValidationException("El CardId es obligatorio");
        }
        if (purchaseLimitAmount == null) {
            throw new ValidationException("El límite de compras es obligatorio");
        }
        if (cashAdvancePercentage < 0 || cashAdvancePercentage > 1) {
            throw new ValidationException("El porcentaje de adelantos debe estar entre 0 y 1");
        }
        Objects.requireNonNull(policy, "La política de límites es obligatoria");
    }

    private static void validateChangeAmount(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new ValidationException("El monto del cambio debe ser mayor a cero");
        }
    }

    private void validatePurchaseAmount(Money amount) {
        if (amount == null || amount.isZero()) {
            throw new ValidationException("El monto debe ser mayor a cero");
        }
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    // ========================================
    // EQUALS & HASHCODE
    // ========================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CreditLimit that = (CreditLimit) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format(
                "CreditLimit{id=%s, cardId=%s, purchaseLimit=%s, available=%s}",
                id, cardId, purchaseLimit.getAmount(), availableCredit.getAmount()
        );
    }


    // ========================================
    // HISTORY METHODS
    // ========================================

    /**
     * Obtiene el historial completo (inmutable)
     */
    public List<CreditLimitHistory> getHistory() {
        return Collections.unmodifiableList(this.history);
    }

    /**
     * Obtiene el historial filtrado por tipo de límite
     */
    public List<CreditLimitHistory> getHistoryByType(LimitType limitType) {
        return this.history.stream()
                .filter(h -> h.getLimitType() == limitType)
                .toList();
    }

    /**
     * Obtiene el último cambio registrado
     */
    public CreditLimitHistory getLastChange() {
        if (this.history.isEmpty()) {
            return null;
        }
        return this.history.get(this.history.size() - 1);
    }

    /**
     * Cuenta cuántos cambios se han hecho
     */
    public int getChangeCount() {
        return this.history.size();
    }

}
