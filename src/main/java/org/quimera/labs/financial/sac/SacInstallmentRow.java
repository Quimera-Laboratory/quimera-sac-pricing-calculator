package org.quimera.labs.financial.sac;

import org.quimera.labs.financial.engine.QDec128;

/**
 * Representa uma linha completa do cronograma de amortização, apropriação diária e
 * precificação SAC em Q128.
 */
public record SacInstallmentRow(
        int installmentNumber,
        int daysInPeriod,
        int cumulativeDays,
        DayCountBasis dayCountBasis,
        long openingBalanceCents,
        long monetaryCorrectionCents,
        long updatedBalanceCents,
        long periodCompoundFactor16,
        long amortizationQuotaCents,
        long interestQuotaCents,
        QDec128 firstDayAccrualQ128,
        long firstDayAccrualCents,
        QDec128 averageDailyAccrualQ128,
        long averageDailyAccrualCents,
        long totalInstallmentCents,
        long closingBalanceCents,
        long discountFactor16,
        QDec128 presentValueQ128,
        long presentValueCents
) {
}
