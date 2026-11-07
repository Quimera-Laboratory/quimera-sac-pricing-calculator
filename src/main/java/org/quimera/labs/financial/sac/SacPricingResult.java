package org.quimera.labs.financial.sac;

import org.quimera.labs.financial.engine.QDec128;

import java.util.List;

/**
 * Resultado consolidado da Precificação, Cronograma SAC, Marcação a Mercado (MtM),
 * Duration e Invariante de Feixe (Apropriação por Saldo de Parcela) em Q128.
 */
public record SacPricingResult(
        SacContractSpec contractSpec,
        DayCountBasis valuationBasis,
        QDec128 valuationAnnualRate,
        List<SacInstallmentRow> schedule,
        long totalAmortizedCents,
        long totalMonetaryCorrectionCents,
        long totalInterestCents,
        long totalCashFlowCents,
        QDec128 totalPresentValueQ128,
        long totalPresentValueCents,
        long premiumOrDiscountCents,
        QDec128 macaulayDurationYearsQ128,
        QDec128 macaulayDurationDaysQ128,
        QDec128 modifiedDurationYearsQ128,
        QDec128 internalRateOfReturnAnnualQ128,
        boolean parcelSheafInvariantVerified
) {
}
