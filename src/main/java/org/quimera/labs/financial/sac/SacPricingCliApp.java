package org.quimera.labs.financial.sac;

import org.quimera.labs.financial.engine.QDec128;
import org.quimera.labs.financial.sac.BigDecimalSacReferenceEngine.BenchmarkComparisonReport;
import org.quimera.labs.financial.sac.BigDecimalSacReferenceEngine.DailyComparisonRow;

import java.util.List;
import java.util.Map;

/**
 * Aplicação CLI demonstrativa da Calculadora de Precificação SAC com Q128
 * operando nas bases 252 (Dias Úteis), 360 (Comercial/Actual-360) e 365 (Actual-365),
 * incluindo o Valor da Apropriação Diária e o Comparativo Completo (Paridade + Performance + Heap)
 * contra {@link java.math.BigDecimal}.
 */
public final class SacPricingCliApp {

    public static void main(String[] args) {
        SacPricingCalculator calculator = new SacPricingCalculator();
        BigDecimalSacReferenceEngine bdEngine = new BigDecimalSacReferenceEngine();

        long principalCents = 120_000_000L; // R$ 1.200.000,00
        int installments = 12;
        String annualRate = "0.1325"; // 13,25% a.a.

        System.out.println("========================================================================================================================");
        System.out.println(" QUIMERA SAC PRICING & DAILY APPROPRIATION CALCULATOR (JAVA 17 + Q128 vs BigDecimal)");
        System.out.println(" Principal: " + Q128RateEngine.formatCentsAsBrl(principalCents)
                + " | Parcelas: " + installments + " | Taxa Contratual: 13,25% a.a.");
        System.out.println("========================================================================================================================\n");

        Map<DayCountBasis, SacPricingResult> comparison =
                calculator.compareAcrossAllBases(principalCents, installments, annualRate);

        System.out.println("1. COMPARATIVO CONSOLIDADO ENTRE BASES (252 DU vs ACT/360 vs ACT/365) COM APROPRIAÇÃO DIÁRIA:");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-32s | %-16s | %-16s | %-16s | %-16s | %-10s%n",
                "Base de Contagem", "Aprop. Dia 1(P1)", "Total Juros", "Total Prestacoes", "Valor Presente", "Duration(a)");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");

        for (DayCountBasis basis : DayCountBasis.values()) {
            SacPricingResult res = comparison.get(basis);
            SacInstallmentRow firstRow = res.schedule().get(0);
            System.out.printf("%-32s | %-16s | %-16s | %-16s | %-16s | %.6f%n",
                    basis.getDescription(),
                    Q128RateEngine.formatCentsAsBrl(firstRow.firstDayAccrualCents()),
                    Q128RateEngine.formatCentsAsBrl(res.totalInterestCents()),
                    Q128RateEngine.formatCentsAsBrl(res.totalCashFlowCents()),
                    Q128RateEngine.formatCentsAsBrl(res.totalPresentValueCents()),
                    res.macaulayDurationYearsQ128().toDouble());
        }
        System.out.println("------------------------------------------------------------------------------------------------------------------------\n");

        // Cronograma SAC com Valor da Apropriação Diária por Parcela (1º Dia e Média/Dia) e Marcação a Mercado
        SacContractSpec spec252 = SacContractSpec.builder()
                .principalCents(principalCents)
                .totalInstallments(installments)
                .dayCountBasis(DayCountBasis.BUS_252)
                .annualContractRate(annualRate)
                .build();

        QDec128 marketRate = Q128RateEngine.fromString("0.1150");
        SacPricingResult mtmResult = calculator.price(spec252, marketRate, DayCountBasis.BUS_252);

        System.out.println("2. CRONOGRAMA SAC (BASE 252 DU) COM VALOR DA APROPRIAÇÃO DIÁRIA E MARCAÇÃO A MERCADO (11,50% a.a.):");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-4s | %-6s | %-16s | %-14s | %-14s | %-14s | %-14s | %-15s | %-15s%n",
                "Parc", "DU Ac.", "Saldo Abertura", "Amortizacao", "Aprop. Dia 1", "Aprop. Media/d", "Juros Total", "Prestacao", "VP (MtM 11,5%)");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");

        for (SacInstallmentRow row : mtmResult.schedule()) {
            System.out.printf("%4d | %6d | %-16s | %-14s | %-14s | %-14s | %-14s | %-15s | %-15s%n",
                    row.installmentNumber(),
                    row.cumulativeDays(),
                    Q128RateEngine.formatCentsAsBrl(row.openingBalanceCents()),
                    Q128RateEngine.formatCentsAsBrl(row.amortizationQuotaCents()),
                    Q128RateEngine.formatCentsAsBrl(row.firstDayAccrualCents()),
                    Q128RateEngine.formatCentsAsBrl(row.averageDailyAccrualCents()),
                    Q128RateEngine.formatCentsAsBrl(row.interestQuotaCents()),
                    Q128RateEngine.formatCentsAsBrl(row.totalInstallmentCents()),
                    Q128RateEngine.formatCentsAsBrl(row.presentValueCents()));
        }
        System.out.println("------------------------------------------------------------------------------------------------------------------------\n");

        // Tabela Dia a Dia da Apropriação Diária (Parcela 1 - 21 Dias Úteis): Q128 vs BigDecimal
        List<DailyComparisonRow> dailyComparison = bdEngine.compareDailyAppropriation(spec252, 1);

        System.out.println("3. EVOLUÇÃO DA APROPRIAÇÃO DIÁRIA PRO-RATA DIE (PARCELA 1 - 21 DU): Q128 vs java.math.BigDecimal:");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-4s | %-22s | %-22s | %-13s | %-13s | %-15s | %-15s | %-6s%n",
                "Dia", "Aprop. Dia Q128 (E_16)", "Aprop. Dia BigDecimal", "Dia Q128(R$)", "Dia BD (R$)", "Acum. Q128(R$)", "Acum. BD (R$)", "Diff");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");

        for (DailyComparisonRow dRow : dailyComparison) {
            System.out.printf("%2d/%2d| %-22s | %-22s | %-13s | %-13s | %-15s | %-15s | %d cent%n",
                    dRow.dayInPeriod(),
                    dRow.totalDaysInPeriod(),
                    dRow.q128DailyAccrualExact().formatExact(),
                    dRow.bigDecimalDailyAccrualExact().toPlainString(),
                    Q128RateEngine.formatCentsAsBrl(dRow.q128DailyAccrualCents()),
                    Q128RateEngine.formatCentsAsBrl(dRow.bigDecimalDailyAccrualCents()),
                    Q128RateEngine.formatCentsAsBrl(dRow.q128AccumulatedInterestCents()),
                    Q128RateEngine.formatCentsAsBrl(dRow.bigDecimalAccumulatedInterestCents()),
                    dRow.centsDifference());
        }
        System.out.println("------------------------------------------------------------------------------------------------------------------------\n");

        // Benchmark Q128 vs BigDecimal nas 3 bases (252, 360 e 365)
        System.out.println("4. BENCHMARK QUANTITATIVO DE APROPRIAÇÃO DIÁRIA: Q128 (ZERO-HEAP) vs java.math.BigDecimal (DECIMAL128):");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-32s | %-14s | %-14s | %-10s | %-15s | %-18s | %-8s%n",
                "Base de Contagem", "Q128 (ns/dia)", "BigDec(ns/dia)", "Speedup", "Heap Q128 (B)", "Heap BigDecimal(B)", "Max Diff");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");

        for (DayCountBasis basis : DayCountBasis.values()) {
            SacContractSpec benchSpec = SacContractSpec.builder()
                    .principalCents(principalCents)
                    .totalInstallments(installments)
                    .dayCountBasis(basis)
                    .annualContractRate(annualRate)
                    .build();

            BenchmarkComparisonReport report = bdEngine.runDailyAppropriationBenchmark(benchSpec, 400);
            System.out.printf("%-32s | %11.2f ns | %11.2f ns | %8.2fx  | %13d B | %,16d B | %d cent%n",
                    basis.getDescription(),
                    report.q128NsPerDayOp(),
                    report.bigDecimalNsPerDayOp(),
                    report.speedupFactor(),
                    report.q128HeapBytesAllocated(),
                    report.bigDecimalHeapBytesAllocated(),
                    report.maxCentsDivergenceAcrossAllDays());
        }
        System.out.println("------------------------------------------------------------------------------------------------------------------------\n");

        // 5. Demonstração da Modelagem Matemática BACEN/CETIP/COSIF e ABNT NBR 5891 (Tabelas 1 e 2 + Invariantes I_1..I_6)
        ZeroRoundingDailyAccrualEngine zeroRoundingEngine = new ZeroRoundingDailyAccrualEngine();
        QDec128 factorF8 = Q128RateEngine.fromString("1.00042297");

        ZeroRoundingDailyAccrualEngine.PeriodAccrualSummary table1Summary =
                zeroRoundingEngine.simulateConstantFactorPeriod(1_000_000L, factorF8, 21, DayCountBasis.BUS_252);

        System.out.println("5. TABELA 1 (ESPECIFICAÇÃO §6.2): S_0 = R$ 10.000,00 | i_a = 11,25% a.a. | F_t = "
                + factorF8.formatExact().substring(0, 10) + " (21 DU):");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-3s | %-18s | %-18s | %-10s | %-18s | %-14s | %-14s | %-10s%n",
                "Dia", "Saldo Exato S_t*", "Juro Disp. j_t^disp", "Lanc. L_t", "Residuo R_t (Q128)", "Saldo S_t^cont", "Ingenuo ~S_t", "Erro Ing.");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        for (ZeroRoundingDailyAccrualEngine.DailySimulationRow r : table1Summary.dailyRows()) {
            if (r.day() <= 5 || r.day() == 10 || r.day() == 15 || r.day() >= 20) {
                System.out.printf("%3d | %-18s | %-18s | %-10s | %-18s | %-14s | %-14s | %+d cent%n",
                        r.day(),
                        r.exactBalanceStarQ128().formatExact().substring(0, 15),
                        r.availableInterestDispQ128().formatExact().substring(0, 12),
                        Q128RateEngine.formatCentsAsBrl(r.kahanBresenhamLedgerEntryCents()),
                        r.subCentResidueQ128().formatExact().substring(0, Math.min(14, r.subCentResidueQ128().formatExact().length())),
                        Q128RateEngine.formatCentsAsBrl(r.compensatedAccountingBalanceCents()),
                        Q128RateEngine.formatCentsAsBrl(r.naiveAccountingBalanceCents()),
                        r.naiveErrorCents());
            }
        }
        System.out.printf(" -> Fechamento t=21: Equacao Fechada = %s | Kahan-Bresenham = %s (Erro = %d cent) | Ingenuo = %s (Erro = %+d cent)%n",
                Q128RateEngine.formatCentsAsBrl(table1Summary.closedFormAccountingBalanceCents()),
                Q128RateEngine.formatCentsAsBrl(table1Summary.kahanBresenhamFinalBalanceCents()),
                table1Summary.closedFormVsCompensatedErrorCents(),
                Q128RateEngine.formatCentsAsBrl(table1Summary.naiveFinalBalanceCents()),
                table1Summary.closedFormVsNaiveErrorCents());
        System.out.println("------------------------------------------------------------------------------------------------------------------------\n");

        ZeroRoundingDailyAccrualEngine.PeriodAccrualSummary table2Summary =
                zeroRoundingEngine.simulateConstantFactorPeriod(100_000L, factorF8, 21, DayCountBasis.BUS_252);

        System.out.println("6. TABELA 2 (ESPECIFICAÇÃO §6.2): S_0 = R$ 1.000,00 | i_a = 11,25% a.a. | F_t = "
                + factorF8.formatExact().substring(0, 10) + " (21 DU - COMPENSAÇÃO SUB-CENTAVO):");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-3s | %-18s | %-18s | %-10s | %-18s | %-14s | %-14s | %-10s%n",
                "Dia", "Saldo Exato S_t*", "Juro Disp. j_t^disp", "Lanc. L_t", "Residuo R_t (Q128)", "Saldo S_t^cont", "Ingenuo ~S_t", "Erro Ing.");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        for (ZeroRoundingDailyAccrualEngine.DailySimulationRow r : table2Summary.dailyRows()) {
            System.out.printf("%3d | %-18s | %-18s | %-10s | %-18s | %-14s | %-14s | %+d cent%n",
                    r.day(),
                    r.exactBalanceStarQ128().formatExact().substring(0, 14),
                    r.availableInterestDispQ128().formatExact().substring(0, 12),
                    Q128RateEngine.formatCentsAsBrl(r.kahanBresenhamLedgerEntryCents()),
                    r.subCentResidueQ128().formatExact().substring(0, Math.min(14, r.subCentResidueQ128().formatExact().length())),
                    Q128RateEngine.formatCentsAsBrl(r.compensatedAccountingBalanceCents()),
                    Q128RateEngine.formatCentsAsBrl(r.naiveAccountingBalanceCents()),
                    r.naiveErrorCents());
        }
        System.out.printf(" -> Fechamento t=21: Equacao Fechada = %s | Kahan-Bresenham = %s (Erro = %d cent) | Ingenuo = %s (Erro = %+d cent)%n",
                Q128RateEngine.formatCentsAsBrl(table2Summary.closedFormAccountingBalanceCents()),
                Q128RateEngine.formatCentsAsBrl(table2Summary.kahanBresenhamFinalBalanceCents()),
                table2Summary.closedFormVsCompensatedErrorCents(),
                Q128RateEngine.formatCentsAsBrl(table2Summary.naiveFinalBalanceCents()),
                table2Summary.closedFormVsNaiveErrorCents());
        System.out.println("------------------------------------------------------------------------------------------------------------------------\n");

        ZeroRoundingDailyAccrualEngine.SacZeroRoundingContractReport sacZeroReport =
                zeroRoundingEngine.simulateSacContractWithZeroRounding(spec252);

        System.out.println("7. TABELA A — CRONOGRAMA MENSAL COM COMPARATIVO: SOMA DIÁRIA COMPENSADA vs. PERÍODO FECHADO vs. INGÊNUO:");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-4s | %-6s | %-15s | %-12s | %-12s | %-14s | %-14s | %-9s | %-14s | %-10s | %-14s | %-15s%n",
                "Mes", "DU Ac.", "Saldo Abertura", "Aprop. Dia 1", "Aprop. Dia21", "Soma Diaria", "Periodo Fech.", "Diff(D-F)", "Soma Ingenua", "Erro Ing.", "Amortizacao", "Saldo Pos-Pgto");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------------------");
        for (ZeroRoundingDailyAccrualEngine.MonthlyClosingScheduleRow mRow : sacZeroReport.monthlyClosingSchedule()) {
            System.out.printf("%4d | %6d | %-15s | %-12s | %-12s | %-14s | %-14s | %+4d cent | %-14s | %+5d cent | %-14s | %-15s%n",
                    mRow.installmentNumber(),
                    mRow.cumulativeDays(),
                    Q128RateEngine.formatCentsAsBrl(mRow.openingPrincipalCents()),
                    Q128RateEngine.formatCentsAsBrl(mRow.firstDayAccrualCents()),
                    Q128RateEngine.formatCentsAsBrl(mRow.lastDayAccrualCents()),
                    Q128RateEngine.formatCentsAsBrl(mRow.dailyAccrualSumInterestCents()),
                    Q128RateEngine.formatCentsAsBrl(mRow.closedFormPeriodInterestCents()),
                    mRow.dailyVsClosedFormDiffCents(),
                    Q128RateEngine.formatCentsAsBrl(mRow.naiveDailySumInterestCents()),
                    mRow.naiveVsClosedFormDiffCents(),
                    Q128RateEngine.formatCentsAsBrl(mRow.amortizationQuotaCents()),
                    Q128RateEngine.formatCentsAsBrl(mRow.closingPrincipalCents()));
        }
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf(" TOTAIS 12 MESES | Soma Diaria Compensada = %s | Periodo Fechado = %s (Diff = %+d cent) | Metodo Ingenuo = %s (Erro = %s)%n",
                Q128RateEngine.formatCentsAsBrl(sacZeroReport.totalCompensatedInterestCents()),
                Q128RateEngine.formatCentsAsBrl(sacZeroReport.totalClosedFormInterestCents()),
                sacZeroReport.cumulativeDailyVsClosedFormDiffCents(),
                Q128RateEngine.formatCentsAsBrl(sacZeroReport.totalNaiveInterestCents()),
                Q128RateEngine.formatCentsAsBrl(sacZeroReport.cumulativeNaiveRoundingErrorCents()));
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------------------\n");

        System.out.println("8. TABELA B — CICLO DE VIDA DIÁRIO DA OPERAÇÃO COM FECHAMENTO MENSAL (MÊS 1 COMPLETO + FECHAMENTOS MÊS 2 A 12):");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-6s | %-4s | %-16s | %-12s | %-14s | %-14s | %-14s | %-9s | %-15s | %-15s | %-15s%n",
                "Mes/D", "DiaG", "Saldo Exato S_t*", "Lanc. Dia Lt", "Residuo R_t", "Acum. Diario", "Fechado Dia d", "Diff(D-F)", "Saldo Pre-Pgto", "Pgto(Juros+Am)", "Saldo Pos-Pgto");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------------------");
        for (ZeroRoundingDailyAccrualEngine.OperationLifecycleDailyRow lRow : sacZeroReport.operationDailyLifecycle()) {
            if (lRow.installmentNumber() == 1 || lRow.isMonthEndClosingDay()) {
                String tag = String.format("M%02d/D%02d", lRow.installmentNumber(), lRow.dayInMonth());
                String resStr = lRow.subCentResidueQ128().formatExact();
                System.out.printf("%-6s | %4d | %-16s | %-12s | %-14s | %-14s | %-14s | %+4d cent | %-15s | %-15s | %-15s%n",
                        tag,
                        lRow.globalDay(),
                        lRow.exactBalanceStarQ128().formatExact().substring(0, 15),
                        Q128RateEngine.formatCentsAsBrl(lRow.dailyLedgerEntryCents()),
                        resStr.substring(0, Math.min(12, resStr.length())),
                        Q128RateEngine.formatCentsAsBrl(lRow.accumulatedDailyInterestInMonthCents()),
                        Q128RateEngine.formatCentsAsBrl(lRow.closedFormInterestToDayCents()),
                        lRow.dailyVsClosedFormDiffCents(),
                        Q128RateEngine.formatCentsAsBrl(lRow.preSettlementAccountingBalanceCents()),
                        Q128RateEngine.formatCentsAsBrl(lRow.totalInstallmentPaidAtMonthEndCents()),
                        Q128RateEngine.formatCentsAsBrl(lRow.postSettlementBalanceCents()));
            }
        }
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------------------\n");

        System.out.println("9. AUDITORIA FORMAL DAS 6 INVARIANTES COSIF (I_1 a I_6) EM CONTRATO SAC MULTI-TRANCHE (252 DU):");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf(" Fator Diario BACEN/CETIP (D_8): %s | Dias Auditados: %d | Tranches SAC: %d%n",
                sacZeroReport.bacenDailyFactorF8().formatExact().substring(0, 10),
                sacZeroReport.dailyCosifAuditReports().size(),
                spec252.getTotalInstallments());
        System.out.printf(" Juros Totais Compensados (Erro Zero): %s | Juros Metodo Ingenuo: %s | Divergencia Evitada: %s%n",
                Q128RateEngine.formatCentsAsBrl(sacZeroReport.totalCompensatedInterestCents()),
                Q128RateEngine.formatCentsAsBrl(sacZeroReport.totalNaiveInterestCents()),
                Q128RateEngine.formatCentsAsBrl(sacZeroReport.cumulativeNaiveRoundingErrorCents()));
        System.out.printf(" Fechamento Mensal Erro Zero (Eq. 16): %s | Todas as 6 Invariantes COSIF (I_1..I_6) Verificadas: %s%n",
                sacZeroReport.allPeriodsClosedWithZeroError() ? "VERIFICADO (0,00 centavos)" : "FALHOU",
                sacZeroReport.allCosifInvariantsPassedEveryDay() ? "VERIFICADO (100% dos dias)" : "FALHOU");
        System.out.println("------------------------------------------------------------------------------------------------------------------------\n");

        System.out.println("10. TESTE EXTREMO DE PIOR CASO (Principal = R$ 1.000.000,00 | N = 420 Meses = 35 Anos | Taxa Dízima 14,7931% a.a.):");
        System.out.println("    Quota Dízima: 1.000.000,00 / 420 = R$ 2.380,95238095... | Confronto Q128 exp/Kahan-Bresenham vs BigDecimal(DECIMAL128)");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-24s | %-6s | %-12s | %-16s | %-16s | %-9s | %-9s | %-14s | %-10s | %-8s%n",
                "Base (N=420 / 35 Anos)", "Dias", "Fator F_t(8)", "Juros Total Q128", "Juros Total BD", "Diff(Q-B)", "MaxDiff/d", "Erro Ingenuo", "Quota M420", "Speedup");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------------------");
        for (DayCountBasis basis : DayCountBasis.values()) {
            BigDecimalSacReferenceEngine.WorstCaseExp420Report wc =
                    bdEngine.runWorstCaseExp420Comparison(100_000_000L, 420, "0.147931", basis);
            System.out.printf("%-24s | %6d | %-12s | %-16s | %-16s | %+4d cent | %4d cent | %-14s | %-10s | %6.2fx%n",
                    basis.name() + " (" + basis.getDaysInYear() + "d)",
                    wc.totalDaysInHorizon(),
                    wc.q128BacenDailyFactorF8().formatExact().substring(0, 10),
                    Q128RateEngine.formatCentsAsBrl(wc.q128TotalCompensatedInterestCents()),
                    Q128RateEngine.formatCentsAsBrl(wc.bigDecimalTotalClosedFormInterestCents()),
                    wc.q128VsBigDecimalTotalInterestDiffCents(),
                    wc.maxDailyCentsDiffAcrossAll8820Days(),
                    Q128RateEngine.formatCentsAsBrl(wc.naiveCumulativeErrorCents()),
                    Q128RateEngine.formatCentsAsBrl(wc.lastInstallmentAmortCents()),
                    wc.speedupFactor());
        }
        System.out.println("============================================================================================================================================================");
    }
}
