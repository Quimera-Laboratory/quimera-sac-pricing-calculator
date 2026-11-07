package org.quimera.labs.financial.sac;

import org.quimera.labs.financial.engine.QDec128;
import org.quimera.labs.financial.engine.QDec128ColumnarVector;
import org.quimera.labs.financial.engine.QuimeraCategoricalDecimal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Calculadora de Precificação, Apropriação Contábil (COSIF / Resolução CMN 4.966) e
 * Marcação a Mercado (MtM) de contratos SAC operando sobre o motor algébrico Q128
 * ({@link QuimeraCategoricalDecimal}, {@link QDec128}, {@link QDec128ColumnarVector})
 * em qualquer base de contagem de dias ({@link DayCountBasis#BUS_252},
 * {@link DayCountBasis#ACT_360} ou {@link DayCountBasis#ACT_365}).
 */
public final class SacPricingCalculator {

    private static final long BASE_16 = Q128RateEngine.BASE_16;

    /**
     * Precifica o contrato SAC ao par (taxa de desconto a mercado idêntica à taxa e base do contrato).
     */
    public SacPricingResult priceAtPar(SacContractSpec spec) {
        return price(spec, spec.getAnnualContractRate(), spec.getDayCountBasis());
    }

    /**
     * Gera o cronograma completo do contrato SAC na base contratual (252, 360 ou 365) e
     * precifica os fluxos de caixa na curva de mercado ({@code valuationAnnualRate} e {@code valuationBasis}).
     */
    public SacPricingResult price(
            SacContractSpec spec,
            QDec128 valuationAnnualRate,
            DayCountBasis valuationBasis) {

        int n = spec.getTotalInstallments();
        int grace = spec.getGraceInstallments();
        DayCountBasis contractBasis = spec.getDayCountBasis();
        RateCompoundingMode mode = spec.getCompoundingMode();
        SacRoundingMode rounding = spec.getRoundingMode();

        long contractRate16 = spec.getAnnualContractRateRaw16();
        long valuationRate16 = Q128RateEngine.toRaw16(valuationAnnualRate);

        QDec128 currentBalanceQ128 = spec.getPrincipalQ128();

        long totalAmortizedCents = 0L;
        long totalMonetaryCorrectionCents = 0L;
        long totalInterestCents = 0L;
        long totalCashFlowCents = 0L;

        QDec128 totalPvQ128 = Q128RateEngine.ZERO;
        QDec128 weightedDaysPvSumQ128 = Q128RateEngine.ZERO;

        List<SacInstallmentRow> rows = new ArrayList<>(n);
        int cumulativeDays = 0;

        for (int k = 1; k <= n; k++) {
            int idx = k - 1;
            int daysInPeriod = spec.getDaysForInstallment(idx);
            cumulativeDays += daysInPeriod;

            long openingCents = rounding.toCents(currentBalanceQ128);

            // 1. Atualização Monetária Pós-Fixada (se houver indexador IPCA/IGP-M/TR no período)
            long corrRate16 = spec.getMonetaryCorrectionRate16(idx);
            long monetaryCorrectionCents = 0L;
            if (corrRate16 != 0L) {
                QDec128 corrDeltaQ128 = Q128RateEngine.multiplyByFactor16(currentBalanceQ128, corrRate16);
                monetaryCorrectionCents = rounding.toCents(corrDeltaQ128);
            }
            long updatedBalanceCents = Math.addExact(openingCents, monetaryCorrectionCents);
            QDec128 updatedBalanceQ128 = Q128RateEngine.fromCents(updatedBalanceCents);

            // 2. Fator de Juros do Período na Base do Contrato (252, 360 ou 365) via Q128
            long periodFactor16 = Q128RateEngine.periodCompoundFactor16(
                    contractRate16, daysInPeriod, contractBasis, mode
            );
            long periodNetRate16 = Math.subtractExact(periodFactor16, BASE_16);

            // 3. Juros da Parcela k sobre o Saldo Devedor Atualizado e Valor da Apropriação Diária
            QDec128 interestQ128 = Q128RateEngine.multiplyByFactor16(updatedBalanceQ128, periodNetRate16);
            long interestCents = rounding.toCents(interestQ128);

            // Apropriação Diária: 1º Dia Pro-Rata Die (d = 1 na base 252, 360 ou 365) e Média Diária do Período
            long oneDayFactor16 = Q128RateEngine.periodCompoundFactor16(contractRate16, 1, contractBasis, mode);
            long oneDayNetRate16 = Math.subtractExact(oneDayFactor16, BASE_16);
            QDec128 firstDayAccrualQ128 = Q128RateEngine.multiplyByFactor16(updatedBalanceQ128, oneDayNetRate16);
            long firstDayAccrualCents = rounding.toCents(firstDayAccrualQ128);

            QDec128 avgDailyAccrualQ128 = daysInPeriod > 0
                    ? Q128RateEngine.divideByLong(interestQ128, daysInPeriod)
                    : Q128RateEngine.ZERO;
            long avgDailyAccrualCents = rounding.toCents(avgDailyAccrualQ128);

            // 4. Quota de Amortização SAC pelo Saldo Remanescente: A_k = SD* / (N - k + 1)
            long amortCents;
            if (k <= grace) {
                amortCents = 0L;
            } else {
                int remainingAmortInstallments = n - k + 1;
                if (remainingAmortInstallments == 1) {
                    // Última parcela absorve 100% do saldo residual para fechamento contábil exato (SD_N = 0,00)
                    amortCents = updatedBalanceCents;
                } else {
                    QDec128 quotaQ128 = Q128RateEngine.divideByLong(updatedBalanceQ128, remainingAmortInstallments);
                    amortCents = Math.min(updatedBalanceCents, rounding.toCents(quotaQ128));
                }
            }

            long totalInstallmentCents = Math.addExact(amortCents, interestCents);
            long closingCents = Math.subtractExact(updatedBalanceCents, amortCents);

            // 5. Fator de Desconto a Mercado na Base de Precificação (252, 360 ou 365) via Q128
            long discountFactor16 = Q128RateEngine.discountFactor16(
                    valuationRate16, cumulativeDays, valuationBasis, mode
            );
            QDec128 cashFlowQ128 = Q128RateEngine.fromCents(totalInstallmentCents);
            QDec128 pvInstallmentQ128 = Q128RateEngine.multiplyByFactor16(cashFlowQ128, discountFactor16);
            long pvInstallmentCents = rounding.toCents(pvInstallmentQ128);

            totalPvQ128 = Q128RateEngine.add(totalPvQ128, pvInstallmentQ128);
            QDec128 timeWeightedPv = Q128RateEngine.multiply(
                    pvInstallmentQ128, Q128RateEngine.fromLong(cumulativeDays)
            );
            weightedDaysPvSumQ128 = Q128RateEngine.add(weightedDaysPvSumQ128, timeWeightedPv);

            rows.add(new SacInstallmentRow(
                    k,
                    daysInPeriod,
                    cumulativeDays,
                    contractBasis,
                    openingCents,
                    monetaryCorrectionCents,
                    updatedBalanceCents,
                    periodFactor16,
                    amortCents,
                    interestCents,
                    firstDayAccrualQ128,
                    firstDayAccrualCents,
                    avgDailyAccrualQ128,
                    avgDailyAccrualCents,
                    totalInstallmentCents,
                    closingCents,
                    discountFactor16,
                    pvInstallmentQ128,
                    pvInstallmentCents
            ));

            totalAmortizedCents = Math.addExact(totalAmortizedCents, amortCents);
            totalMonetaryCorrectionCents = Math.addExact(totalMonetaryCorrectionCents, monetaryCorrectionCents);
            totalInterestCents = Math.addExact(totalInterestCents, interestCents);
            totalCashFlowCents = Math.addExact(totalCashFlowCents, totalInstallmentCents);

            currentBalanceQ128 = Q128RateEngine.fromCents(closingCents);
        }

        long totalPvCents = rounding.toCents(totalPvQ128);
        long premiumOrDiscountCents = Math.subtractExact(totalPvCents, spec.getPrincipalCents());

        // 6. Cálculo de Duration de Macaulay (em dias e em anos na base de valuation) e Duration Modificada em Q128
        QDec128 macaulayDaysQ128 = totalPvQ128.isZero()
                ? Q128RateEngine.ZERO
                : Q128RateEngine.divide(weightedDaysPvSumQ128, totalPvQ128);
        QDec128 macaulayYearsQ128 = Q128RateEngine.divideByLong(
                macaulayDaysQ128, valuationBasis.getDaysInYear()
        );
        QDec128 onePlusMarketRate = Q128RateEngine.add(Q128RateEngine.ONE, valuationAnnualRate.toInternal16());
        QDec128 modifiedDurationYearsQ128 = Q128RateEngine.divide(macaulayYearsQ128, onePlusMarketRate);

        // 7. Auditoria de Invariante de Feixe (Apropriação por Saldo de Parcela em Vetor Colunar Q128)
        boolean sheafVerified = verifyParcelBalanceSheafInvariant(spec, rows);

        // 8. Cálculo da Taxa Interna de Retorno (TIR / YTM) na base de valuation via Q128
        QDec128 irrQ128 = solveInternalRateOfReturn(rows, totalPvCents, valuationBasis, mode);

        return new SacPricingResult(
                spec,
                valuationBasis,
                valuationAnnualRate.toInternal16(),
                Collections.unmodifiableList(rows),
                totalAmortizedCents,
                totalMonetaryCorrectionCents,
                totalInterestCents,
                totalCashFlowCents,
                totalPvQ128,
                totalPvCents,
                premiumOrDiscountCents,
                macaulayYearsQ128,
                macaulayDaysQ128,
                modifiedDurationYearsQ128,
                irrQ128,
                sheafVerified
        );
    }

    /**
     * Gera a curva diária de apropriação contábil Pro-Rata Die (dia a dia t = 1..d_k)
     * para uma parcela específica do contrato SAC na base configurada (252, 360 ou 365),
     * garantindo fechamento exato da soma dos deltas diários com o juro total da parcela.
     */
    public List<SacDailyAccrualRow> computeDailyAccrualSchedule(
            SacContractSpec spec,
            int installmentNumber) {
        if (installmentNumber < 1 || installmentNumber > spec.getTotalInstallments()) {
            throw new IllegalArgumentException("Número de parcela inválido: " + installmentNumber);
        }

        SacPricingResult parResult = priceAtPar(spec);
        SacInstallmentRow targetRow = parResult.schedule().get(installmentNumber - 1);

        int totalDays = targetRow.daysInPeriod();
        DayCountBasis basis = spec.getDayCountBasis();
        RateCompoundingMode mode = spec.getCompoundingMode();
        SacRoundingMode rounding = spec.getRoundingMode();
        long annualRate16 = spec.getAnnualContractRateRaw16();

        long openingCents = targetRow.updatedBalanceCents();
        QDec128 openingQ128 = Q128RateEngine.fromCents(openingCents);

        List<SacDailyAccrualRow> dailyRows = new ArrayList<>(totalDays);
        long prevAccruedCents = 0L;
        QDec128 prevAccruedQ128 = Q128RateEngine.ZERO;

        for (int day = 1; day <= totalDays; day++) {
            long accFactor16 = (day == totalDays)
                    ? targetRow.periodCompoundFactor16()
                    : Q128RateEngine.periodCompoundFactor16(annualRate16, day, basis, mode);
            long netRateToDate16 = Math.subtractExact(accFactor16, BASE_16);

            QDec128 accruedToDateQ128 = Q128RateEngine.multiplyByFactor16(openingQ128, netRateToDate16);
            long accruedToDateCents = (day == totalDays)
                    ? targetRow.interestQuotaCents()
                    : rounding.toCents(accruedToDateQ128);

            QDec128 dailyDeltaQ128 = Q128RateEngine.subtract(accruedToDateQ128, prevAccruedQ128);
            long dailyDeltaCents = Math.subtractExact(accruedToDateCents, prevAccruedCents);
            long carryingAmountCents = Math.addExact(openingCents, accruedToDateCents);

            dailyRows.add(new SacDailyAccrualRow(
                    installmentNumber,
                    day,
                    totalDays,
                    basis,
                    openingCents,
                    accFactor16,
                    accruedToDateQ128,
                    accruedToDateCents,
                    dailyDeltaQ128,
                    dailyDeltaCents,
                    carryingAmountCents
            ));

            prevAccruedCents = accruedToDateCents;
            prevAccruedQ128 = accruedToDateQ128;
        }

        return Collections.unmodifiableList(dailyRows);
    }

    /**
     * Calcula o Valor Presente de Liquidação Antecipada (Redução Proporcional de Juros BACEN)
     * imediatamente após o pagamento da parcela {@code paidInstallments} (0 <= paidInstallments < N),
     * descontando todas as parcelas vincendas na base informada (252, 360 ou 365).
     */
    public QDec128 calculateEarlyPayoffPresentValue(
            SacContractSpec spec,
            int paidInstallments,
            QDec128 discountAnnualRate,
            DayCountBasis discountBasis) {
        if (paidInstallments < 0 || paidInstallments >= spec.getTotalInstallments()) {
            throw new IllegalArgumentException("paidInstallments deve estar entre 0 e N-1");
        }

        SacPricingResult parResult = priceAtPar(spec);
        List<SacInstallmentRow> schedule = parResult.schedule();

        int settlementDay = paidInstallments == 0
                ? 0
                : schedule.get(paidInstallments - 1).cumulativeDays();

        long discountRate16 = Q128RateEngine.toRaw16(discountAnnualRate);
        RateCompoundingMode mode = spec.getCompoundingMode();

        QDec128 payoffPvQ128 = Q128RateEngine.ZERO;
        for (int k = paidInstallments + 1; k <= spec.getTotalInstallments(); k++) {
            SacInstallmentRow row = schedule.get(k - 1);
            int remainingDaysToMaturity = row.cumulativeDays() - settlementDay;
            long df16 = Q128RateEngine.discountFactor16(
                    discountRate16, remainingDaysToMaturity, discountBasis, mode
            );
            QDec128 cfQ128 = Q128RateEngine.fromCents(row.totalInstallmentCents());
            QDec128 pvRow = Q128RateEngine.multiplyByFactor16(cfQ128, df16);
            payoffPvQ128 = Q128RateEngine.add(payoffPvQ128, pvRow);
        }

        return payoffPvQ128;
    }

    /**
     * Verifica a Invariante de Aditividade de Feixe (Apropriação por Saldo de Parcela Individual)
     * utilizando a estrutura colunar Zero-Heap {@link QDec128ColumnarVector}:
     *   Em todo período m = 1..N, a soma dos saldos de principal das parcelas vivas k in [m..N]
     *   no vetor colunar iguala exatamente o saldo devedor global do contrato, e a soma dos juros
     *   apropriados por parcela converge para o juro global da parcela m.
     */
    public boolean verifyParcelBalanceSheafInvariant(
            SacContractSpec spec,
            List<SacInstallmentRow> schedule) {
        int n = schedule.size();
        QDec128ColumnarVector parcelPrincipalsVec = new QDec128ColumnarVector(n, Q128RateEngine.SCALE_16);

        for (int i = 0; i < n; i++) {
            QDec128 quotaQ128 = Q128RateEngine.fromCents(schedule.get(i).amortizationQuotaCents());
            parcelPrincipalsVec.setQDec128(i, quotaQ128);
        }

        SacRoundingMode rounding = spec.getRoundingMode();

        for (int m = 0; m < n; m++) {
            SacInstallmentRow row = schedule.get(m);
            long netRate16 = Math.subtractExact(row.periodCompoundFactor16(), BASE_16);

            QDec128 sumOpenPrincipalQ128 = Q128RateEngine.ZERO;
            QDec128 sumParcelInterestQ128 = Q128RateEngine.ZERO;

            for (int k = m; k < n; k++) {
                QDec128 parcelPrin = parcelPrincipalsVec.getQDec128(k);
                sumOpenPrincipalQ128 = Q128RateEngine.add(sumOpenPrincipalQ128, parcelPrin);
                QDec128 parcelInt = Q128RateEngine.multiplyByFactor16(parcelPrin, netRate16);
                sumParcelInterestQ128 = Q128RateEngine.add(sumParcelInterestQ128, parcelInt);
            }

            // Verifica em contratos pré-fixados que a soma das quotas vivas == saldo atualizado
            if (spec.getMonetaryCorrectionRate16(m) == 0L) {
                long sumOpenCents = rounding.toCents(sumOpenPrincipalQ128);
                if (sumOpenCents != row.updatedBalanceCents()) {
                    return false;
                }
                long sumInterestCents = rounding.toCents(sumParcelInterestQ128);
                if (Math.abs(sumInterestCents - row.interestQuotaCents()) > 1L) {
                    return false;
                }
            }

            // Baixa a parcela m no vetor colunar SoA após o vencimento
            parcelPrincipalsVec.set(m, 0L, 0L);
        }

        return parcelPrincipalsVec.sumQDec128().isZero();
    }

    /**
     * Calcula a Taxa Interna de Retorno (TIR / YTM efetiva anual) na base escolhida (252, 360 ou 365)
     * por Bisseção Monotônica em Q128 (precisão de 10^-14 na taxa anual).
     */
    public QDec128 solveInternalRateOfReturn(
            List<SacInstallmentRow> schedule,
            long targetPresentValueCents,
            DayCountBasis basis,
            RateCompoundingMode mode) {
        if (schedule.isEmpty() || targetPresentValueCents <= 0L) {
            return Q128RateEngine.ZERO;
        }

        QDec128 targetPvQ128 = Q128RateEngine.fromCents(targetPresentValueCents);
        long lowRate16 = -5_000_000_000_000_000L; // -50% a.a.
        long highRate16 = 50_000_000_000_000_000L; // +500% a.a.

        for (int iter = 0; iter < 90; iter++) {
            long midRate16 = lowRate16 + ((highRate16 - lowRate16) / 2L);
            QDec128 npvQ128 = evaluatePresentValueQ128(schedule, midRate16, basis, mode);
            if (npvQ128.compare(targetPvQ128) > 0) {
                lowRate16 = midRate16;
            } else {
                highRate16 = midRate16;
            }
            if (Math.abs(highRate16 - lowRate16) <= 100L) {
                break;
            }
        }

        long solvedRate16 = lowRate16 + ((highRate16 - lowRate16) / 2L);
        return Q128RateEngine.fromRaw16(solvedRate16);
    }

    private QDec128 evaluatePresentValueQ128(
            List<SacInstallmentRow> schedule,
            long candidateAnnualRate16,
            DayCountBasis basis,
            RateCompoundingMode mode) {
        QDec128 sumPvQ128 = Q128RateEngine.ZERO;
        for (SacInstallmentRow row : schedule) {
            long df16 = Q128RateEngine.discountFactor16(
                    candidateAnnualRate16, row.cumulativeDays(), basis, mode
            );
            QDec128 cfQ128 = Q128RateEngine.fromCents(row.totalInstallmentCents());
            QDec128 pvRowQ128 = Q128RateEngine.multiplyByFactor16(cfQ128, df16);
            sumPvQ128 = Q128RateEngine.add(sumPvQ128, pvRowQ128);
        }
        return sumPvQ128;
    }

    /**
     * Executa o processamento em lote Zero-Heap de apropriação de saldo de parcelas sobre arrays
     * primitivos de um {@link QDec128ColumnarVector} (sem nenhuma alocação na Heap da JVM).
     */
    public long runZeroHeapBatchAppropriation(
            long[] qArr,
            long[] rArr,
            long[] periodFactors16,
            int count,
            int iterations) {
        long acc = 0L;
        for (int iter = 0; iter < iterations; iter++) {
            for (int i = 0; i < count; i++) {
                long q = qArr[i];
                long r = rArr[i];
                long f = periodFactors16[i];

                long pHi = Math.multiplyHigh(r, f);
                long pLo = r * f;
                long rScaled = QuimeraCategoricalDecimal.div128By64Unsigned(pHi, pLo, BASE_16);
                long qIntPart = q * (f / BASE_16);
                long qFracPart = QuimeraCategoricalDecimal.signedMonoidMul16Safe(q * BASE_16, f % BASE_16) / BASE_16;
                acc += (qIntPart + qFracPart + rScaled);
            }
        }
        return acc;
    }

    /**
     * Compara a precificação do mesmo contrato SAC nas três bases (BUS_252, ACT_360 e ACT_365).
     */
    public Map<DayCountBasis, SacPricingResult> compareAcrossAllBases(
            long principalCents,
            int totalInstallments,
            String annualRateDecimal) {
        Map<DayCountBasis, SacPricingResult> results = new EnumMap<>(DayCountBasis.class);
        for (DayCountBasis basis : DayCountBasis.values()) {
            SacContractSpec spec = SacContractSpec.builder()
                    .principalCents(principalCents)
                    .totalInstallments(totalInstallments)
                    .dayCountBasis(basis)
                    .annualContractRate(annualRateDecimal)
                    .build();
            results.put(basis, priceAtPar(spec));
        }
        return results;
    }
}
