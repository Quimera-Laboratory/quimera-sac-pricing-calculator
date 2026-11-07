package org.quimera.labs.financial.sac;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quimera.labs.financial.engine.QDec128;
import org.quimera.labs.financial.engine.QDec128ColumnarVector;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suíte de testes JUnit 5 para validação matemática, contábil e de performance (Zero-Heap)
 * da Calculadora de Precificação SAC com Q128 nas bases 252, 360 e 365.
 */
class SacPricingCalculatorTest {

    private final SacPricingCalculator calculator = new SacPricingCalculator();

    @Test
    @DisplayName("1. [BASE 252 DU] Precificação SAC ao par com principal quebrado, fechamento exato e VP == Principal")
    void testSacPricingBase252() {
        long principalCents = 123_456_789L; // R$ 1.234.567,89
        SacContractSpec spec = SacContractSpec.builder()
                .principalCents(principalCents)
                .totalInstallments(12)
                .dayCountBasis(252)
                .annualContractRate("0.1365")
                .uniformDaysPerInstallment(21)
                .build();

        SacPricingResult result = calculator.priceAtPar(spec);

        assertEquals(DayCountBasis.BUS_252, result.valuationBasis());
        assertEquals(principalCents, result.totalAmortizedCents(), "Soma das amortizações deve fechar 100% do principal");
        assertEquals(0L, result.schedule().get(11).closingBalanceCents(), "Saldo final após a 12ª parcela deve ser zero");
        assertTrue(Math.abs(result.totalPresentValueCents() - principalCents) <= 1L,
                "Valor Presente ao par na base 252 deve reproduzir o principal");
        assertTrue(result.parcelSheafInvariantVerified(), "Invariante de feixe por saldo de parcela deve ser verdadeira");

        // TIR calculada em Q128 deve convergir para 13,65% a.a.
        assertEquals(0.1365, result.internalRateOfReturnAnnualQ128().toDouble(), 1e-6);
    }

    @Test
    @DisplayName("2. [BASE 360 COMERCIAL] Precificação SAC Exponencial e Linear em ACT/360 (30/360)")
    void testSacPricingBase360CompoundAndLinear() {
        long principalCents = 100_000_000L; // R$ 1.000.000,00

        // 2.1 Modo Linear Proporcional em Base 360: 12% a.a. em períodos de 30 dias => exatos 1,00% ao mês
        SacContractSpec linearSpec360 = SacContractSpec.builder()
                .principalCents(principalCents)
                .totalInstallments(10)
                .dayCountBasis(360)
                .annualContractRate("0.12")
                .uniformDaysPerInstallment(30)
                .compoundingMode(RateCompoundingMode.LINEAR_PROPORTIONAL)
                .build();

        SacPricingResult linearRes = calculator.priceAtPar(linearSpec360);
        assertEquals(principalCents, linearRes.totalAmortizedCents());
        // 1ª parcela: Saldo R$ 1.000.000,00 * 1% = R$ 10.000,00 (1.000.000 centavos) de juros
        assertEquals(1_000_000L, linearRes.schedule().get(0).interestQuotaCents());
        // Soma PA dos juros de 10 parcelas SAC a 1% a.m.: 10.000 * (10 + 1) / 2 = R$ 55.000,00 (5.500.000 centavos)
        assertEquals(5_500_000L, linearRes.totalInterestCents());

        // 2.2 Modo Exponencial Composto em Base 360: (1 + 0.12)^(30/360)
        SacContractSpec expSpec360 = SacContractSpec.builder()
                .principalCents(principalCents)
                .totalInstallments(12)
                .dayCountBasis(DayCountBasis.ACT_360)
                .annualContractRate("0.1268250301319697") // (1.01)^12 - 1 => 1% a.m. composto
                .uniformDaysPerInstallment(30)
                .compoundingMode(RateCompoundingMode.EXPONENTIAL_COMPOUND)
                .build();

        SacPricingResult expRes = calculator.priceAtPar(expSpec360);
        assertEquals(principalCents, expRes.totalAmortizedCents());
        assertEquals(1_000_000L, expRes.schedule().get(0).interestQuotaCents(),
                "Com taxa anual (1.01^12 - 1) na base 360, o juro de 30 dias sobre R$ 1M deve ser exatos R$ 10.000,00");
        assertTrue(Math.abs(expRes.totalPresentValueCents() - principalCents) <= 1L);
    }

    @Test
    @DisplayName("3. [BASE 365 CIVIL] Precificação SAC em ACT/365 com calendário civil real (28, 30 e 31 dias)")
    void testSacPricingBase365WithCivilCalendar() {
        long principalCents = 240_000_000L; // R$ 2.400.000,00
        int[] civilMonthsDays = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31}; // Soma = 365 dias

        SacContractSpec spec365 = SacContractSpec.builder()
                .principalCents(principalCents)
                .totalInstallments(12)
                .dayCountBasis(365)
                .annualContractRate("0.1450")
                .daysPerInstallment(civilMonthsDays)
                .build();

        SacPricingResult res365 = calculator.priceAtPar(spec365);

        assertEquals(DayCountBasis.ACT_365, res365.valuationBasis());
        assertEquals(365, res365.schedule().get(11).cumulativeDays(), "Soma dos dias do ano civil deve ser 365");
        assertEquals(principalCents, res365.totalAmortizedCents());
        assertEquals(0L, res365.schedule().get(11).closingBalanceCents());
        assertTrue(Math.abs(res365.totalPresentValueCents() - principalCents) <= 1L,
                "Valor Presente ao par na base 365 com meses irregulares (28/30/31) deve reproduzir o principal");
        assertTrue(res365.parcelSheafInvariantVerified());
    }

    @Test
    @DisplayName("4. [EQUIVALÊNCIA ENTRE BASES 252, 360 E 365] Isomorfismo de fatores de período via Q128")
    void testCrossBasisEquivalence() {
        QDec128 rate252 = Q128RateEngine.fromString("0.1325"); // 13,25% a.a. para 21 DU na base 252

        // Converte para a taxa anual equivalente em ACT/360 (para mês de 30 dias) e ACT/365 (para mês de 30 dias)
        QDec128 eqRate360 = Q128RateEngine.convertEquivalentAnnualRate(
                rate252, DayCountBasis.BUS_252, 21, DayCountBasis.ACT_360, 30
        );
        QDec128 eqRate365 = Q128RateEngine.convertEquivalentAnnualRate(
                rate252, DayCountBasis.BUS_252, 21, DayCountBasis.ACT_365, 30
        );

        long factor252 = Q128RateEngine.periodCompoundFactor16(
                Q128RateEngine.toRaw16(rate252), 21, DayCountBasis.BUS_252, RateCompoundingMode.EXPONENTIAL_COMPOUND
        );
        long factor360 = Q128RateEngine.periodCompoundFactor16(
                Q128RateEngine.toRaw16(eqRate360), 30, DayCountBasis.ACT_360, RateCompoundingMode.EXPONENTIAL_COMPOUND
        );
        long factor365 = Q128RateEngine.periodCompoundFactor16(
                Q128RateEngine.toRaw16(eqRate365), 30, DayCountBasis.ACT_365, RateCompoundingMode.EXPONENTIAL_COMPOUND
        );

        assertTrue(Math.abs(factor252 - factor360) <= 2L, "Fator mensal equivalente 252 vs 360 deve coincidir em Q128");
        assertTrue(Math.abs(factor252 - factor365) <= 2L, "Fator mensal equivalente 252 vs 365 deve coincidir em Q128");
    }

    @Test
    @DisplayName("5. [APROPRIAÇÃO DIÁRIA PRO-RATA DIE] Fechamento contábil diário nas bases 252, 360 e 365")
    void testDailyProRataDieAccrualInAllBases() {
        for (DayCountBasis basis : DayCountBasis.values()) {
            SacContractSpec spec = SacContractSpec.builder()
                    .principalCents(95_432_100L)
                    .totalInstallments(12)
                    .dayCountBasis(basis)
                    .annualContractRate("0.1285")
                    .build();

            SacPricingResult result = calculator.priceAtPar(spec);

            for (int k = 1; k <= 12; k++) {
                List<SacDailyAccrualRow> dailyRows = calculator.computeDailyAccrualSchedule(spec, k);
                assertEquals(basis.getDefaultDaysPerMonth(), dailyRows.size());

                long sumDailyDeltas = 0L;
                for (SacDailyAccrualRow dRow : dailyRows) {
                    assertTrue(dRow.dailyInterestDeltaCents() >= 0L, "Delta diário não pode ser negativo");
                    sumDailyDeltas += dRow.dailyInterestDeltaCents();
                }

                long expectedInstallmentInterest = result.schedule().get(k - 1).interestQuotaCents();
                assertEquals(expectedInstallmentInterest, sumDailyDeltas,
                        "Soma dos deltas diários na base " + basis + " deve fechar 100% com o juro da parcela " + k);
            }
        }
    }

    @Test
    @DisplayName("6. [MARCAÇÃO A MERCADO & LIQUIDAÇÃO ANTECIPADA] Ágio, Deságio e Quitação nas 3 bases")
    void testMarkToMarketAndEarlyPayoffAcrossBases() {
        long principalCents = 360_000_000L; // R$ 3.600.000,00

        for (DayCountBasis basis : DayCountBasis.values()) {
            SacContractSpec spec = SacContractSpec.builder()
                    .principalCents(principalCents)
                    .totalInstallments(12)
                    .dayCountBasis(basis)
                    .annualContractRate("0.14")
                    .build();

            // 1. Taxa de mercado menor (10% a.a.) => Contrato negociado com ÁGIO (VP > Principal)
            SacPricingResult premiumRes = calculator.price(spec, Q128RateEngine.fromString("0.10"), basis);
            assertTrue(premiumRes.totalPresentValueCents() > principalCents, "Deve apresentar ágio quando juros caem");
            assertTrue(premiumRes.premiumOrDiscountCents() > 0L);

            // 2. Taxa de mercado maior (18% a.a.) => Contrato negociado com DESÁGIO (VP < Principal)
            SacPricingResult discountRes = calculator.price(spec, Q128RateEngine.fromString("0.18"), basis);
            assertTrue(discountRes.totalPresentValueCents() < principalCents, "Deve apresentar deságio quando juros sobem");
            assertTrue(discountRes.premiumOrDiscountCents() < 0L);

            // 3. Liquidação Antecipada após a 5ª parcela (restam 7 parcelas = 7 * R$ 300.000,00 = R$ 2.100.000,00)
            QDec128 payoffPv = calculator.calculateEarlyPayoffPresentValue(
                    spec, 5, spec.getAnnualContractRate(), basis
            );
            long expectedRemainingPrincipalCents = 210_000_000L;
            assertTrue(Math.abs(payoffPv.projectCentsRound2HalfEven() - expectedRemainingPrincipalCents) <= 1L,
                    "Liquidação antecipada à taxa contratual na base " + basis + " deve reproduzir o saldo devedor");
        }
    }

    @Test
    @DisplayName("7. [CARÊNCIA E PÓS-FIXADO] SAC com carência de principal e correção monetária")
    void testGracePeriodAndMonetaryCorrection() {
        long principalCents = 180_000_000L;
        long[] monthlyInflation16 = new long[12];
        for (int i = 0; i < 12; i++) {
            monthlyInflation16[i] = 45_000_000_000_000L; // +0,45% de correção monetária ao mês
        }

        SacContractSpec spec = SacContractSpec.builder()
                .principalCents(principalCents)
                .totalInstallments(12)
                .graceInstallments(3) // 3 meses de carência de principal (amortiza da 4ª à 12ª parcela)
                .dayCountBasis(DayCountBasis.ACT_360)
                .annualContractRate("0.08")
                .monetaryCorrectionRates16(monthlyInflation16)
                .build();

        SacPricingResult res = calculator.priceAtPar(spec);

        for (int k = 1; k <= 3; k++) {
            assertEquals(0L, res.schedule().get(k - 1).amortizationQuotaCents(), "Sem amortização na carência");
            assertTrue(res.schedule().get(k - 1).interestQuotaCents() > 0L, "Paga juros durante a carência");
        }
        assertEquals(
                principalCents + res.totalMonetaryCorrectionCents(),
                res.totalAmortizedCents(),
                "Total amortizado deve igualar Principal + Correção Monetária acumulada"
        );
        assertEquals(0L, res.schedule().get(11).closingBalanceCents());
    }

    @Test
    @DisplayName("8. [ZERO HEAP ALLOCATION] Processamento colunar QDec128ColumnarVector com 0 bytes no Heap")
    void testZeroHeapColumnarAppropriation() {
        int nInstallments = 120;
        QDec128ColumnarVector vector = new QDec128ColumnarVector(nInstallments, Q128RateEngine.SCALE_16);
        long[] factors16 = new long[nInstallments];

        long baseFactor16 = Q128RateEngine.periodCompoundFactor16(
                Q128RateEngine.toRaw16(Q128RateEngine.fromString("0.125")),
                21,
                DayCountBasis.BUS_252,
                RateCompoundingMode.EXPONENTIAL_COMPOUND
        );

        for (int i = 0; i < nInstallments; i++) {
            vector.setQDec128(i, Q128RateEngine.fromCents(50_000_00L));
            factors16[i] = baseFactor16;
        }

        long[] qArr = vector.qArr();
        long[] rArr = vector.rArr();

        // Warmup JIT C2
        long warm = calculator.runZeroHeapBatchAppropriation(qArr, rArr, factors16, nInstallments, 5_000);

        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().getId();

        long bytesBefore = threadBean.getThreadAllocatedBytes(threadId);
        long check = warm + calculator.runZeroHeapBatchAppropriation(qArr, rArr, factors16, nInstallments, 10_000);
        long bytesAfter = threadBean.getThreadAllocatedBytes(threadId);

        long allocated = bytesAfter - bytesBefore;
        assertNotEquals(0L, check);
        assertEquals(0L, allocated, "O loop colunar Q128 em Java deve alocar 0 bytes na Heap");
    }

    @Test
    @DisplayName("9. [COMPARATIVO MULTI-BASE] Execução simultânea nas bases 252, 360 e 365")
    void testCompareAcrossAllBases() {
        Map<DayCountBasis, SacPricingResult> map =
                calculator.compareAcrossAllBases(100_000_000L, 12, "0.12");
        assertEquals(3, map.size());
        assertNotNull(map.get(DayCountBasis.BUS_252));
        assertNotNull(map.get(DayCountBasis.ACT_360));
        assertNotNull(map.get(DayCountBasis.ACT_365));

        // Em 12 parcelas de 30 dias, os juros em ACT/360 (30/360 = 1/12 ano) são maiores que em ACT/365 (30/365 < 1/12 ano)
        assertTrue(map.get(DayCountBasis.ACT_360).totalInterestCents() > map.get(DayCountBasis.ACT_365).totalInterestCents());
    }

    @Test
    @DisplayName("10. [COMPARATIVO Q128 vs BigDecimal] Paridade diária centavo-a-centavo, Zero-Heap e Speedup nas bases 252, 360 e 365")
    void testDailyAppropriationParityAndBenchmarkVsBigDecimal() {
        BigDecimalSacReferenceEngine bdEngine = new BigDecimalSacReferenceEngine();

        for (DayCountBasis basis : DayCountBasis.values()) {
            SacContractSpec spec = SacContractSpec.builder()
                    .principalCents(120_000_000L)
                    .totalInstallments(12)
                    .dayCountBasis(basis)
                    .annualContractRate("0.1325")
                    .build();

            // 1. Paridade dia a dia em todas as 12 parcelas
            for (int k = 1; k <= 12; k++) {
                var compRows = bdEngine.compareDailyAppropriation(spec, k);
                assertFalse(compRows.isEmpty());
                for (var row : compRows) {
                    assertEquals(0L, row.centsDifference(),
                            "Divergência em centavos entre Q128 e BigDecimal na base " + basis
                                    + " parcela " + k + " dia " + row.dayInPeriod());
                    assertTrue(row.q128DailyAccrualCents() > 0L);
                }
            }

            // 2. Benchmark de Throughput e Alocação de Memória: Q128 vs BigDecimal
            var report = bdEngine.runDailyAppropriationBenchmark(spec, 200);
            assertEquals(0L, report.maxCentsDivergenceAcrossAllDays());
            assertEquals(0L, report.q128HeapBytesAllocated(), "Q128 deve alocar 0 bytes na Heap no loop diário");
            assertTrue(report.bigDecimalHeapBytesAllocated() > 1_000_000L, "BigDecimal aloca megabytes na Heap");
            assertTrue(report.speedupFactor() > 2.0, "Q128 deve ser significativamente mais rápido que BigDecimal");
        }
    }
}
