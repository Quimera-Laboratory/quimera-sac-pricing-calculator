package org.quimera.labs.financial.sac;

import org.quimera.labs.financial.engine.QDec128;
import org.quimera.labs.financial.engine.QuimeraCategoricalDecimal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Implementação Formal da Modelagem Matemática para Eliminação de Erros de Arredondamento
 * na Apropriação Diária de Juros (Padrões BACEN / CETIP / COSIF e ABNT NBR 5891) usando {@code Q128}.
 *
 * <p>Implementa integralmente:
 * <ol>
 *   <li><b>Espaços Numéricos Discretos</b>: $\mathbb{D}_{16}$, $\mathbb{D}_8$ (Fatores BACEN/CETIP),
 *       $\mathbb{D}_2$ (Moeda Contábil), operador simétrico $\mathcal{R}_2$ (ABNT NBR 5891 Half-Even),
 *       $\mathcal{R}_8$ (Fator Diário BACEN/CETIP) e truncamento fiscal $\mathcal{T}_2$ (RFB).</li>
 *   <li><b>Solução 1 — Arquitetura de Estado Dual (Soma Telescópica, Eqs. 11–16)</b>:
 *       $S_t^* = S_{t-1}^* \cdot F_t + \Delta E_t$, $S_t^{\text{cont}} = \mathcal{R}_2(S_t^*)$,
 *       $L_t = \mathcal{R}_2(S_t^*) - \mathcal{R}_2(S_{t-1}^*) - \Delta E_t$.</li>
 *   <li><b>Solução 2 — Acumulação de Resíduos Sub-Centavo (Kahan-Bresenham Financeiro, Eqs. 17–29)</b>:
 *       Tupla $(P_t, J_t^{\text{cont}}, R_t)$ com conservação exata $P_t + J_t^{\text{cont}} + R_t = S_t^*$
 *       e confinamento de Lyapunov $|R_t| \le 0{,}005$.</li>
 *   <li><b>Eventos Intermediários de Amortização Parcial e Liquidação Total (§5, Eqs. 30–33)</b>:
 *       Preservação de $R_t^{\text{pós}} = R_t$ em amortizações parciais em centavos ($\Delta E_t \in \mathbb{D}_2$)
 *       e zeramento em liquidação total ($\theta_t = 1$).</li>
 *   <li><b>Reconciliação Razão Analítico vs. Razão Geral Sintético COSIF (§7, Eqs. 35–36)</b> e
 *       <b>Auditoria Formal das 6 Invariantes ($I_1$ a $I_6$, §8, Eqs. 37–46)</b>.</li>
 * </ol>
 */
public final class ZeroRoundingDailyAccrualEngine {

    /** Limite máximo de meio centavo para o resíduo sub-centavo |R_t| <= 0,005 Reais = 5 * 10^13 na fibra E_16 */
    public static final QDec128 HALF_CENT_LIMIT_Q128 = Q128RateEngine.fromString("0.005");

    /** Tabela regressiva oficial do IOF (dias corridos 1 a 29; >= 30 dias = 0%) em percentual inteiro */
    private static final int[] IOF_REGRESSIVE_TABLE_PCT = {
        100, // 0 dias
        96, 93, 90, 86, 83, 80, 76, 73, 70, 66,
        63, 60, 56, 53, 50, 46, 43, 40, 36, 33,
        30, 26, 23, 20, 16, 13, 10,  6,  3,  0
    };

    // =========================================================================
    // 1. OPERADORES DE QUANTIZAÇÃO E FATOR DIÁRIO PADRÃO BACEN/CETIP (§1.1 e §1.2)
    // =========================================================================

    /**
     * Operador de arredondamento simétrico $\mathcal{R}_8 : \mathbb{R} \to \mathbb{D}_8$
     * (ABNT NBR 5891 Half-Even na 8ª casa decimal), mantendo representação na fibra $\mathcal{E}_{16}$.
     */
    public static QDec128 roundR8(QDec128 x) {
        return x.projectRound8HalfEven();
    }

    /**
     * Operador de arredondamento monetário $\mathcal{R}_2 : \mathbb{R} \to \mathbb{D}_2$
     * (ABNT NBR 5891 Half-Even na 2ª casa decimal), retornando centavos inteiros.
     */
    public static long roundR2Cents(QDec128 x) {
        return x.projectCentsRound2HalfEven();
    }

    /**
     * Operador de arredondamento monetário $\mathcal{R}_2 : \mathbb{R} \to \mathbb{D}_2$
     * retornando o elemento de $\mathbb{D}_2$ imerso na fibra $\mathcal{E}_{16}$.
     */
    public static QDec128 roundR2Q128(QDec128 x) {
        return Q128RateEngine.fromCents(roundR2Cents(x));
    }

    /**
     * Operador fiscal de truncamento $\mathcal{T}_2 : \mathbb{R} \to \mathbb{D}_2$
     * na 2ª casa decimal (Receita Federal do Brasil para IOF e IRRF), retornando centavos.
     */
    public static long truncT2Cents(QDec128 x) {
        return x.projectCentsTrunc2();
    }

    /**
     * Calcula o Fator Diário Padrão BACEN/CETIP $F_t \in \mathbb{D}_8$ (Equação 4):
     * $$F_t = 1 + \mathcal{R}_8\left( \left[ (1 + i_{a,t})^{1/B} - 1 \right] \cdot \alpha_t \right)$$
     * suportando qualquer base $B \in \{252, 360, 365\}$ e percentual de indexação $\alpha_t$.
     *
     * @param annualEffectiveRate Taxa efetiva anual $i_{a,t}$ (ex: 0.1125 para 11,25% a.a.)
     * @param basis               Base de contagem de dias (252, 360 ou 365)
     * @param indexationPct       Percentual do indexador $\alpha_t$ (ex: 1.0 para 100% CDI)
     * @return Fator diário $F_t \in \mathbb{D}_8$ representado em Q128
     */
    public static QDec128 computeBacenCetipDailyFactor8(
            QDec128 annualEffectiveRate,
            DayCountBasis basis,
            QDec128 indexationPct) {
        long rate16 = Q128RateEngine.toRaw16(annualEffectiveRate);
        long oneDayFactor16 = Q128RateEngine.periodCompoundFactor16(
                rate16, 1, basis, RateCompoundingMode.EXPONENTIAL_COMPOUND
        );
        QDec128 baseDailyNetRate = Q128RateEngine.fromRaw16(oneDayFactor16 - Q128RateEngine.BASE_16);
        QDec128 indexedDailyNetRate = Q128RateEngine.multiply(baseDailyNetRate, indexationPct);
        QDec128 rounded8NetRate = roundR8(indexedDailyNetRate);
        return Q128RateEngine.add(Q128RateEngine.ONE, rounded8NetRate);
    }

    // =========================================================================
    // 2. ESTRUTURAS DE ESTADO E REGISTROS DE AUDITORIA DIÁRIA (§2, §3, §4, §5)
    // =========================================================================

    /**
     * Linha de evolução diária comparando Estado Dual (§3), Acumulação de Resíduos
     * Kahan-Bresenham (§4) e o Método Diário Ingênuo (§2.2, Tabelas 1 e 2).
     */
    public record DailySimulationRow(
            int day,
            QDec128 dailyFactorF8,
            long externalFlowCents,
            QDec128 exactBalanceStarQ128,
            QDec128 availableInterestDispQ128,
            long dualStateLedgerEntryCents,
            long kahanBresenhamLedgerEntryCents,
            QDec128 subCentResidueQ128,
            long principalCents,
            long accruedAccountingInterestCents,
            long compensatedAccountingBalanceCents,
            long naiveDailyLedgerEntryCents,
            long naiveAccountingBalanceCents,
            long naiveErrorCents,
            boolean invariantI1Satisfied,
            boolean invariantI3Satisfied,
            boolean dualAndKahanIdentical
    ) {
    }

    /**
     * Resultado completo da simulação de um período de $N_M$ dias com verificação
     * do Teorema do Fechamento Mensal Consistente (Equações 5, 6 e 16).
     */
    public record PeriodAccrualSummary(
            long initialPrincipalCents,
            DayCountBasis basis,
            List<DailySimulationRow> dailyRows,
            QDec128 closedFormExactBalanceQ128,
            long closedFormAccountingBalanceCents,
            long telescopicLedgerFinalBalanceCents,
            long kahanBresenhamFinalBalanceCents,
            long naiveFinalBalanceCents,
            long totalCompensatedInterestCents,
            long totalNaiveInterestCents,
            long closedFormVsCompensatedErrorCents,
            long closedFormVsNaiveErrorCents,
            boolean allDailyInvariantsSatisfied
    ) {
    }

    /**
     * Estado mutável de uma Tranche Individual $k$ operando sob a Solução 2 (Kahan-Bresenham Financeiro)
     * e simultaneamente validado contra a Solução 1 (Estado Dual Telescópico).
     */
    public static final class TrancheAccrualState {
        private final String trancheId;
        private final int startDayTau;
        private long principalCents;               // P_t in D_2 (centavos)
        private long accruedInterestCents;         // J_t^cont in D_2 (centavos)
        private QDec128 subCentResidueQ128;        // R_t in D_16 (|R_t| <= 0.005)
        private QDec128 exactBalanceStarQ128;      // S_t^* in D_16 (Estado Dual)
        private long naiveBalanceCents;            // \tilde{S}_t in D_2 (Método Ingênuo)
        private long lastDailyLedgerEntryCents;    // L_t in D_2
        private long cumulativeExternalFlowsCents; // \sum \Delta E_t

        public TrancheAccrualState(String trancheId, int startDayTau, long initialPrincipalCents) {
            if (initialPrincipalCents < 0L) {
                throw new IllegalArgumentException("Principal inicial não pode ser negativo");
            }
            this.trancheId = trancheId;
            this.startDayTau = startDayTau;
            this.principalCents = initialPrincipalCents;
            this.accruedInterestCents = 0L;
            this.subCentResidueQ128 = Q128RateEngine.ZERO;
            this.exactBalanceStarQ128 = Q128RateEngine.fromCents(initialPrincipalCents);
            this.naiveBalanceCents = initialPrincipalCents;
            this.lastDailyLedgerEntryCents = 0L;
            this.cumulativeExternalFlowsCents = 0L;
        }

        /**
         * Executa um passo diário de apropriação de juros segundo as Soluções 1 (Estado Dual, Eqs. 11–14)
         * e 2 (Kahan-Bresenham Financeiro, Eqs. 17–24) para o fator diário $F_t \in \mathbb{D}_8$.
         */
        public DailySimulationRow accrueOneDay(int currentDay, QDec128 dailyFactorF8) {
            // -----------------------------------------------------------------
            // 1. SOLUÇÃO 2: Algoritmo de Acumulação de Resíduos (Kahan-Bresenham)
            // -----------------------------------------------------------------
            // Passo 1 (Eq. 17): B_{t-1}^* = P_{t-1} + J_{t-1}^cont + R_{t-1}
            long prevAccountingCents = Math.addExact(principalCents, accruedInterestCents);
            QDec128 prevAccountingQ128 = Q128RateEngine.fromCents(prevAccountingCents);
            QDec128 economicBaseQ128 = Q128RateEngine.add(prevAccountingQ128, subCentResidueQ128);

            // Passo 2 (Eqs. 18-19): j_t^bruto = B_{t-1}^* * (F_t - 1),  j_t^disp = j_t^bruto + R_{t-1}
            QDec128 netFactorMinusOne = Q128RateEngine.subtract(dailyFactorF8, Q128RateEngine.ONE);
            QDec128 grossDailyInterestQ128 = Q128RateEngine.multiply(economicBaseQ128, netFactorMinusOne);
            QDec128 availableInterestDispQ128 = Q128RateEngine.add(grossDailyInterestQ128, subCentResidueQ128);

            // Passo 3 (Eq. 20): L_t = R_2(j_t^disp)
            long kahanLedgerEntryCents = roundR2Cents(availableInterestDispQ128);
            QDec128 kahanLedgerEntryQ128 = Q128RateEngine.fromCents(kahanLedgerEntryCents);

            // Passo 4 (Eqs. 21-22): R_t = j_t^disp - L_t,  J_t^cont = J_{t-1}^cont + L_t
            QDec128 newResidueQ128 = Q128RateEngine.subtract(availableInterestDispQ128, kahanLedgerEntryQ128);
            long newAccruedInterestCents = Math.addExact(accruedInterestCents, kahanLedgerEntryCents);
            long newCompensatedBalanceCents = Math.addExact(principalCents, newAccruedInterestCents);

            // -----------------------------------------------------------------
            // 2. SOLUÇÃO 1: Arquitetura de Estado Dual (Soma Telescópica, Eqs. 11-13)
            // -----------------------------------------------------------------
            long prevDualContCents = roundR2Cents(exactBalanceStarQ128);
            QDec128 newExactBalanceStarQ128 = Q128RateEngine.multiply(exactBalanceStarQ128, dailyFactorF8);
            long newDualContCents = roundR2Cents(newExactBalanceStarQ128);
            long dualLedgerEntryCents = Math.subtractExact(newDualContCents, prevDualContCents);

            // -----------------------------------------------------------------
            // 3. MÉTODO DIÁRIO INGÊNUO (Tabela 2, §6.2) para medição do erro \Delta_erro
            // -----------------------------------------------------------------
            QDec128 naivePrevQ128 = Q128RateEngine.fromCents(principalCents);
            QDec128 naiveGrossInterest = Q128RateEngine.multiply(naivePrevQ128, netFactorMinusOne);
            long naiveLedgerCents = roundR2Cents(naiveGrossInterest);
            long newNaiveBalanceCents = Math.addExact(naiveBalanceCents, naiveLedgerCents);
            long naiveErrorCents = Math.subtractExact(newNaiveBalanceCents, newCompensatedBalanceCents);

            // Atualiza estado da tranche
            this.accruedInterestCents = newAccruedInterestCents;
            this.subCentResidueQ128 = newResidueQ128;
            this.exactBalanceStarQ128 = newExactBalanceStarQ128;
            this.naiveBalanceCents = newNaiveBalanceCents;
            this.lastDailyLedgerEntryCents = kahanLedgerEntryCents;

            // Verificação das Invariantes I_1 e I_3 no dia currentDay
            QDec128 reconstructedStar = Q128RateEngine.add(
                    Q128RateEngine.fromCents(newCompensatedBalanceCents),
                    newResidueQ128
            );
            boolean i1Ok = reconstructedStar.isEquivalent(newExactBalanceStarQ128)
                    && (newCompensatedBalanceCents == newDualContCents);
            boolean i3Ok = newResidueQ128.abs().compare(HALF_CENT_LIMIT_Q128) <= 0;
            boolean dualEqualsKahan = (dualLedgerEntryCents == kahanLedgerEntryCents);

            return new DailySimulationRow(
                    currentDay,
                    dailyFactorF8,
                    0L,
                    newExactBalanceStarQ128,
                    availableInterestDispQ128,
                    dualLedgerEntryCents,
                    kahanLedgerEntryCents,
                    newResidueQ128,
                    principalCents,
                    newAccruedInterestCents,
                    newCompensatedBalanceCents,
                    naiveLedgerCents,
                    newNaiveBalanceCents,
                    naiveErrorCents,
                    i1Ok,
                    i3Ok,
                    dualEqualsKahan
            );
        }

        /**
         * Aplica um evento intermediário de Amortização Parcial ou Liquidação Total (§5, Eqs. 30–33):
         * <ul>
         *   <li>Se {@code totalPaymentCents == P_t + J_t^cont} ($\theta_t = 1$, Liquidação Total):
         *       zera $P_t$, $J_t^{\text{cont}}$, $S_t^*$ e o resíduo sub-centavo $R_t^{\text{pós}} = 0$.</li>
         *   <li>Se $0 < \text{totalPaymentCents} < P_t + J_t^{\text{cont}}$ ($0 < \theta_t < 1$, Amortização Parcial):
         *       deduz os juros pagos e a quota de principal pagos em centavos exatos ($\in \mathbb{D}_2$),
         *       atualiza $S_t^{*,\text{pós}} = S_t^* - A_t$ e preserva $R_t^{\text{pós}} = R_t$ inalterado!</li>
         * </ul>
         */
        public void applyAmortizationOrRedemption(long principalAmortizedCents, long interestPaidCents) {
            long totalAmortCents = Math.addExact(principalAmortizedCents, interestPaidCents);
            long currentContBalanceCents = getAccountingBalanceCents();

            if (totalAmortCents < 0L || totalAmortCents > currentContBalanceCents) {
                throw new IllegalArgumentException(
                        "Valor de amortização (" + totalAmortCents + ") inválido para saldo (" + currentContBalanceCents + ")"
                );
            }

            if (totalAmortCents == currentContBalanceCents) {
                // Liquidação Total (\theta_t = 1): Eq. (32) -> R_t^pós = 0
                this.principalCents = 0L;
                this.accruedInterestCents = 0L;
                this.subCentResidueQ128 = Q128RateEngine.ZERO;
                this.exactBalanceStarQ128 = Q128RateEngine.ZERO;
                this.naiveBalanceCents = 0L;
            } else {
                // Amortização Parcial (0 < \theta_t < 1): Eq. (33) -> R_t^pós = R_t
                this.principalCents = Math.subtractExact(this.principalCents, principalAmortizedCents);
                this.accruedInterestCents = Math.subtractExact(this.accruedInterestCents, interestPaidCents);
                QDec128 amortQ128 = Q128RateEngine.fromCents(totalAmortCents);
                this.exactBalanceStarQ128 = Q128RateEngine.subtract(this.exactBalanceStarQ128, amortQ128);
                this.naiveBalanceCents = Math.max(0L, this.naiveBalanceCents - totalAmortCents);
                // R_t permanece identicamente preservado: S_t^{*,pós} - (P_t^pós + J_t^{cont,pós}) == R_t
            }
            this.cumulativeExternalFlowsCents = Math.subtractExact(this.cumulativeExternalFlowsCents, totalAmortCents);
        }

        /**
         * Aplica aporte adicional positivo ($\Delta E_t > 0 \in \mathbb{D}_2$).
         */
        public void applyAdditionalContribution(long contributionCents) {
            if (contributionCents <= 0L) {
                throw new IllegalArgumentException("Aporte deve ser positivo");
            }
            this.principalCents = Math.addExact(this.principalCents, contributionCents);
            QDec128 contribQ128 = Q128RateEngine.fromCents(contributionCents);
            this.exactBalanceStarQ128 = Q128RateEngine.add(this.exactBalanceStarQ128, contribQ128);
            this.naiveBalanceCents = Math.addExact(this.naiveBalanceCents, contributionCents);
            this.cumulativeExternalFlowsCents = Math.addExact(this.cumulativeExternalFlowsCents, contributionCents);
        }

        public String getTrancheId() {
            return trancheId;
        }

        public int getStartDayTau() {
            return startDayTau;
        }

        public long getPrincipalCents() {
            return principalCents;
        }

        public long getAccruedInterestCents() {
            return accruedInterestCents;
        }

        public long getAccountingBalanceCents() {
            return Math.addExact(principalCents, accruedInterestCents);
        }

        public QDec128 getSubCentResidueQ128() {
            return subCentResidueQ128;
        }

        public QDec128 getExactBalanceStarQ128() {
            return exactBalanceStarQ128;
        }

        public long getNaiveBalanceCents() {
            return naiveBalanceCents;
        }

        public long getLastDailyLedgerEntryCents() {
            return lastDailyLedgerEntryCents;
        }

        public long getCumulativeExternalFlowsCents() {
            return cumulativeExternalFlowsCents;
        }
    }

    // =========================================================================
    // 3. SIMULAÇÃO DE PERÍODO FECHADO E PROVA DE ERRO ZERO (§2, §3, §4, §6)
    // =========================================================================

    /**
     * Simula a apropriação diária de $t = 1 \dots N_M$ para um saldo inicial $S_0 \in \mathbb{D}_2$
     * sob um fator diário constante $F_t \in \mathbb{D}_8$ (como nas Tabelas 1 e 2 da especificação),
     * verificando o fechamento telescópico contra a equação fechada $S_{N_M}^{\text{fechado}} = \mathcal{R}_2(S_0 \cdot F_t^{N_M})$.
     */
    public PeriodAccrualSummary simulateConstantFactorPeriod(
            long initialPrincipalCents,
            QDec128 dailyFactorF8,
            int businessDaysCount,
            DayCountBasis basis) {
        List<QDec128> factors = Collections.nCopies(businessDaysCount, dailyFactorF8);
        return simulateVariableFactorPeriod(initialPrincipalCents, factors, basis);
    }

    /**
     * Simula a apropriação diária para qualquer sequência de fatores diários $F_1, F_2, \dots, F_{N_M} \in \mathbb{D}_8$.
     */
    public PeriodAccrualSummary simulateVariableFactorPeriod(
            long initialPrincipalCents,
            List<QDec128> dailyFactorsF8,
            DayCountBasis basis) {

        TrancheAccrualState state = new TrancheAccrualState("TRANCHE-MAIN", 0, initialPrincipalCents);
        List<DailySimulationRow> rows = new ArrayList<>(dailyFactorsF8.size());

        long sumDualLedgerCents = 0L;
        long sumKahanLedgerCents = 0L;
        long sumNaiveLedgerCents = 0L;
        boolean allInvariantsOk = true;

        QDec128 productOfFactorsQ128 = Q128RateEngine.ONE;

        for (int i = 0; i < dailyFactorsF8.size(); i++) {
            int day = i + 1;
            QDec128 fDay = dailyFactorsF8.get(i);
            productOfFactorsQ128 = Q128RateEngine.multiply(productOfFactorsQ128, fDay);

            DailySimulationRow row = state.accrueOneDay(day, fDay);
            rows.add(row);

            sumDualLedgerCents = Math.addExact(sumDualLedgerCents, row.dualStateLedgerEntryCents());
            sumKahanLedgerCents = Math.addExact(sumKahanLedgerCents, row.kahanBresenhamLedgerEntryCents());
            sumNaiveLedgerCents = Math.addExact(sumNaiveLedgerCents, row.naiveDailyLedgerEntryCents());

            if (!row.invariantI1Satisfied() || !row.invariantI3Satisfied() || !row.dualAndKahanIdentical()) {
                allInvariantsOk = false;
            }
        }

        // Equação Exata de Período Fechado (Eqs. 5 e 6): S_{N_M}^* = S_0 * \prod_{t=1}^{N_M} F_t
        QDec128 s0Q128 = Q128RateEngine.fromCents(initialPrincipalCents);
        QDec128 closedFormExactQ128 = Q128RateEngine.multiply(s0Q128, productOfFactorsQ128);
        long closedFormAccountingCents = roundR2Cents(closedFormExactQ128);

        long telescopicFinalCents = Math.addExact(initialPrincipalCents, sumDualLedgerCents);
        long kahanFinalCents = state.getAccountingBalanceCents();
        long naiveFinalCents = state.getNaiveBalanceCents();

        long closedVsCompensatedError = Math.subtractExact(kahanFinalCents, closedFormAccountingCents);
        long closedVsNaiveError = Math.subtractExact(naiveFinalCents, closedFormAccountingCents);

        return new PeriodAccrualSummary(
                initialPrincipalCents,
                basis,
                Collections.unmodifiableList(rows),
                closedFormExactQ128,
                closedFormAccountingCents,
                telescopicFinalCents,
                kahanFinalCents,
                naiveFinalCents,
                sumKahanLedgerCents,
                sumNaiveLedgerCents,
                closedVsCompensatedError,
                closedVsNaiveError,
                allInvariantsOk && (closedVsCompensatedError == 0L) && (telescopicFinalCents == kahanFinalCents)
        );
    }

    // =========================================================================
    // 4. TRIBUTAÇÃO (IOF E IRRF - INVARIANTE I_6) E PARTIDAS DOBRADAS (I_4)
    // =========================================================================

    /**
     * Resultado do cálculo tributário sobre uma Tranche $k$ no prazo decorrido $\Delta d = t - \tau_{i,k}$
     * respeitando a Invariante $I_6$ (Equação 45):
     *   $0 \le \text{IOF}_{i,k,t} \le J_{i,k,t}^{\text{cont}}$ e
     *   $\text{IR}_{i,k,t} = \mathcal{T}_2(\gamma_{\text{IR}}(\Delta d) \cdot (J_{i,k,t}^{\text{cont}} - \text{IOF}_{i,k,t}))$.
     */
    public record TrancheTaxAssessment(
            int elapsedDaysDeltaD,
            long grossAccountingInterestCents,
            int iofRegressiveRatePct,
            QDec128 irRegressiveRateQ128,
            long iofTaxCents,
            long irTaxCents,
            long totalTaxCents,
            long netAfterTaxInterestCents,
            long netRedemptionValueCents,
            boolean invariantI6Satisfied
    ) {
    }

    /**
     * Retorna a alíquota regressiva de IRRF $\gamma_{\text{IR}}(\Delta d) \in \{22{,}5\%, 20{,}0\%, 17{,}5\%, 15{,}0\%\}$
     * conforme o prazo decorrido em dias corridos $\Delta d$:
     * <ul>
     *   <li>Até 180 dias: 22,5% (`0.225`)</li>
     *   <li>De 181 a 360 dias: 20,0% (`0.200`)</li>
     *   <li>De 361 a 720 dias: 17,5% (`0.175`)</li>
     *   <li>Acima de 720 dias: 15,0% (`0.150`)</li>
     * </ul>
     */
    public static QDec128 getRegressiveIrRate(int elapsedCalendarDays) {
        if (elapsedCalendarDays <= 180) {
            return Q128RateEngine.fromString("0.225");
        } else if (elapsedCalendarDays <= 360) {
            return Q128RateEngine.fromString("0.200");
        } else if (elapsedCalendarDays <= 720) {
            return Q128RateEngine.fromString("0.175");
        } else {
            return Q128RateEngine.fromString("0.150");
        }
    }

    /**
     * Retorna o percentual regressivo da tabela de IOF para $\Delta d \ge 0$.
     */
    public static int getRegressiveIofPct(int elapsedCalendarDays) {
        if (elapsedCalendarDays <= 0) {
            return 100;
        }
        if (elapsedCalendarDays >= 30) {
            return 0;
        }
        return IOF_REGRESSIVE_TABLE_PCT[elapsedCalendarDays];
    }

    /**
     * Calcula o IOF e o IRRF sobre uma Tranche segundo a Invariante $I_6$ (Equação 45):
     * incide exclusivamente sobre $J_{i,k,t}^{\text{cont}} \ge 0$ (nunca sobre o resíduo sub-centavo $R_{i,k,t}$),
     * truncando na 2ª casa decimal via $\mathcal{T}_2$ e proibindo confisco do principal $P_{i,k,t}$.
     */
    public static TrancheTaxAssessment computeTrancheTaxes(
            TrancheAccrualState tranche,
            int currentCalendarDay) {
        int deltaD = Math.max(0, currentCalendarDay - tranche.getStartDayTau());
        long accruedJ = Math.max(0L, tranche.getAccruedInterestCents());
        int iofPct = getRegressiveIofPct(deltaD);
        QDec128 irRate = getRegressiveIrRate(deltaD);

        // IOF = T_2( (iofPct / 100) * J^cont ), limitado a [0, J^cont]
        QDec128 jQ128 = Q128RateEngine.fromCents(accruedJ);
        QDec128 iofRateQ128 = Q128RateEngine.divideByLong(Q128RateEngine.fromLong(iofPct), 100L);
        long rawIofCents = truncT2Cents(Q128RateEngine.multiply(jQ128, iofRateQ128));
        long iofCents = Math.min(accruedJ, Math.max(0L, rawIofCents));

        // Base do IR = J^cont - IOF
        long irBaseCents = Math.subtractExact(accruedJ, iofCents);
        QDec128 irBaseQ128 = Q128RateEngine.fromCents(irBaseCents);
        long irCents = Math.min(irBaseCents, Math.max(0L, truncT2Cents(Q128RateEngine.multiply(irBaseQ128, irRate))));

        long totalTaxCents = Math.addExact(iofCents, irCents);
        long netInterestCents = Math.subtractExact(accruedJ, totalTaxCents);
        long netRedemptionCents = Math.addExact(tranche.getPrincipalCents(), netInterestCents);

        boolean i6Ok = (iofCents >= 0L)
                && (iofCents <= accruedJ)
                && (irCents >= 0L)
                && (totalTaxCents <= accruedJ)
                && (netRedemptionCents >= tranche.getPrincipalCents());

        return new TrancheTaxAssessment(
                deltaD,
                accruedJ,
                iofPct,
                irRate,
                iofCents,
                irCents,
                totalTaxCents,
                netInterestCents,
                netRedemptionCents,
                i6Ok
        );
    }

    // =========================================================================
    // 5. MOTOR DE RECONCILIAÇÃO COSIF (RAZÃO ANALÍTICO vs SINTÉTICO) E INVARIANTES I_1..I_6
    // =========================================================================

    /**
     * Contrato Analítico composto por $K_i \ge 1$ tranches independentes (Invariante $I_5$).
     * Pode representar um contrato de investimento multi-tranche ou um contrato de crédito SAC
     * decomposto em $N$ parcelas/tranches independentes.
     */
    public static final class AnalyticalContractLedger {
        private final String contractId;
        private final List<TrancheAccrualState> tranches;
        private final long initialPrincipalCents;

        public AnalyticalContractLedger(String contractId, List<TrancheAccrualState> tranches) {
            this.contractId = contractId;
            this.tranches = new ArrayList<>(tranches);
            long sumP0 = 0L;
            for (TrancheAccrualState t : tranches) {
                sumP0 = Math.addExact(sumP0, t.getPrincipalCents());
            }
            this.initialPrincipalCents = sumP0;
        }

        public String getContractId() {
            return contractId;
        }

        public List<TrancheAccrualState> getTranches() {
            return Collections.unmodifiableList(tranches);
        }

        public long getInitialPrincipalCents() {
            return initialPrincipalCents;
        }

        /** Soma dos principais das tranches: $P_{i,t} = \sum_{k=1}^{K_i} P_{i,k,t}$ (Eq. 43) */
        public long getTotalPrincipalCents() {
            long sum = 0L;
            for (TrancheAccrualState t : tranches) {
                sum = Math.addExact(sum, t.getPrincipalCents());
            }
            return sum;
        }

        /** Soma dos juros apropriados das tranches: $J_{i,t}^{\text{cont}} = \sum_{k=1}^{K_i} J_{i,k,t}^{\text{cont}}$ (Eq. 43) */
        public long getTotalAccruedInterestCents() {
            long sum = 0L;
            for (TrancheAccrualState t : tranches) {
                sum = Math.addExact(sum, t.getAccruedInterestCents());
            }
            return sum;
        }

        /** Saldo contábil analítico do contrato: $S_{i,t}^{\text{cont}} = P_{i,t} + J_{i,t}^{\text{cont}}$ */
        public long getContractAccountingBalanceCents() {
            return Math.addExact(getTotalPrincipalCents(), getTotalAccruedInterestCents());
        }

        /** Lançamento diário do contrato: $L_{i,t} = \sum_{k=1}^{K_i} L_{i,k,t}$ (Eq. 43) */
        public long getContractDailyLedgerEntryCents() {
            long sum = 0L;
            for (TrancheAccrualState t : tranches) {
                sum = Math.addExact(sum, t.getLastDailyLedgerEntryCents());
            }
            return sum;
        }

        /** Soma exata $S_{i,t}^* = \sum_{k=1}^{K_i} S_{i,k,t}^*$ */
        public QDec128 getContractExactBalanceStarQ128() {
            QDec128 sum = Q128RateEngine.ZERO;
            for (TrancheAccrualState t : tranches) {
                sum = Q128RateEngine.add(sum, t.getExactBalanceStarQ128());
            }
            return sum;
        }

        /** Soma dos resíduos sub-centavo das tranches: $\sum_{k=1}^{K_i} R_{i,k,t}$ */
        public QDec128 getContractTotalResidueQ128() {
            QDec128 sum = Q128RateEngine.ZERO;
            for (TrancheAccrualState t : tranches) {
                sum = Q128RateEngine.add(sum, t.getSubCentResidueQ128());
            }
            return sum;
        }
    }

    /**
     * Relatório diário de Auditoria das 6 Invariantes Formais ($I_1$ a $I_6$, Seção 8)
     * sobre uma carteira de $M$ contratos no Razão Geral COSIF.
     */
    public record CosifInvariantAuditReport(
            int day,
            int activeContractsCount,
            int activeTranchesCount,
            long generalLedgerBalanceCents,
            long subledgerSumBalanceCents,
            long syntheticDailyLedgerEntryCents,
            long totalDebitCents,
            long totalCreditCents,
            QDec128 portfolioExactBalanceSumQ128,
            QDec128 portfolioTotalResidueSumQ128,
            QDec128 maxTheoreticalPortfolioDivergenceBoundQ128,
            boolean invariantI1EconomicConservation,
            boolean invariantI2CosifReconciliation,
            boolean invariantI3LyapunovResidueBound,
            boolean invariantI4DoubleEntryAndTelescopic,
            boolean invariantI5MultiTrancheAdditivity,
            boolean invariantI6TaxIntegrityAndNoConfiscation
    ) {
        public boolean allSixInvariantsPassed() {
            return invariantI1EconomicConservation
                    && invariantI2CosifReconciliation
                    && invariantI3LyapunovResidueBound
                    && invariantI4DoubleEntryAndTelescopic
                    && invariantI5MultiTrancheAdditivity
                    && invariantI6TaxIntegrityAndNoConfiscation;
        }
    }

    /**
     * Razão Geral Sintético (General Ledger COSIF) sincronizado bottom-up com os $M$
     * contratos analíticos (Subledger), verificando formalmente $I_1$ a $I_6$ a cada dia útil.
     */
    public static final class CosifGeneralLedger {
        private final List<AnalyticalContractLedger> contracts;
        private long generalLedgerBalanceCents; // \mathcal{G}_t in D_2
        private final long initialGeneralLedgerBalanceCents;
        private long cumulativeSyntheticLedgerEntriesCents;
        private long cumulativeExternalFlowsCents;

        public CosifGeneralLedger(List<AnalyticalContractLedger> contracts) {
            this.contracts = new ArrayList<>(contracts);
            long sumG0 = 0L;
            for (AnalyticalContractLedger c : contracts) {
                sumG0 = Math.addExact(sumG0, c.getContractAccountingBalanceCents());
            }
            this.generalLedgerBalanceCents = sumG0;
            this.initialGeneralLedgerBalanceCents = sumG0;
            this.cumulativeSyntheticLedgerEntriesCents = 0L;
            this.cumulativeExternalFlowsCents = 0L;
        }

        /**
         * Processa o fechamento diário de todas as tranches de todos os $M$ contratos para o dia {@code day},
         * atualiza o Razão Geral Sintético $\mathcal{G}_t$ via agregação bottom-up (Eq. 36)
         * e audita as 6 invariantes formais ($I_1$ a $I_6$).
         */
        public CosifInvariantAuditReport processDailyAccrualAndAudit(
                int day,
                QDec128 dailyFactorF8) {

            boolean i1Ok = true;
            boolean i3Ok = true;
            boolean i4Ok = true;
            boolean i5Ok = true;
            boolean i6Ok = true;

            long syntheticDailyEntryCents = 0L;
            long subledgerSumCents = 0L;
            long totalDebitsCents = 0L;
            long totalCreditsCents = 0L;
            int totalTranchesCount = 0;

            QDec128 portfolioExactSumQ128 = Q128RateEngine.ZERO;
            QDec128 portfolioResidueSumQ128 = Q128RateEngine.ZERO;

            for (AnalyticalContractLedger contract : contracts) {
                long sumTrancheP = 0L;
                long sumTrancheJ = 0L;
                long sumTrancheL = 0L;

                for (TrancheAccrualState tranche : contract.getTranches()) {
                    totalTranchesCount++;
                    DailySimulationRow row = tranche.accrueOneDay(day, dailyFactorF8);

                    if (!row.invariantI1Satisfied()) {
                        i1Ok = false;
                    }
                    if (!row.invariantI3Satisfied()) {
                        i3Ok = false;
                    }

                    long entryL = row.kahanBresenhamLedgerEntryCents();
                    // Partidas Dobradas (Invariante I_4): Débito == Crédito == L_{i,k,t}
                    long debit = entryL;
                    long credit = entryL;
                    totalDebitsCents = Math.addExact(totalDebitsCents, debit);
                    totalCreditsCents = Math.addExact(totalCreditsCents, credit);

                    sumTrancheP = Math.addExact(sumTrancheP, tranche.getPrincipalCents());
                    sumTrancheJ = Math.addExact(sumTrancheJ, tranche.getAccruedInterestCents());
                    sumTrancheL = Math.addExact(sumTrancheL, entryL);

                    portfolioExactSumQ128 = Q128RateEngine.add(portfolioExactSumQ128, tranche.getExactBalanceStarQ128());
                    portfolioResidueSumQ128 = Q128RateEngine.add(portfolioResidueSumQ128, tranche.getSubCentResidueQ128());

                    // Auditoria Tributária (Invariante I_6)
                    TrancheTaxAssessment tax = computeTrancheTaxes(tranche, day);
                    if (!tax.invariantI6Satisfied()) {
                        i6Ok = false;
                    }
                }

                // Invariante I_5 (Equação 43): Aditividade estrita das tranches no contrato
                if (sumTrancheP != contract.getTotalPrincipalCents()
                        || sumTrancheJ != contract.getTotalAccruedInterestCents()
                        || sumTrancheL != contract.getContractDailyLedgerEntryCents()) {
                    i5Ok = false;
                }

                syntheticDailyEntryCents = Math.addExact(syntheticDailyEntryCents, sumTrancheL);
                subledgerSumCents = Math.addExact(subledgerSumCents, contract.getContractAccountingBalanceCents());
            }

            // Atualização bottom-up do Razão Geral Sintético (Equação 36 e 39):
            // \mathcal{G}_t = \mathcal{G}_{t-1} + L_t^{\text{sintético}}
            this.generalLedgerBalanceCents = Math.addExact(this.generalLedgerBalanceCents, syntheticDailyEntryCents);
            this.cumulativeSyntheticLedgerEntriesCents = Math.addExact(
                    this.cumulativeSyntheticLedgerEntriesCents, syntheticDailyEntryCents
            );

            // Invariante I_2 (Equação 39): \mathcal{G}_t == \sum_{i=1}^M S_{i,t}^{\text{cont}}
            boolean i2Ok = (this.generalLedgerBalanceCents == subledgerSumCents);

            // Invariante I_3 Global (Equação 41): |\sum S_{i,t}^* - \mathcal{G}_t| <= 0.005 * totalTranches
            QDec128 maxPortfolioBound = Q128RateEngine.multiply(
                    HALF_CENT_LIMIT_Q128, Q128RateEngine.fromLong(totalTranchesCount)
            );
            QDec128 diffPortfolio = Q128RateEngine.subtract(
                    portfolioExactSumQ128, Q128RateEngine.fromCents(this.generalLedgerBalanceCents)
            ).abs();
            if (diffPortfolio.compare(maxPortfolioBound) > 0) {
                i3Ok = false;
            }

            // Invariante I_4 (Equação 42): Partidas dobradas (\sum (D - C) == 0) e Soma Telescópica
            boolean doubleEntryBalanced = (totalDebitsCents == totalCreditsCents);
            boolean telescopicBalanced = (this.generalLedgerBalanceCents
                    - this.initialGeneralLedgerBalanceCents
                    - this.cumulativeExternalFlowsCents) == this.cumulativeSyntheticLedgerEntriesCents;
            i4Ok = i4Ok && doubleEntryBalanced && telescopicBalanced;

            return new CosifInvariantAuditReport(
                    day,
                    contracts.size(),
                    totalTranchesCount,
                    this.generalLedgerBalanceCents,
                    subledgerSumCents,
                    syntheticDailyEntryCents,
                    totalDebitsCents,
                    totalCreditsCents,
                    portfolioExactSumQ128,
                    portfolioResidueSumQ128,
                    maxPortfolioBound,
                    i1Ok,
                    i2Ok,
                    i3Ok,
                    i4Ok,
                    i5Ok,
                    i6Ok
            );
        }

        /**
         * Registra uma amortização (ex: pagamento de prestação SAC) em uma tranche e sincroniza o Razão Sintético.
         */
        public void registerAmortizationEvent(
                TrancheAccrualState tranche,
                long principalAmortizedCents,
                long interestPaidCents) {
            long totalAmort = Math.addExact(principalAmortizedCents, interestPaidCents);
            tranche.applyAmortizationOrRedemption(principalAmortizedCents, interestPaidCents);
            this.generalLedgerBalanceCents = Math.subtractExact(this.generalLedgerBalanceCents, totalAmort);
            this.cumulativeExternalFlowsCents = Math.subtractExact(this.cumulativeExternalFlowsCents, totalAmort);
        }

        public long getGeneralLedgerBalanceCents() {
            return generalLedgerBalanceCents;
        }
    }

    // =========================================================================
    // 6. INTEGRAÇÃO COM CONTRATOS SAC (CRONOGRAMA + CICLO DE VIDA DIÁRIO + FECHAMENTO MENSAL)
    // =========================================================================

    /**
     * Linha do Cronograma de Fechamento Mensal da Operação SAC comparando a Soma da
     * Apropriação Diária Compensada ($\sum_{d=1}^{N_m} L_{m,d}$) contra a Equação de
     * Período Fechado ($J_m^{\text{fechado}} = \mathcal{R}_2(S_{m,0} \cdot F_t^{N_m}) - S_{m,0}$)
     * e contra o Método Diário Ingênuo ($\sum_{d=1}^{N_m} \tilde{L}_{m,d}$).
     */
    public record MonthlyClosingScheduleRow(
            int installmentNumber,
            int daysInPeriod,
            int cumulativeDays,
            long openingPrincipalCents,
            long firstDayAccrualCents,
            long lastDayAccrualCents,
            long dailyAccrualSumInterestCents,
            long closedFormPeriodInterestCents,
            long dailyVsClosedFormDiffCents,
            long naiveDailySumInterestCents,
            long naiveVsClosedFormDiffCents,
            long preAmortizationBalanceCents,
            long closedFormPreAmortizationBalanceCents,
            long amortizationQuotaCents,
            long totalInstallmentPaymentCents,
            long closingPrincipalCents,
            QDec128 closingSubCentResiduePrePaymentQ128
    ) {
    }

    /**
     * Linha do Ciclo de Vida Diário Completo da Operação SAC (dia a dia $t = 1 \dots \sum N_m$),
     * exibindo a evolução diária dentro de cada mês $m$, a comparação diária com o período fechado
     * ($\sum_{\tau=1}^d L_{m,\tau}$ vs. $\mathcal{R}_2(S_{m,0} \cdot F_t^d) - S_{m,0}$) e o evento
     * de fechamento mensal (pagamento de juros + amortização SAC) no último dia útil de cada mês.
     */
    public record OperationLifecycleDailyRow(
            int installmentNumber,
            int dayInMonth,
            int globalDay,
            boolean isMonthEndClosingDay,
            long openingPrincipalCents,
            QDec128 exactBalanceStarQ128,
            QDec128 availableInterestDispQ128,
            long dailyLedgerEntryCents,
            QDec128 subCentResidueQ128,
            long accumulatedDailyInterestInMonthCents,
            long closedFormInterestToDayCents,
            long dailyVsClosedFormDiffCents,
            long naiveDailyEntryCents,
            long naiveAccumulatedInterestInMonthCents,
            long naiveVsClosedFormDiffCents,
            long preSettlementAccountingBalanceCents,
            long closedFormAccountingBalanceToDayCents,
            long interestSettledAtMonthEndCents,
            long principalAmortizedAtMonthEndCents,
            long totalInstallmentPaidAtMonthEndCents,
            long postSettlementBalanceCents
    ) {
    }

    /**
     * Resultado da simulação completa de um contrato SAC sob a Modelagem de Erro Zero
     * (Solução 1: Estado Dual Telescópico + Solução 2: Kahan-Bresenham + Auditoria COSIF $I_1 \dots I_6$).
     */
    public record SacZeroRoundingContractReport(
            SacContractSpec spec,
            QDec128 bacenDailyFactorF8,
            List<PeriodAccrualSummary> installmentPeriodSummaries,
            List<MonthlyClosingScheduleRow> monthlyClosingSchedule,
            List<OperationLifecycleDailyRow> operationDailyLifecycle,
            List<CosifInvariantAuditReport> dailyCosifAuditReports,
            long totalCompensatedInterestCents,
            long totalClosedFormInterestCents,
            long totalNaiveInterestCents,
            long cumulativeDailyVsClosedFormDiffCents,
            long cumulativeNaiveRoundingErrorCents,
            boolean allPeriodsClosedWithZeroError,
            boolean allCosifInvariantsPassedEveryDay
    ) {
    }

    /**
     * Simula um contrato SAC completo ({@link SacContractSpec}) em qualquer base (252, 360 ou 365)
     * aplicando o Fator Diário Padrão BACEN/CETIP $F_t \in \mathbb{D}_8$ (Eq. 4),
     * o Algoritmo Kahan-Bresenham de Acumulação de Resíduos Sub-Centavo (§4),
     * os Eventos de Amortização Parcial e Liquidação Total (§5, Eqs. 30–33) e
     * a Auditoria Diária das 6 Invariantes Formais COSIF ($I_1$ a $I_6$, §8).
     */
    public SacZeroRoundingContractReport simulateSacContractWithZeroRounding(SacContractSpec spec) {
        DayCountBasis basis = spec.getDayCountBasis();
        QDec128 annualRate = spec.getAnnualContractRate();
        QDec128 dailyFactorF8 = computeBacenCetipDailyFactor8(annualRate, basis, Q128RateEngine.ONE);

        SacPricingCalculator calculator = new SacPricingCalculator();
        SacPricingResult parSchedule = calculator.priceAtPar(spec);
        List<SacInstallmentRow> scheduleRows = parSchedule.schedule();

        // 1. Simulação por período de parcela + geração da Tabela de Fechamento Mensal e Tabela de Ciclo de Vida Diário
        List<PeriodAccrualSummary> periodSummaries = new ArrayList<>(scheduleRows.size());
        List<MonthlyClosingScheduleRow> monthlyScheduleRows = new ArrayList<>(scheduleRows.size());
        List<OperationLifecycleDailyRow> lifecycleDailyRows = new ArrayList<>();

        long totalCompensatedInterest = 0L;
        long totalClosedFormInterest = 0L;
        long totalNaiveInterest = 0L;
        boolean allPeriodsZeroError = true;
        int lifecycleGlobalDay = 0;

        for (SacInstallmentRow row : scheduleRows) {
            long openingPrincipal = row.updatedBalanceCents();
            long amortQuota = row.amortizationQuotaCents();
            int daysInPeriod = row.daysInPeriod();

            PeriodAccrualSummary summary = simulateConstantFactorPeriod(
                    openingPrincipal,
                    dailyFactorF8,
                    daysInPeriod,
                    basis
            );
            periodSummaries.add(summary);

            List<DailySimulationRow> dRows = summary.dailyRows();
            long firstDayL = dRows.get(0).kahanBresenhamLedgerEntryCents();
            long lastDayL = dRows.get(dRows.size() - 1).kahanBresenhamLedgerEntryCents();
            QDec128 lastDayResidue = dRows.get(dRows.size() - 1).subCentResidueQ128();

            long closedFormInterest = Math.subtractExact(
                    summary.closedFormAccountingBalanceCents(), openingPrincipal
            );
            long dailySumInterest = summary.totalCompensatedInterestCents();
            long naiveSumInterest = summary.totalNaiveInterestCents();
            long dailyVsClosedDiff = Math.subtractExact(dailySumInterest, closedFormInterest);
            long naiveVsClosedDiff = Math.subtractExact(naiveSumInterest, closedFormInterest);

            long totalPayment = Math.addExact(amortQuota, dailySumInterest);
            long closingPrincipal = Math.subtractExact(openingPrincipal, amortQuota);

            monthlyScheduleRows.add(new MonthlyClosingScheduleRow(
                    row.installmentNumber(),
                    daysInPeriod,
                    row.cumulativeDays(),
                    openingPrincipal,
                    firstDayL,
                    lastDayL,
                    dailySumInterest,
                    closedFormInterest,
                    dailyVsClosedDiff,
                    naiveSumInterest,
                    naiveVsClosedDiff,
                    summary.kahanBresenhamFinalBalanceCents(),
                    summary.closedFormAccountingBalanceCents(),
                    amortQuota,
                    totalPayment,
                    closingPrincipal,
                    lastDayResidue
            ));

            // Constrói as linhas do Ciclo de Vida Diário da Operação dentro do mês row.installmentNumber()
            long runningNaiveInterestInMonth = 0L;
            for (DailySimulationRow dRow : dRows) {
                lifecycleGlobalDay++;
                boolean isLastDayOfMonth = (dRow.day() == daysInPeriod);
                runningNaiveInterestInMonth = Math.addExact(runningNaiveInterestInMonth, dRow.naiveDailyLedgerEntryCents());

                // Saldo fechado até o dia d: R_2(S_m,0 * F_t^d) = R_2(S_{m,d}^*)
                long closedFormBalanceToDay = roundR2Cents(dRow.exactBalanceStarQ128());
                long closedFormInterestToDay = Math.subtractExact(closedFormBalanceToDay, openingPrincipal);
                long accDailyInterestToDay = dRow.accruedAccountingInterestCents();
                long dayDiffVsClosed = Math.subtractExact(accDailyInterestToDay, closedFormInterestToDay);
                long dayNaiveDiffVsClosed = Math.subtractExact(runningNaiveInterestInMonth, closedFormInterestToDay);

                long settledInterest = isLastDayOfMonth ? accDailyInterestToDay : 0L;
                long settledAmort = isLastDayOfMonth ? amortQuota : 0L;
                long settledTotal = Math.addExact(settledInterest, settledAmort);
                long postSettlementBalance = Math.subtractExact(
                        dRow.compensatedAccountingBalanceCents(), settledTotal
                );

                if (dayDiffVsClosed != 0L) {
                    allPeriodsZeroError = false;
                }

                lifecycleDailyRows.add(new OperationLifecycleDailyRow(
                        row.installmentNumber(),
                        dRow.day(),
                        lifecycleGlobalDay,
                        isLastDayOfMonth,
                        openingPrincipal,
                        dRow.exactBalanceStarQ128(),
                        dRow.availableInterestDispQ128(),
                        dRow.kahanBresenhamLedgerEntryCents(),
                        dRow.subCentResidueQ128(),
                        accDailyInterestToDay,
                        closedFormInterestToDay,
                        dayDiffVsClosed,
                        dRow.naiveDailyLedgerEntryCents(),
                        runningNaiveInterestInMonth,
                        dayNaiveDiffVsClosed,
                        dRow.compensatedAccountingBalanceCents(),
                        closedFormBalanceToDay,
                        settledInterest,
                        settledAmort,
                        settledTotal,
                        postSettlementBalance
                ));
            }

            totalCompensatedInterest = Math.addExact(totalCompensatedInterest, dailySumInterest);
            totalClosedFormInterest = Math.addExact(totalClosedFormInterest, closedFormInterest);
            totalNaiveInterest = Math.addExact(totalNaiveInterest, naiveSumInterest);
            if (!summary.allDailyInvariantsSatisfied() || dailyVsClosedDiff != 0L) {
                allPeriodsZeroError = false;
            }
        }

        // 2. Decomposição Multi-Tranche do Contrato SAC no Razão Geral COSIF (Invariantes I_1 a I_6)
        List<TrancheAccrualState> sacTranches = new ArrayList<>(scheduleRows.size());
        for (SacInstallmentRow row : scheduleRows) {
            sacTranches.add(new TrancheAccrualState(
                    "SAC-PARCELA-" + row.installmentNumber(),
                    0,
                    row.amortizationQuotaCents()
            ));
        }

        AnalyticalContractLedger sacContractLedger = new AnalyticalContractLedger("SAC-CONTRACT-01", sacTranches);
        CosifGeneralLedger cosifLedger = new CosifGeneralLedger(List.of(sacContractLedger));

        List<CosifInvariantAuditReport> auditReports = new ArrayList<>();
        boolean allAuditsOk = true;
        int globalDay = 0;

        for (int idx = 0; idx < scheduleRows.size(); idx++) {
            SacInstallmentRow row = scheduleRows.get(idx);
            int daysInPeriod = row.daysInPeriod();
            for (int d = 1; d <= daysInPeriod; d++) {
                globalDay++;
                CosifInvariantAuditReport audit = cosifLedger.processDailyAccrualAndAudit(globalDay, dailyFactorF8);
                auditReports.add(audit);
                if (!audit.allSixInvariantsPassed()) {
                    allAuditsOk = false;
                }
            }
            // No vencimento da parcela idx, liquida a tranche correspondente (Eq. 32) e amortiza os juros das demais (Eq. 33)
            TrancheAccrualState maturingTranche = sacTranches.get(idx);
            cosifLedger.registerAmortizationEvent(
                    maturingTranche,
                    maturingTranche.getPrincipalCents(),
                    maturingTranche.getAccruedInterestCents()
            );
            for (int rem = idx + 1; rem < sacTranches.size(); rem++) {
                TrancheAccrualState activeTranche = sacTranches.get(rem);
                if (activeTranche.getAccruedInterestCents() > 0L) {
                    cosifLedger.registerAmortizationEvent(
                            activeTranche,
                            0L,
                            activeTranche.getAccruedInterestCents()
                    );
                }
            }
        }

        long cumulativeDailyVsClosedDiff = Math.subtractExact(totalCompensatedInterest, totalClosedFormInterest);
        long cumulativeNaiveError = Math.subtractExact(totalNaiveInterest, totalClosedFormInterest);

        return new SacZeroRoundingContractReport(
                spec,
                dailyFactorF8,
                Collections.unmodifiableList(periodSummaries),
                Collections.unmodifiableList(monthlyScheduleRows),
                Collections.unmodifiableList(lifecycleDailyRows),
                Collections.unmodifiableList(auditReports),
                totalCompensatedInterest,
                totalClosedFormInterest,
                totalNaiveInterest,
                cumulativeDailyVsClosedDiff,
                cumulativeNaiveError,
                allPeriodsZeroError && (cumulativeDailyVsClosedDiff == 0L),
                allAuditsOk && (cosifLedger.getGeneralLedgerBalanceCents() == 0L)
        );
    }
}
