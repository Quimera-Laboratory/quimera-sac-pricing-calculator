package org.quimera.labs.financial.sac;

import org.quimera.labs.financial.engine.QDec128;

/**
 * Representa a apropriação contábil diária Pro-Rata Die (COSIF / Resolução CMN 4.966)
 * para um dia {@code dayInPeriod} dentro de uma parcela SAC na base 252, 360 ou 365.
 */
public record SacDailyAccrualRow(
        int installmentNumber,
        int dayInPeriod,
        int totalDaysInPeriod,
        DayCountBasis dayCountBasis,
        long openingBalanceCents,
        long accumulatedFactor16,
        QDec128 accruedInterestToDateQ128,
        long accruedInterestToDateCents,
        QDec128 dailyInterestDeltaQ128,
        long dailyInterestDeltaCents,
        long updatedCarryingAmountCents
) {
}
