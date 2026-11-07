package org.quimera.labs.financial.sac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quimera.labs.financial.engine.QDec128;
import org.quimera.labs.financial.sac.ZeroRoundingDailyAccrualEngine.AnalyticalContractLedger;
import org.quimera.labs.financial.sac.ZeroRoundingDailyAccrualEngine.CosifGeneralLedger;
import org.quimera.labs.financial.sac.ZeroRoundingDailyAccrualEngine.CosifInvariantAuditReport;
import org.quimera.labs.financial.sac.ZeroRoundingDailyAccrualEngine.DailySimulationRow;
import org.quimera.labs.financial.sac.ZeroRoundingDailyAccrualEngine.PeriodAccrualSummary;
import org.quimera.labs.financial.sac.ZeroRoundingDailyAccrualEngine.SacZeroRoundingContractReport;
import org.quimera.labs.financial.sac.ZeroRoundingDailyAccrualEngine.TrancheAccrualState;
import org.quimera.labs.financial.sac.ZeroRoundingDailyAccrualEngine.TrancheTaxAssessment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de Testes Formais da Modelagem Matemática para Eliminação de Erros de Arredondamento
 * na Apropriação Diária de Juros (Padrões BACEN / CETIP / COSIF e ABNT NBR 5891) usando {@code Q128}.
 */
class ZeroRoundingDailyAccrualEngineTest {

    private final ZeroRoundingDailyAccrualEngine engine = new ZeroRoundingDailyAccrualEngine();

    @Test
    @DisplayName("1. Operadores de Quantizacao R_8, R_2 (ABNT NBR 5891 Half-Even) e T_2 (Truncamento Fiscal RFB) + Fator Diario F_t")
    void testQuantizationOperatorsAndBacenCetipDailyFactor() {
        // Verifica R_8 sobre 1,0004229714... -> 1,00042297 (Equação 24 da especificação)
        QDec128 rawPaperFactor = Q128RateEngine.fromString("1.0004229714285714");
        assertEquals("1.0004229700000000", ZeroRoundingDailyAccrualEngine.roundR8(rawPaperFactor).formatExact());

        // Verifica cálculo de F_t = R_8((1 + 0,1125)^(1/252)) via motor exponencial/logarítmico Q128:
        // (1,1125)^(1/252) = 1,0004231440043769... -> R_8 = 1,00042314
        QDec128 rate1125 = Q128RateEngine.fromString("0.1125");
        QDec128 factorF8 = ZeroRoundingDailyAccrualEngine.computeBacenCetipDailyFactor8(
                rate1125, DayCountBasis.BUS_252, Q128RateEngine.ONE
        );
        assertEquals("1.0004231400000000", factorF8.formatExact());

        // ABNT NBR 5891 Half-Even em R_2:
        // Equidistante 0,005 -> arredonda para par (0,00)
        assertEquals(0L, ZeroRoundingDailyAccrualEngine.roundR2Cents(Q128RateEngine.fromString("0.005")));
        // Equidistante 0,015 -> arredonda para par (0,02)
        assertEquals(2L, ZeroRoundingDailyAccrualEngine.roundR2Cents(Q128RateEngine.fromString("0.015")));
        // Equidistante 0,025 -> arredonda para par (0,02)
        assertEquals(2L, ZeroRoundingDailyAccrualEngine.roundR2Cents(Q128RateEngine.fromString("0.025")));
        // Equidistante 0,035 -> arredonda para par (0,04)
        assertEquals(4L, ZeroRoundingDailyAccrualEngine.roundR2Cents(Q128RateEngine.fromString("0.035")));

        // Truncamento Fiscal T_2 (RFB): descarta frações sub-centavo
        assertEquals(1L, ZeroRoundingDailyAccrualEngine.truncT2Cents(Q128RateEngine.fromString("0.01999999")));
    }

    @Test
    @DisplayName("2. Reproducao Exata da Tabela 1 (§6.2): S_0 = R$ 10.000,00, i_a = 11,25% a.a., 21 DU, F_t = 1,00042297")
    void testTable1ExactReproduction() {
        QDec128 factorF8 = Q128RateEngine.fromString("1.00042297");
        PeriodAccrualSummary summary = engine.simulateConstantFactorPeriod(
                1_000_000L, // R$ 10.000,00
                factorF8,
                21,
                DayCountBasis.BUS_252
        );

        assertTrue(summary.allDailyInvariantsSatisfied());
        // Equação fechada: R_2(10.000,00 * (1,00042297)^21) = R_2(10.089,19937308) = R$ 10.089,20 (1.008.920 centavos)
        assertEquals(1_008_920L, summary.closedFormAccountingBalanceCents());
        assertEquals(1_008_920L, summary.telescopicLedgerFinalBalanceCents());
        assertEquals(1_008_920L, summary.kahanBresenhamFinalBalanceCents());
        assertEquals(0L, summary.closedFormVsCompensatedErrorCents());

        List<DailySimulationRow> rows = summary.dailyRows();
        // Verifica todos os dias listados na Tabela 1 da especificação (dias 1..10) e fechamento (dia 21)
        assertEquals(1_000_423L, rows.get(0).compensatedAccountingBalanceCents()); // Dia 1: 10.004,23 (L_1 = 4,23, R_1 = -0,00030000)
        assertEquals(423L, rows.get(0).kahanBresenhamLedgerEntryCents());
        assertEquals("-0.0003000000000000", rows.get(0).subCentResidueQ128().formatExact());

        assertEquals(1_000_846L, rows.get(1).compensatedAccountingBalanceCents()); // Dia 2: 10.008,46 (L_2 = 4,23)
        assertEquals(423L, rows.get(1).kahanBresenhamLedgerEntryCents());

        assertEquals(1_001_269L, rows.get(2).compensatedAccountingBalanceCents()); // Dia 3: 10.012,69 (L_3 = 4,23)
        assertEquals(423L, rows.get(2).kahanBresenhamLedgerEntryCents());

        assertEquals(1_001_693L, rows.get(3).compensatedAccountingBalanceCents()); // Dia 4: 10.016,93 (L_4 = 4,24)
        assertEquals(424L, rows.get(3).kahanBresenhamLedgerEntryCents());

        assertEquals(1_002_117L, rows.get(4).compensatedAccountingBalanceCents()); // Dia 5: 10.021,17 (L_5 = 4,24)
        assertEquals(1_002_541L, rows.get(5).compensatedAccountingBalanceCents()); // Dia 6: 10.025,41 (L_6 = 4,24)
        assertEquals(1_002_965L, rows.get(6).compensatedAccountingBalanceCents()); // Dia 7: 10.029,65 (L_7 = 4,24)
        assertEquals(1_003_389L, rows.get(7).compensatedAccountingBalanceCents()); // Dia 8: 10.033,89 (L_8 = 4,24)
        assertEquals(1_003_813L, rows.get(8).compensatedAccountingBalanceCents()); // Dia 9: 10.038,13 (L_9 = 4,24)
        assertEquals(1_004_238L, rows.get(9).compensatedAccountingBalanceCents()); // Dia 10: 10.042,38 (L_10 = 4,25)
        assertEquals(425L, rows.get(9).kahanBresenhamLedgerEntryCents());
        assertEquals(1_008_920L, rows.get(20).compensatedAccountingBalanceCents()); // Dia 21: 10.089,20
    }

    @Test
    @DisplayName("3. Reproducao Exata da Tabela 2 (§6.2): S_0 = R$ 1.000,00, 21 DU — Compensacao Sub-Centavo nos Dias {2,5,8,10,13,16,19,21}")
    void testTable2ExactReproduction() {
        QDec128 factorF8 = Q128RateEngine.fromString("1.00042297");
        PeriodAccrualSummary summary = engine.simulateConstantFactorPeriod(
                100_000L, // R$ 1.000,00
                factorF8,
                21,
                DayCountBasis.BUS_252
        );

        assertTrue(summary.allDailyInvariantsSatisfied());

        // Equação fechada (Eq. 25): R_2(1.000,00 * (1,00042297)^21) = R_2(1.008,91993731) = R$ 1.008,92 (100.892 centavos)
        assertEquals(100_892L, summary.closedFormAccountingBalanceCents());
        assertEquals(100_892L, summary.telescopicLedgerFinalBalanceCents());
        assertEquals(100_892L, summary.kahanBresenhamFinalBalanceCents());
        assertEquals(0L, summary.closedFormVsCompensatedErrorCents());

        // Verifica os valores exatos da Tabela 2 para S_t^cont nos dias 1, 2, 3, 4, 5, 10, 15 e 21:
        List<DailySimulationRow> rows = summary.dailyRows();
        assertEquals(100_042L, rows.get(0).compensatedAccountingBalanceCents());  // Dia 1:  1.000,42 (L_1 = 0,42)
        assertEquals(100_085L, rows.get(1).compensatedAccountingBalanceCents());  // Dia 2:  1.000,85 (L_2 = 0,43)
        assertEquals(100_127L, rows.get(2).compensatedAccountingBalanceCents());  // Dia 3:  1.001,27 (L_3 = 0,42)
        assertEquals(100_169L, rows.get(3).compensatedAccountingBalanceCents());  // Dia 4:  1.001,69 (L_4 = 0,42)
        assertEquals(100_212L, rows.get(4).compensatedAccountingBalanceCents());  // Dia 5:  1.002,12 (L_5 = 0,43)
        assertEquals(100_424L, rows.get(9).compensatedAccountingBalanceCents());  // Dia 10: 1.004,24 (L_10 = 0,43)
        assertEquals(100_636L, rows.get(14).compensatedAccountingBalanceCents()); // Dia 15: 1.006,36 (L_15 = 0,42)
        assertEquals(100_892L, rows.get(20).compensatedAccountingBalanceCents()); // Dia 21: 1.008,92 (L_21 = 0,43)

        // Para eliminar a defasagem de -10 centavos (1.008,82 -> 1.008,92), ocorrem exatamente 10 dias
        // de compensação (+1 centavo -> R$ 0,43): t in {2, 5, 8, 10, 12, 14, 16, 18, 19, 21}
        List<Integer> compensationDays = new ArrayList<>();
        for (DailySimulationRow r : summary.dailyRows()) {
            assertTrue(r.dualAndKahanIdentical());
            assertTrue(r.invariantI1Satisfied());
            assertTrue(r.invariantI3Satisfied());
            if (r.kahanBresenhamLedgerEntryCents() == 43L) {
                compensationDays.add(r.day());
            } else {
                assertEquals(42L, r.kahanBresenhamLedgerEntryCents());
            }
        }
        assertEquals(List.of(2, 5, 8, 10, 12, 14, 16, 18, 19, 21), compensationDays);
        assertEquals(10, compensationDays.size());

        // No método ingênuo sobre principal fixo (Tabela 2: 1.000,00 + 21 * R_2(0,42297) = 1.008,82), o erro é -10 centavos
        long naiveFixedPrincipalDayCents = ZeroRoundingDailyAccrualEngine.roundR2Cents(
                Q128RateEngine.multiply(Q128RateEngine.fromCents(100_000L), Q128RateEngine.fromString("0.00042297"))
        );
        assertEquals(42L, naiveFixedPrincipalDayCents);
        long naiveTable2BalanceCents = 100_000L + (21L * naiveFixedPrincipalDayCents);
        assertEquals(100_882L, naiveTable2BalanceCents);
        assertEquals(-10L, naiveTable2BalanceCents - summary.kahanBresenhamFinalBalanceCents());
    }

    @Test
    @DisplayName("4. Equivalencia Algebrica entre Solucao 1 (Estado Dual Telescopico) e Solucao 2 (Kahan-Bresenham) nas Bases 252, 360 e 365")
    void testEquivalenceAcrossAllDayCountBases() {
        QDec128 annualRate = Q128RateEngine.fromString("0.1375");
        long[] principals = {100_000L, 750_000L, 12_345_678L, 100_000_000L};

        for (DayCountBasis basis : DayCountBasis.values()) {
            QDec128 factorF8 = ZeroRoundingDailyAccrualEngine.computeBacenCetipDailyFactor8(
                    annualRate, basis, Q128RateEngine.ONE
            );
            int days = basis == DayCountBasis.BUS_252 ? 63 : 90;

            for (long p0 : principals) {
                PeriodAccrualSummary summary = engine.simulateConstantFactorPeriod(p0, factorF8, days, basis);
                assertTrue(summary.allDailyInvariantsSatisfied(),
                        "Invariantes diárias devem valer na base " + basis + " para P_0=" + p0);
                assertEquals(summary.closedFormAccountingBalanceCents(), summary.telescopicLedgerFinalBalanceCents());
                assertEquals(summary.closedFormAccountingBalanceCents(), summary.kahanBresenhamFinalBalanceCents());
                assertEquals(0L, summary.closedFormVsCompensatedErrorCents());
            }
        }
    }

    @Test
    @DisplayName("5. Eventos Intermediarios (§5, Eqs. 30-33): Amortizacao Parcial Preserva R_t e Liquidacao Total Zera R_t")
    void testIntermediateAmortizationAndTotalRedemption() {
        QDec128 factorF8 = Q128RateEngine.fromString("1.00042297");
        TrancheAccrualState tranche = new TrancheAccrualState("TRANCHE-TEST", 0, 100_000L); // R$ 1.000,00

        // Apropria 4 dias úteis
        for (int d = 1; d <= 4; d++) {
            tranche.accrueOneDay(d, factorF8);
        }

        QDec128 residueBeforePartial = tranche.getSubCentResidueQ128();
        assertFalse(residueBeforePartial.isZero(), "Resíduo sub-centavo antes da amortização parcial deve ser != 0");

        // Amortização Parcial (0 < \theta_t < 1, Eq. 33): paga R$ 200,00 de principal e R$ 1,00 de juros
        tranche.applyAmortizationOrRedemption(20_000L, 100L);
        QDec128 residueAfterPartial = tranche.getSubCentResidueQ128();

        // Verifica Eq. (33): R_t^pós == R_t e P_t^pós + J_t^{cont,pós} + R_t^pós == S_t^{*,pós}
        assertTrue(residueBeforePartial.isEquivalent(residueAfterPartial),
                "Amortização parcial em centavos deve preservar exatamente o resíduo R_t");
        QDec128 reconstructedStar = Q128RateEngine.add(
                Q128RateEngine.fromCents(tranche.getAccountingBalanceCents()),
                tranche.getSubCentResidueQ128()
        );
        assertTrue(reconstructedStar.isEquivalent(tranche.getExactBalanceStarQ128()),
                "Conservação econômica I_1 deve permanecer intacta após amortização parcial");

        // Continua apropriando mais 5 dias úteis sem perda de sincronismo entre Estado Dual e Kahan-Bresenham
        for (int d = 5; d <= 9; d++) {
            DailySimulationRow row = tranche.accrueOneDay(d, factorF8);
            assertTrue(row.invariantI1Satisfied());
            assertTrue(row.invariantI3Satisfied());
            assertTrue(row.dualAndKahanIdentical());
        }

        // Liquidação Total (\theta_t = 1, Eq. 32): resgata 100% do saldo contábil -> R_t^pós = 0
        tranche.applyAmortizationOrRedemption(tranche.getPrincipalCents(), tranche.getAccruedInterestCents());
        assertEquals(0L, tranche.getAccountingBalanceCents());
        assertTrue(tranche.getSubCentResidueQ128().isZero(), "Liquidação total deve zerar o resíduo R_t");
        assertTrue(tranche.getExactBalanceStarQ128().isZero(), "Liquidação total deve zerar S_t^*");
    }

    @Test
    @DisplayName("6. Auditoria Formal das 6 Invariantes (I_1 a I_6, §7 e §8) em Carteira Multi-Contrato e Multi-Tranche COSIF")
    void testSixFormalInvariantsOnMultiContractCosifLedger() {
        QDec128 factorF8 = Q128RateEngine.fromString("1.00042297");

        // Contrato 1 com 3 tranches em datas de início distintas (para testar IOF regressivo 1..29 dias e IRRF)
        AnalyticalContractLedger contract1 = new AnalyticalContractLedger("CONTRATO-CDB-01", List.of(
                new TrancheAccrualState("C1-T1", 0, 100_000L),   // R$ 1.000,00
                new TrancheAccrualState("C1-T2", 5, 250_000L),   // R$ 2.500,00
                new TrancheAccrualState("C1-T3", 10, 500_000L)   // R$ 5.000,00
        ));

        // Contrato 2 com 2 tranches
        AnalyticalContractLedger contract2 = new AnalyticalContractLedger("CONTRATO-SAC-02", List.of(
                new TrancheAccrualState("C2-T1", 0, 1_000_000L), // R$ 10.000,00
                new TrancheAccrualState("C2-T2", 0, 1_500_000L)  // R$ 15.000,00
        ));

        CosifGeneralLedger cosifLedger = new CosifGeneralLedger(List.of(contract1, contract2));

        for (int day = 1; day <= 35; day++) {
            CosifInvariantAuditReport report = cosifLedger.processDailyAccrualAndAudit(day, factorF8);
            assertTrue(report.invariantI1EconomicConservation(), "Invariante I_1 falhou no dia " + day);
            assertTrue(report.invariantI2CosifReconciliation(), "Invariante I_2 falhou no dia " + day);
            assertTrue(report.invariantI3LyapunovResidueBound(), "Invariante I_3 falhou no dia " + day);
            assertTrue(report.invariantI4DoubleEntryAndTelescopic(), "Invariante I_4 falhou no dia " + day);
            assertTrue(report.invariantI5MultiTrancheAdditivity(), "Invariante I_5 falhou no dia " + day);
            assertTrue(report.invariantI6TaxIntegrityAndNoConfiscation(), "Invariante I_6 falhou no dia " + day);
            assertTrue(report.allSixInvariantsPassed());
            assertEquals(report.generalLedgerBalanceCents(), report.subledgerSumBalanceCents());

            // Testa amortização parcial no dia 15 sobre uma tranche
            if (day == 15) {
                TrancheAccrualState t1 = contract1.getTranches().get(0);
                cosifLedger.registerAmortizationEvent(t1, 30_000L, t1.getAccruedInterestCents());
            }
        }

        // Verifica explicitamente cálculo de IOF regressivo (dia 15 -> 50%) e isenção de IOF (dia >= 30 -> 0%)
        TrancheAccrualState sampleTranche = contract2.getTranches().get(0);
        TrancheTaxAssessment taxDay15 = ZeroRoundingDailyAccrualEngine.computeTrancheTaxes(sampleTranche, 15);
        assertEquals(50, taxDay15.iofRegressiveRatePct());
        assertTrue(taxDay15.invariantI6Satisfied());

        TrancheTaxAssessment taxDay35 = ZeroRoundingDailyAccrualEngine.computeTrancheTaxes(sampleTranche, 35);
        assertEquals(0, taxDay35.iofRegressiveRatePct());
        assertEquals(0L, taxDay35.iofTaxCents());
        assertTrue(taxDay35.invariantI6Satisfied());
    }

    @Test
    @DisplayName("7. Integracao Completa com Contrato SAC Multi-Parcela (simulateSacContractWithZeroRounding)")
    void testSacContractZeroRoundingIntegration() {
        SacContractSpec spec = SacContractSpec.builder()
                .principalCents(120_000_000L) // R$ 1.200.000,00
                .totalInstallments(12)
                .dayCountBasis(DayCountBasis.BUS_252)
                .annualContractRate("0.1125")
                .build();

        SacZeroRoundingContractReport report = engine.simulateSacContractWithZeroRounding(spec);
        assertTrue(report.allPeriodsClosedWithZeroError(),
                "Todas as 12 parcelas SAC devem fechar com erro zero entre a soma diária e a fórmula fechada");
        assertTrue(report.allCosifInvariantsPassedEveryDay(),
                "Todas as 6 invariantes COSIF (I_1 a I_6) devem passar nos 252 dias úteis e encerrar com saldo zero");
        assertEquals(252, report.dailyCosifAuditReports().size());
        assertEquals(12, report.monthlyClosingSchedule().size());
        assertEquals(252, report.operationDailyLifecycle().size());
        assertEquals(0L, report.cumulativeDailyVsClosedFormDiffCents());

        for (ZeroRoundingDailyAccrualEngine.MonthlyClosingScheduleRow mRow : report.monthlyClosingSchedule()) {
            assertEquals(mRow.closedFormPeriodInterestCents(), mRow.dailyAccrualSumInterestCents());
            assertEquals(0L, mRow.dailyVsClosedFormDiffCents());
            assertEquals(mRow.closedFormPreAmortizationBalanceCents(), mRow.preAmortizationBalanceCents());
        }

        for (ZeroRoundingDailyAccrualEngine.OperationLifecycleDailyRow lRow : report.operationDailyLifecycle()) {
            assertEquals(lRow.closedFormInterestToDayCents(), lRow.accumulatedDailyInterestInMonthCents());
            assertEquals(0L, lRow.dailyVsClosedFormDiffCents());
            assertEquals(lRow.closedFormAccountingBalanceToDayCents(), lRow.preSettlementAccountingBalanceCents());
        }
        assertEquals(0L, report.operationDailyLifecycle().get(251).postSettlementBalanceCents());
    }

    @Test
    @DisplayName("8. Teste Extremo de Pior Caso: Principal = R$ 1.000.000,00 com N = 420 Meses (35 Anos / 8.820 DU) — Q128 exp vs BigDecimal")
    void testWorstCaseOneMillionPrincipal420InstallmentsAgainstBigDecimal() {
        BigDecimalSacReferenceEngine bdEngine = new BigDecimalSacReferenceEngine();
        long oneMillionReaisCents = 100_000_000L; // R$ 1.000.000,00 (gera dízima 1.000.000 / 420 = R$ 2.380,95238095...)
        int n420 = 420; // 35 anos (prazo máximo imobiliário SFH/SFI = 8.820 DU na base 252 / 12.600 dias em 360/365)

        // Taxa de pior caso com mantissa densa não-terminante (14,7931% a.a.) nas 3 bases (252, 360, 365)
        for (DayCountBasis basis : DayCountBasis.values()) {
            BigDecimalSacReferenceEngine.WorstCaseExp420Report report =
                    bdEngine.runWorstCaseExp420Comparison(oneMillionReaisCents, n420, "0.147931", basis);

            assertTrue(report.dailyFactorF8BitIdentical(),
                    "Fator diário F_t em D_8 deve ser 100% idêntico entre Q128 e BigDecimal na base " + basis);
            assertEquals(0L, report.finalClosingPrincipalCents(),
                    "Saldo devedor residual no mês 420 deve ser exatamente R$ 0,00 apesar da dízima 1.000.000 / 420");
            assertEquals(0L, report.q128DailyVsClosedFormDiffCents(),
                    "Soma diária Kahan-Bresenham deve fechar com 0 centavos de erro vs período fechado nos 420 meses");
            assertEquals(0L, report.q128VsBigDecimalTotalInterestDiffCents(),
                    "Juro total acumulado em 420 meses no Q128 deve igualar exatamente o BigDecimal(DECIMAL128) na base " + basis);
            assertEquals(0L, report.maxDailyCentsDiffAcrossAll8820Days(),
                    "Nenhum dos " + report.totalDaysInHorizon() + " dias pode divergir em centavos entre Q128 e BigDecimal");
            assertTrue(report.all420MonthsZeroRoundingError(),
                    "Todos os 420 fechamentos mensais devem ter erro zero");
            assertTrue(report.allCosifInvariantsPassed8820Days(),
                    "Todas as 6 invariantes COSIF devem passar nos " + report.totalDaysInHorizon() + " dias e 420 tranches");
        }
    }
}
