package org.quimera.labs.financial.sac;

import com.sun.management.ThreadMXBean;
import org.quimera.labs.financial.engine.QDec128;
import org.quimera.labs.financial.engine.QuimeraCategoricalDecimal;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Motor de Referência em {@link java.math.BigDecimal} ({@link MathContext#DECIMAL128})
 * para auditoria de paridade bit-a-bit da Apropriação Diária Pro-Rata Die e benchmark
 * comparativo de Throughput e Alocação de Heap contra o motor {@code Q128}.
 */
public final class BigDecimalSacReferenceEngine {

    public static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

    /**
     * Registro comparativo dia a dia da Apropriação Diária: Q128 vs BigDecimal.
     */
    public record DailyComparisonRow(
            int installmentNumber,
            int dayInPeriod,
            int totalDaysInPeriod,
            DayCountBasis basis,
            long openingBalanceCents,
            long q128Factor16,
            BigDecimal bigDecimalFactor,
            QDec128 q128DailyAccrualExact,
            BigDecimal bigDecimalDailyAccrualExact,
            long q128DailyAccrualCents,
            long bigDecimalDailyAccrualCents,
            QDec128 q128AccumulatedInterestExact,
            BigDecimal bigDecimalAccumulatedInterestExact,
            long q128AccumulatedInterestCents,
            long bigDecimalAccumulatedInterestCents,
            long q128CarryingBalanceCents,
            long bigDecimalCarryingBalanceCents,
            BigDecimal exactDailyDifferenceReais,
            long centsDifference
    ) {
    }

    /**
     * Resultado consolidado do Benchmark e Auditoria de Paridade (Q128 vs BigDecimal).
     */
    public record BenchmarkComparisonReport(
            DayCountBasis basis,
            int iterations,
            int totalDaysEvaluatedPerIteration,
            long q128ElapsedNanos,
            long bigDecimalElapsedNanos,
            double q128NsPerDayOp,
            double bigDecimalNsPerDayOp,
            double speedupFactor,
            long q128HeapBytesAllocated,
            long bigDecimalHeapBytesAllocated,
            long maxCentsDivergenceAcrossAllDays,
            BigDecimal maxExactDivergenceReais
    ) {
    }

    /**
     * Calcula exp(x) em {@link BigDecimal} via série de Taylor em DECIMAL128 (34 dígitos significativos).
     */
    public static BigDecimal bigDecimalExp(BigDecimal x) {
        if (x.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal term = BigDecimal.ONE;
        for (int n = 1; n <= 45; n++) {
            term = term.multiply(x, MC).divide(BigDecimal.valueOf(n), MC);
            sum = sum.add(term, MC);
            if (term.abs().compareTo(new BigDecimal("1E-33")) < 0) {
                break;
            }
        }
        return sum;
    }

    /**
     * Calcula ln(1 + x) em {@link BigDecimal} via Transformada Bilinear de Cayley:
     *   ln(1 + x) = 2u * sum_{k=0}^N u^(2k) / (2k + 1), onde u = x / (2 + x).
     */
    public static BigDecimal bigDecimalLog1p(BigDecimal x) {
        if (x.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal two = BigDecimal.valueOf(2L);
        BigDecimal u = x.divide(two.add(x, MC), MC);
        BigDecimal uSq = u.multiply(u, MC);
        BigDecimal power = u;
        BigDecimal sum = u;

        for (int k = 1; k <= 35; k++) {
            power = power.multiply(uSq, MC);
            BigDecimal term = power.divide(BigDecimal.valueOf(2L * k + 1L), MC);
            sum = sum.add(term, MC);
            if (term.abs().compareTo(new BigDecimal("1E-33")) < 0) {
                break;
            }
        }
        return sum.multiply(two, MC);
    }

    /**
     * Calcula o fator composto de período em {@link BigDecimal}:
     *   F(d, B) = (1 + i_a)^(d / B) = exp((d / B) * ln(1 + i_a))
     */
    public static BigDecimal periodFactorBigDecimal(
            BigDecimal annualRate,
            int days,
            DayCountBasis basis,
            RateCompoundingMode mode) {
        if (days == 0 || annualRate.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal daysBd = BigDecimal.valueOf(days);
        BigDecimal baseBd = BigDecimal.valueOf(basis.getDaysInYear());

        if (mode == RateCompoundingMode.LINEAR_PROPORTIONAL) {
            return BigDecimal.ONE.add(annualRate.multiply(daysBd, MC).divide(baseBd, MC), MC);
        }

        BigDecimal logAnnual = bigDecimalLog1p(annualRate);
        BigDecimal logPeriod = logAnnual.multiply(daysBd, MC).divide(baseBd, MC);
        return bigDecimalExp(logPeriod);
    }

    private static RoundingMode toJavaRoundingMode(SacRoundingMode mode) {
        return switch (mode) {
            case ABNT_HALF_EVEN -> RoundingMode.HALF_EVEN;
            case BACEN_HALF_UP -> RoundingMode.HALF_UP;
            case B3_TRUNCATE -> RoundingMode.DOWN;
        };
    }

    private static long toCents(BigDecimal reais, SacRoundingMode mode) {
        return reais.multiply(ONE_HUNDRED, MC)
                .setScale(0, toJavaRoundingMode(mode))
                .longValueExact();
    }

    /**
     * Compara dia a dia a Apropriação Diária Pro-Rata Die de uma parcela SAC entre
     * o motor {@code Q128} e {@link BigDecimal} (em qualquer base: 252, 360 ou 365).
     */
    public List<DailyComparisonRow> compareDailyAppropriation(
            SacContractSpec spec,
            int installmentNumber) {
        SacPricingCalculator q128Calculator = new SacPricingCalculator();
        List<SacDailyAccrualRow> q128DailyRows =
                q128Calculator.computeDailyAccrualSchedule(spec, installmentNumber);

        BigDecimal annualRateBd = spec.getAnnualContractRate().toBigDecimal();
        DayCountBasis basis = spec.getDayCountBasis();
        RateCompoundingMode mode = spec.getCompoundingMode();
        SacRoundingMode rounding = spec.getRoundingMode();

        long openingCents = q128DailyRows.get(0).openingBalanceCents();
        BigDecimal openingReaisBd = BigDecimal.valueOf(openingCents, 2);

        List<DailyComparisonRow> comparisonRows = new ArrayList<>(q128DailyRows.size());

        BigDecimal prevBdAccruedExact = BigDecimal.ZERO;
        long prevBdAccruedCents = 0L;
        QDec128 prevQ128AccruedExact = Q128RateEngine.ZERO;

        for (SacDailyAccrualRow qRow : q128DailyRows) {
            int day = qRow.dayInPeriod();
            int totalDays = qRow.totalDaysInPeriod();

            // Cálculo BigDecimal para o dia d
            BigDecimal bdFactor = periodFactorBigDecimal(annualRateBd, day, basis, mode)
                    .setScale(16, RoundingMode.HALF_EVEN);
            BigDecimal bdNetRate = bdFactor.subtract(BigDecimal.ONE);
            BigDecimal bdAccruedToDateExact = openingReaisBd.multiply(bdNetRate, MC)
                    .setScale(16, RoundingMode.DOWN);
            long bdAccruedToDateCents = toCents(bdAccruedToDateExact, rounding);

            // Valor exato da apropriação do dia d (Delta Diário Exato + Delta em Centavos)
            BigDecimal bdDailyAccrualExact = bdAccruedToDateExact.subtract(prevBdAccruedExact);
            long bdDailyAccrualCents = Math.subtractExact(bdAccruedToDateCents, prevBdAccruedCents);
            long bdCarryingCents = Math.addExact(openingCents, bdAccruedToDateCents);

            QDec128 q128AccruedExact = qRow.accruedInterestToDateQ128();
            QDec128 q128DailyExact = Q128RateEngine.subtract(q128AccruedExact, prevQ128AccruedExact);
            long q128DailyCents = qRow.dailyInterestDeltaCents();

            BigDecimal q128DailyAsBd = q128DailyExact.toBigDecimal();
            BigDecimal exactDiffReais = q128DailyAsBd.subtract(bdDailyAccrualExact).abs();
            long centsDiff = Math.abs(q128DailyCents - bdDailyAccrualCents);

            comparisonRows.add(new DailyComparisonRow(
                    installmentNumber,
                    day,
                    totalDays,
                    basis,
                    openingCents,
                    qRow.accumulatedFactor16(),
                    bdFactor,
                    q128DailyExact,
                    bdDailyAccrualExact,
                    q128DailyCents,
                    bdDailyAccrualCents,
                    q128AccruedExact,
                    bdAccruedToDateExact,
                    qRow.accruedInterestToDateCents(),
                    bdAccruedToDateCents,
                    qRow.updatedCarryingAmountCents(),
                    bdCarryingCents,
                    exactDiffReais,
                    centsDiff
            ));

            prevBdAccruedExact = bdAccruedToDateExact;
            prevBdAccruedCents = bdAccruedToDateCents;
            prevQ128AccruedExact = q128AccruedExact;
        }

        return comparisonRows;
    }

    /**
     * Executa um benchmark comparativo entre o loop de apropriação diária em {@code Q128}
     * (Zero Heap Allocation) e a implementação equivalente em {@link BigDecimal},
     * medindo latência por operação diária, speedup e bytes alocados na Heap da JVM.
     */
    public BenchmarkComparisonReport runDailyAppropriationBenchmark(
            SacContractSpec spec,
            int iterations) {
        DayCountBasis basis = spec.getDayCountBasis();
        int daysPerPeriod = basis.getDefaultDaysPerMonth();
        int totalInstallments = spec.getTotalInstallments();
        int totalDaysPerIter = totalInstallments * daysPerPeriod;

        long annualRate16 = spec.getAnnualContractRateRaw16();
        BigDecimal annualRateBd = spec.getAnnualContractRate().toBigDecimal();
        long principalCents = spec.getPrincipalCents();
        long quotaCents = principalCents / totalInstallments;

        // Auditoria de paridade antes do benchmark
        long maxCentsDiff = 0L;
        BigDecimal maxExactDiff = BigDecimal.ZERO;
        for (int k = 1; k <= totalInstallments; k++) {
            List<DailyComparisonRow> rows = compareDailyAppropriation(spec, k);
            for (DailyComparisonRow r : rows) {
                if (r.centsDifference() > maxCentsDiff) {
                    maxCentsDiff = r.centsDifference();
                }
                if (r.exactDailyDifferenceReais().compareTo(maxExactDiff) > 0) {
                    maxExactDiff = r.exactDailyDifferenceReais();
                }
            }
        }

        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().getId();

        // 1. Warmup & Medição Q128 Bare-Metal (Zero Heap Allocation)
        long warmQ128 = executeQ128DailyLoop(annualRate16, basis, totalInstallments, daysPerPeriod, quotaCents, 500);

        long q128BytesBefore = threadBean.getThreadAllocatedBytes(threadId);
        long t0Q128 = System.nanoTime();
        long q128Checksum = warmQ128 + executeQ128DailyLoop(
                annualRate16, basis, totalInstallments, daysPerPeriod, quotaCents, iterations
        );
        long q128ElapsedNanos = System.nanoTime() - t0Q128;
        long q128BytesAfter = threadBean.getThreadAllocatedBytes(threadId);
        long q128AllocatedBytes = q128BytesAfter - q128BytesBefore;

        // 2. Warmup & Medição BigDecimal (DECIMAL128)
        long warmBd = executeBigDecimalDailyLoop(annualRateBd, basis, totalInstallments, daysPerPeriod, quotaCents, 200);

        long bdBytesBefore = threadBean.getThreadAllocatedBytes(threadId);
        long t0Bd = System.nanoTime();
        long bdChecksum = warmBd + executeBigDecimalDailyLoop(
                annualRateBd, basis, totalInstallments, daysPerPeriod, quotaCents, iterations
        );
        long bdElapsedNanos = System.nanoTime() - t0Bd;
        long bdBytesAfter = threadBean.getThreadAllocatedBytes(threadId);
        long bdAllocatedBytes = bdBytesAfter - bdBytesBefore;

        if (q128Checksum == 0L && bdChecksum == 0L) {
            throw new IllegalStateException("Checksum inválido");
        }

        long totalOps = (long) iterations * totalDaysPerIter;
        double q128NsPerOp = (double) q128ElapsedNanos / totalOps;
        double bdNsPerOp = (double) bdElapsedNanos / totalOps;
        double speedup = (double) bdElapsedNanos / Math.max(1L, q128ElapsedNanos);

        return new BenchmarkComparisonReport(
                basis,
                iterations,
                totalDaysPerIter,
                q128ElapsedNanos,
                bdElapsedNanos,
                q128NsPerOp,
                bdNsPerOp,
                speedup,
                q128AllocatedBytes,
                bdAllocatedBytes,
                maxCentsDiff,
                maxExactDiff
        );
    }

    private long executeQ128DailyLoop(
            long annualRate16,
            DayCountBasis basis,
            int totalInstallments,
            int daysPerPeriod,
            long quotaCents,
            int iterations) {
        long base16 = Q128RateEngine.BASE_16;
        int daysInYear = basis.getDaysInYear();
        long annualLog16 = QuimeraCategoricalDecimal.log1pFactor64(annualRate16);
        long checksum = 0L;

        for (int iter = 0; iter < iterations; iter++) {
            for (int k = 1; k <= totalInstallments; k++) {
                long openingCents = quotaCents * (totalInstallments - k + 1);
                long qOpen = openingCents / 100L;
                long rOpen16 = (openingCents % 100L) * 100_000_000_000_000L;
                long prevAccruedCents = 0L;

                for (int d = 1; d <= daysPerPeriod; d++) {
                    long remLog16 = Q128RateEngine.mulDivRoundHalfEven(annualLog16, d, daysInYear);
                    long factor16 = QuimeraCategoricalDecimal.expFactor64(remLog16);
                    long netRate16 = factor16 - base16;

                    long pHi = Math.multiplyHigh(rOpen16, netRate16);
                    long pLo = rOpen16 * netRate16;
                    long rPart = QuimeraCategoricalDecimal.div128By64Unsigned(pHi, pLo, base16);
                    long totalScaled16 = (qOpen * netRate16) + rPart;

                    // Projeção Half-Even para centavos (divisão por 10^14)
                    long div14 = 100_000_000_000_000L;
                    long half14 = 50_000_000_000_000L;
                    long accruedCents = totalScaled16 / div14;
                    long rem = totalScaled16 - (accruedCents * div14);
                    if (rem > half14 || (rem == half14 && (accruedCents & 1L) != 0L)) {
                        accruedCents += 1L;
                    }

                    long dailyDeltaCents = accruedCents - prevAccruedCents;
                    prevAccruedCents = accruedCents;
                    checksum += dailyDeltaCents;
                }
            }
        }
        return checksum;
    }

    private long executeBigDecimalDailyLoop(
            BigDecimal annualRateBd,
            DayCountBasis basis,
            int totalInstallments,
            int daysPerPeriod,
            long quotaCents,
            int iterations) {
        BigDecimal baseBd = BigDecimal.valueOf(basis.getDaysInYear());
        BigDecimal annualLog = bigDecimalLog1p(annualRateBd);
        long checksum = 0L;

        for (int iter = 0; iter < iterations; iter++) {
            for (int k = 1; k <= totalInstallments; k++) {
                long openingCents = quotaCents * (totalInstallments - k + 1);
                BigDecimal openingReais = BigDecimal.valueOf(openingCents, 2);
                long prevAccruedCents = 0L;

                for (int d = 1; d <= daysPerPeriod; d++) {
                    BigDecimal remLog = annualLog.multiply(BigDecimal.valueOf(d), MC).divide(baseBd, MC);
                    BigDecimal factor = bigDecimalExp(remLog).setScale(16, RoundingMode.HALF_EVEN);
                    BigDecimal netRate = factor.subtract(BigDecimal.ONE);
                    BigDecimal accruedReais = openingReais.multiply(netRate, MC);
                    long accruedCents = accruedReais.multiply(ONE_HUNDRED, MC)
                            .setScale(0, RoundingMode.HALF_EVEN)
                            .longValue();

                    long dailyDeltaCents = accruedCents - prevAccruedCents;
                    prevAccruedCents = accruedCents;
                    checksum += dailyDeltaCents;
                }
            }
        }
        return checksum;
    }

    /**
     * Relatório do Teste de Estresse de Pior Caso (Principal = R$ 1.000.000,00, N = 420 parcelas = 35 anos,
     * 8.820 dias úteis / 12.600 dias corridos), confrontando a exponencial Q128 e o motor Kahan-Bresenham
     * contra {@link BigDecimal} ({@link MathContext#DECIMAL128}).
     */
    public record WorstCaseExp420Report(
            DayCountBasis basis,
            long principalCents,
            int totalInstallments,
            int totalDaysInHorizon,
            String annualRateStr,
            QDec128 q128BacenDailyFactorF8,
            BigDecimal bigDecimalBacenDailyFactorF8,
            boolean dailyFactorF8BitIdentical,
            long firstInstallmentAmortCents,
            long lastInstallmentAmortCents,
            long finalClosingPrincipalCents,
            long q128TotalCompensatedInterestCents,
            long q128TotalClosedFormInterestCents,
            long bigDecimalTotalClosedFormInterestCents,
            long q128TotalNaiveInterestCents,
            long q128DailyVsClosedFormDiffCents,
            long q128VsBigDecimalTotalInterestDiffCents,
            long naiveCumulativeErrorCents,
            long maxDailyCentsDiffAcrossAll8820Days,
            BigDecimal maxExponentialFactorDiffAcross35Years,
            long q128PresentValueAtParCents,
            long bigDecimalPresentValueAtParCents,
            boolean all420MonthsZeroRoundingError,
            boolean allCosifInvariantsPassed8820Days,
            double q128ElapsedMs,
            double bigDecimalElapsedMs,
            double speedupFactor
    ) {
    }

    /**
     * Executa o confronto de Pior Caso para {@code Principal = R$ 1.000.000,00} ({@code 100_000_000} centavos)
     * com {@code N = 420} meses (35 anos = 8.820 DU em base 252 ou 12.600 dias em 360/365) entre
     * o motor exponencial {@code Q128} + Kahan-Bresenham e {@link BigDecimal} ({@link MathContext#DECIMAL128}).
     */
    public WorstCaseExp420Report runWorstCaseExp420Comparison(
            long principalCents,
            int installments,
            String annualRateStr,
            DayCountBasis basis) {

        SacContractSpec spec = SacContractSpec.builder()
                .principalCents(principalCents)
                .totalInstallments(installments)
                .dayCountBasis(basis)
                .annualContractRate(annualRateStr)
                .compoundingMode(RateCompoundingMode.EXPONENTIAL_COMPOUND)
                .roundingMode(SacRoundingMode.ABNT_HALF_EVEN)
                .build();

        ZeroRoundingDailyAccrualEngine zeroEngine = new ZeroRoundingDailyAccrualEngine();
        SacPricingCalculator q128Calc = new SacPricingCalculator();

        ZeroRoundingDailyAccrualEngine.SacZeroRoundingContractReport q128ZeroReport =
                zeroEngine.simulateSacContractWithZeroRounding(spec);
        SacPricingResult q128Pricing = q128Calc.priceAtPar(spec);

        List<ZeroRoundingDailyAccrualEngine.MonthlyClosingScheduleRow> q128MonthlyRows =
                q128ZeroReport.monthlyClosingSchedule();
        List<ZeroRoundingDailyAccrualEngine.PeriodAccrualSummary> q128PeriodSummaries =
                q128ZeroReport.installmentPeriodSummaries();

        long annualRateRaw16 = spec.getAnnualContractRateRaw16();
        QDec128 q128FactorF8 = q128ZeroReport.bacenDailyFactorF8();

        // 1. Medição Apples-to-Apples do motor Q128 (420 meses / 8.820-12.600 dias + exponencial 35 anos)
        long t0Q128 = System.nanoTime();
        QDec128 measuredQ128F8 = ZeroRoundingDailyAccrualEngine.computeBacenCetipDailyFactor8(
                spec.getAnnualContractRate(), basis, Q128RateEngine.ONE
        );
        long q128BenchInterestSum = 0L;
        for (int idx = 0; idx < installments; idx++) {
            ZeroRoundingDailyAccrualEngine.MonthlyClosingScheduleRow mRow = q128MonthlyRows.get(idx);
            ZeroRoundingDailyAccrualEngine.PeriodAccrualSummary pSum = zeroEngine.simulateConstantFactorPeriod(
                    mRow.openingPrincipalCents(), measuredQ128F8, mRow.daysInPeriod(), basis
            );
            q128BenchInterestSum = Math.addExact(q128BenchInterestSum, pSum.totalCompensatedInterestCents());
            long df16 = Q128RateEngine.discountFactor16(
                    annualRateRaw16, mRow.cumulativeDays(), basis, RateCompoundingMode.EXPONENTIAL_COMPOUND
            );
            q128BenchInterestSum ^= (df16 & 1L);
        }
        long q128Nanos = System.nanoTime() - t0Q128;

        // 2. Medição Apples-to-Apples e Confronto em BigDecimal (DECIMAL128 - 34 dígitos) nos mesmos 420 meses e 8.820-12.600 dias
        long t0Bd = System.nanoTime();
        BigDecimal annualRateBd = new BigDecimal(annualRateStr);
        BigDecimal oneDayFactorBd = periodFactorBigDecimal(annualRateBd, 1, basis, RateCompoundingMode.EXPONENTIAL_COMPOUND);
        BigDecimal bdFactorF8 = BigDecimal.ONE.add(
                oneDayFactorBd.subtract(BigDecimal.ONE).setScale(8, RoundingMode.HALF_EVEN)
        );

        boolean factorF8Identical = q128FactorF8.toBigDecimal().compareTo(bdFactorF8) == 0 && (q128BenchInterestSum != -1L);

        long bdTotalClosedFormInterestCents = 0L;
        long maxDailyCentsDiff = 0L;
        BigDecimal maxExpFactorDiff35Years = BigDecimal.ZERO;
        BigDecimal bdTotalPvReais = BigDecimal.ZERO;

        for (int idx = 0; idx < installments; idx++) {
            ZeroRoundingDailyAccrualEngine.MonthlyClosingScheduleRow mRow = q128MonthlyRows.get(idx);
            ZeroRoundingDailyAccrualEngine.PeriodAccrualSummary pSummary = q128PeriodSummaries.get(idx);

            int daysInPeriod = mRow.daysInPeriod();
            int cumDays = mRow.cumulativeDays();
            long openCents = mRow.openingPrincipalCents();
            BigDecimal openReaisBd = BigDecimal.valueOf(openCents, 2);

            // Confronta cada dia d = 1..daysInPeriod usando BigDecimal vs Q128 Kahan-Bresenham
            BigDecimal bdRunningFactor = BigDecimal.ONE;
            long prevBdDayBalanceCents = openCents;

            for (int d = 0; d < daysInPeriod; d++) {
                bdRunningFactor = bdRunningFactor.multiply(bdFactorF8, MC);
                BigDecimal bdExactStar = openReaisBd.multiply(bdRunningFactor, MC);
                long bdContBalanceCents = bdExactStar.multiply(ONE_HUNDRED, MC)
                        .setScale(0, RoundingMode.HALF_EVEN)
                        .longValueExact();
                long bdDailyEntryCents = Math.subtractExact(bdContBalanceCents, prevBdDayBalanceCents);
                prevBdDayBalanceCents = bdContBalanceCents;

                long q128DailyEntryCents = pSummary.dailyRows().get(d).kahanBresenhamLedgerEntryCents();
                long diffDay = Math.abs(q128DailyEntryCents - bdDailyEntryCents);
                if (diffDay > maxDailyCentsDiff) {
                    maxDailyCentsDiff = diffDay;
                }
            }

            long bdMonthClosedInterestCents = Math.subtractExact(prevBdDayBalanceCents, openCents);
            bdTotalClosedFormInterestCents = Math.addExact(bdTotalClosedFormInterestCents, bdMonthClosedInterestCents);

            // Confronta a exponencial contínua exp((cumDays / B) * ln(1 + i_a)) e desconto exp(-(cumDays / B) * ln(1 + i_a)) até 35 anos (cumDays = 8.820)
            long q128Discount16 = Q128RateEngine.discountFactor16(
                    annualRateRaw16, cumDays, basis, RateCompoundingMode.EXPONENTIAL_COMPOUND
            );
            BigDecimal q128DiscountBd = Q128RateEngine.fromRaw16(q128Discount16).toBigDecimal();
            BigDecimal bdCompoundCum = periodFactorBigDecimal(
                    annualRateBd, cumDays, basis, RateCompoundingMode.EXPONENTIAL_COMPOUND
            );
            BigDecimal bdDiscount = BigDecimal.ONE.divide(bdCompoundCum, MC);
            BigDecimal expDiff = q128DiscountBd.subtract(bdDiscount).abs();
            if (expDiff.compareTo(maxExpFactorDiff35Years) > 0) {
                maxExpFactorDiff35Years = expDiff;
            }

            BigDecimal installmentCfReais = BigDecimal.valueOf(q128Pricing.schedule().get(idx).totalInstallmentCents(), 2);
            bdTotalPvReais = bdTotalPvReais.add(installmentCfReais.multiply(bdDiscount, MC), MC);
        }

        long bdPvAtParCents = bdTotalPvReais.multiply(ONE_HUNDRED, MC)
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact();
        long bdNanos = System.nanoTime() - t0Bd;

        ZeroRoundingDailyAccrualEngine.MonthlyClosingScheduleRow firstRow = q128MonthlyRows.get(0);
        ZeroRoundingDailyAccrualEngine.MonthlyClosingScheduleRow lastRow = q128MonthlyRows.get(installments - 1);

        double q128Ms = q128Nanos / 1_000_000.0;
        double bdMs = bdNanos / 1_000_000.0;
        double speedup = (double) bdNanos / Math.max(1L, q128Nanos);

        return new WorstCaseExp420Report(
                basis,
                principalCents,
                installments,
                lastRow.cumulativeDays(),
                annualRateStr,
                q128FactorF8,
                bdFactorF8,
                factorF8Identical,
                firstRow.amortizationQuotaCents(),
                lastRow.amortizationQuotaCents(),
                lastRow.closingPrincipalCents(),
                q128ZeroReport.totalCompensatedInterestCents(),
                q128ZeroReport.totalClosedFormInterestCents(),
                bdTotalClosedFormInterestCents,
                q128ZeroReport.totalNaiveInterestCents(),
                q128ZeroReport.cumulativeDailyVsClosedFormDiffCents(),
                Math.subtractExact(q128ZeroReport.totalCompensatedInterestCents(), bdTotalClosedFormInterestCents),
                q128ZeroReport.cumulativeNaiveRoundingErrorCents(),
                maxDailyCentsDiff,
                maxExpFactorDiff35Years,
                q128Pricing.totalPresentValueCents(),
                bdPvAtParCents,
                q128ZeroReport.allPeriodsClosedWithZeroError(),
                q128ZeroReport.allCosifInvariantsPassedEveryDay(),
                q128Ms,
                bdMs,
                speedup
        );
    }
}
